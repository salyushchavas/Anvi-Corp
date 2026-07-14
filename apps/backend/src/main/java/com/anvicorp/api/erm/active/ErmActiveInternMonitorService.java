package com.anvicorp.api.erm.active;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.erm.active.ErmActiveDtos.ActiveInternListPage;
import com.anvicorp.api.erm.active.ErmActiveDtos.ActiveInternRow;
import com.anvicorp.api.erm.active.ErmActiveDtos.CardState;
import com.anvicorp.api.erm.active.ErmActiveDtos.MonitorState;
import com.anvicorp.api.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ERM Phase 6 — Active Intern Monitor. The compact list + per-intern
 * monitor view (6 cards). Card state rules implement the doc §9 matrix
 * verbatim.
 *
 * <p>Each card's state computation lives in its own small SQL — the
 * service is the orchestrator, no JPA entity graph here. Keeps the
 * per-row cost predictable + the list page ≤ ~100ms even at 1000 active
 * interns.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmActiveInternMonitorService {

    private final JdbcTemplate jdbc;

    // ── List ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ActiveInternListPage list(
            String scope, String stateFilter,
            UUID trainerId, UUID evaluatorId, UUID managerId,
            String search, User caller, int page, int pageSize) {
        int p = Math.max(0, page);
        int ps = Math.min(100, Math.max(1, pageSize));

        StringBuilder where = new StringBuilder(
                " WHERE il.active_status = 'ACTIVE' ");
        List<Object> params = new ArrayList<>();
        if ("mine".equalsIgnoreCase(scope) && caller != null) {
            where.append(" AND (il.erm_id IS NULL OR il.erm_id = ?) ");
            params.add(caller.getId());
        }
        if (trainerId != null) {
            where.append(" AND il.trainer_id = ? ");
            params.add(trainerId);
        }
        if (evaluatorId != null) {
            where.append(" AND il.evaluator_id = ? ");
            params.add(evaluatorId);
        }
        if (managerId != null) {
            where.append(" AND il.manager_id = ? ");
            params.add(managerId);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(u.full_name) LIKE ? "
                    + "OR LOWER(u.email) LIKE ? OR LOWER(il.employee_id) LIKE ?) ");
            String s = "%" + search.trim().toLowerCase() + "%";
            params.add(s); params.add(s); params.add(s);
        }

        long total;
        try {
            Long c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM intern_lifecycles il "
                            + "JOIN users u ON u.id = il.user_id" + where,
                    Long.class, params.toArray());
            total = c == null ? 0L : c;
        } catch (Exception e) {
            log.warn("[ActiveInterns] count failed: {}", e.getMessage());
            total = 0L;
        }

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(ps);
        pageParams.add(p * ps);
        List<ActiveInternRow> rows;
        try {
            rows = jdbc.query(
                    "SELECT il.id AS lifecycle_id, il.user_id, u.full_name, il.employee_id, "
                            + "       il.trainer_id, tu.full_name AS trainer_name, "
                            + "       il.evaluator_id, eu.full_name AS evaluator_name, "
                            + "       il.manager_id, mu.full_name AS manager_name, "
                            + "       il.erm_id, ru.full_name AS erm_name, il.started_at "
                            + "  FROM intern_lifecycles il "
                            + "  JOIN users u ON u.id = il.user_id "
                            + "  LEFT JOIN users tu ON tu.id = il.trainer_id "
                            + "  LEFT JOIN users eu ON eu.id = il.evaluator_id "
                            + "  LEFT JOIN users mu ON mu.id = il.manager_id "
                            + "  LEFT JOIN users ru ON ru.id = il.erm_id "
                            + where
                            + " ORDER BY u.full_name ASC "
                            + " LIMIT ? OFFSET ?",
                    pageParams.toArray(),
                    this::mapBasicRow);
        } catch (Exception e) {
            log.warn("[ActiveInterns] page failed: {}", e.getMessage());
            rows = List.of();
        }

        // Compute 6 monitor states + exception counts per row.
        List<ActiveInternRow> hydrated = new ArrayList<>(rows.size());
        for (ActiveInternRow basic : rows) {
            try {
                hydrated.add(hydrateStates(basic));
            } catch (Exception e) {
                log.warn("[ActiveInterns] hydrate failed for lifecycle {}: {}",
                        basic.internLifecycleId(), e.getMessage());
                hydrated.add(basic);
            }
        }

        // Optional state filter (post-hydration so each row is consistent).
        if (stateFilter != null && !stateFilter.isBlank()
                && !"all".equalsIgnoreCase(stateFilter)) {
            hydrated = filterByCardState(hydrated, stateFilter);
        }

        int totalPages = ps == 0 ? 0 : (int) Math.ceil((double) total / ps);
        return new ActiveInternListPage(hydrated, p, ps, total, totalPages);
    }

    private List<ActiveInternRow> filterByCardState(
            List<ActiveInternRow> rows, String filter) {
        CardState target = switch (filter.toUpperCase()) {
            case "URGENT" -> CardState.URGENT;
            case "WARN"   -> CardState.WARN;
            case "OK"     -> CardState.OK;
            default -> null;
        };
        if (target == null) return rows;
        List<ActiveInternRow> out = new ArrayList<>();
        for (ActiveInternRow r : rows) {
            if (Set.of(r.project().state(), r.trainerMeeting().state(),
                    r.evaluation().state(), r.timesheet().state(),
                    r.compliance().state(), r.escalations().state())
                    .contains(target)) {
                out.add(r);
            }
        }
        return out;
    }

    private ActiveInternRow mapBasicRow(ResultSet rs, int n) throws SQLException {
        Instant startedAt = instantOf(rs.getTimestamp("started_at"));
        int daysActive = startedAt == null ? 0
                : (int) Duration.between(startedAt, Instant.now()).toDays();
        MonitorState empty = new MonitorState(CardState.OK, "—", null);
        return new ActiveInternRow(
                nullableUuid(rs.getString("lifecycle_id")),
                nullableUuid(rs.getString("user_id")),
                rs.getString("full_name"),
                rs.getString("employee_id"),
                nullableUuid(rs.getString("trainer_id")),
                rs.getString("trainer_name"),
                nullableUuid(rs.getString("evaluator_id")),
                rs.getString("evaluator_name"),
                nullableUuid(rs.getString("manager_id")),
                rs.getString("manager_name"),
                nullableUuid(rs.getString("erm_id")),
                rs.getString("erm_name"),
                startedAt,
                Math.max(0, daysActive),
                empty, empty, empty, empty, empty, empty,
                0L, 0L);
    }

    // ── Card state computation ──────────────────────────────────────────

    private ActiveInternRow hydrateStates(ActiveInternRow basic) {
        UUID userId = basic.internUserId();
        UUID lifecycleId = basic.internLifecycleId();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        MonitorState project = computeProjectState(userId, basic.startedAt(), today);
        MonitorState meeting = computeMeetingState(lifecycleId);
        MonitorState evaluation = computeEvaluationState(lifecycleId);
        MonitorState timesheet = computeTimesheetState(userId, today);
        MonitorState compliance = computeComplianceState(userId, today);
        ExceptionCounts ec = countExceptions(userId);
        MonitorState escalations = computeEscalationState(ec);

        return new ActiveInternRow(
                basic.internLifecycleId(), basic.internUserId(),
                basic.internName(), basic.employeeId(),
                basic.trainerId(), basic.trainerName(),
                basic.evaluatorId(), basic.evaluatorName(),
                basic.managerId(), basic.managerName(),
                basic.ermId(), basic.ermName(),
                basic.startedAt(), basic.daysActive(),
                project, meeting, evaluation, timesheet, compliance, escalations,
                ec.open(), ec.urgent());
    }

    private MonitorState computeProjectState(UUID userId, Instant startedAt,
                                              LocalDate today) {
        try {
            Map<String, Object> r = jdbc.queryForMap(
                    "SELECT "
                            + "  (SELECT COUNT(*) FROM project_assignments "
                            + "     WHERE intern_id = ? "
                            + "       AND status IN ('IN_PROGRESS','SUBMITTED','RETURNED','ASSIGNED')) AS active_count, "
                            + "  (SELECT MIN(due_date) FROM project_assignments "
                            + "     WHERE intern_id = ? "
                            + "       AND status IN ('IN_PROGRESS','SUBMITTED','RETURNED','ASSIGNED') "
                            + "       AND due_date IS NOT NULL) AS nearest_due, "
                            + "  (SELECT MAX(assignment_date) FROM project_assignments "
                            + "     WHERE intern_id = ?) AS last_assignment",
                    userId, userId, userId);
            int active = ((Number) r.getOrDefault("active_count", 0)).intValue();
            LocalDate nearest = toLocalDate((java.sql.Date) r.get("nearest_due"));
            LocalDate lastAssign = toLocalDate((java.sql.Date) r.get("last_assignment"));

            if (active == 0) {
                long daysSinceLast = lastAssign == null
                        ? (startedAt == null ? Long.MAX_VALUE
                            : daysBetween(startedAt.atZone(ZoneOffset.UTC).toLocalDate(), today))
                        : daysBetween(lastAssign, today);
                long daysSinceStart = startedAt == null ? 0
                        : daysBetween(startedAt.atZone(ZoneOffset.UTC).toLocalDate(), today);
                if (daysSinceStart >= 5) {
                    return new MonitorState(CardState.URGENT,
                            "No active project", "≥ 5 days since lifecycle started");
                }
                if (daysSinceLast > 14) {
                    return new MonitorState(CardState.WARN,
                            "No new assignment",
                            daysSinceLast + " days since last project");
                }
                return new MonitorState(CardState.OK, "Between projects", null);
            }
            if (nearest == null) {
                return new MonitorState(CardState.OK,
                        active + " active project(s)", "no due date");
            }
            long dueIn = daysBetween(today, nearest);
            if (dueIn < 0) {
                return new MonitorState(CardState.URGENT,
                        "Project overdue",
                        Math.abs(dueIn) + " day(s) past due");
            }
            if (dueIn < 3) {
                return new MonitorState(CardState.WARN,
                        "Project due soon", "due in " + dueIn + " day(s)");
            }
            return new MonitorState(CardState.OK,
                    active + " active project(s)", "next due in " + dueIn + "d");
        } catch (Exception e) {
            log.debug("[ActiveInterns] project state failed: {}", e.getMessage());
            return new MonitorState(CardState.OK, "—", null);
        }
    }

    private MonitorState computeMeetingState(UUID lifecycleId) {
        try {
            Map<String, Object> r = jdbc.queryForMap(
                    "SELECT "
                            + "  (SELECT MAX(scheduled_for) FROM weekly_meetings "
                            + "     WHERE intern_lifecycle_id = ? "
                            + "       AND status = 'COMPLETED') AS last_completed, "
                            + "  (SELECT MIN(scheduled_for) FROM weekly_meetings "
                            + "     WHERE intern_lifecycle_id = ? "
                            + "       AND status = 'SCHEDULED' "
                            + "       AND scheduled_for > NOW()) AS next_scheduled, "
                            + "  EXISTS (SELECT 1 FROM weekly_meetings "
                            + "             WHERE intern_lifecycle_id = ? "
                            + "               AND status = 'NO_SHOW' "
                            + "               AND scheduled_for > NOW() - INTERVAL '14 days') AS recent_noshow",
                    lifecycleId, lifecycleId, lifecycleId);
            Instant lastCompleted = instantOf((java.sql.Timestamp) r.get("last_completed"));
            Instant nextScheduled = instantOf((java.sql.Timestamp) r.get("next_scheduled"));
            boolean recentNoShow = Boolean.TRUE.equals(r.get("recent_noshow"));

            if (recentNoShow) {
                return new MonitorState(CardState.URGENT,
                        "Recent no-show", null);
            }
            long daysSinceLast = lastCompleted == null
                    ? Long.MAX_VALUE
                    : Duration.between(lastCompleted, Instant.now()).toDays();
            long daysUntilNext = nextScheduled == null
                    ? Long.MAX_VALUE
                    : Duration.between(Instant.now(), nextScheduled).toDays();
            if (daysSinceLast <= 7 || daysUntilNext <= 7) {
                return new MonitorState(CardState.OK, "On cadence",
                        nextScheduled != null ? "next in " + daysUntilNext + "d" : null);
            }
            if (daysSinceLast >= 14) {
                return new MonitorState(CardState.URGENT,
                        "≥ 14 days, no meeting", null);
            }
            return new MonitorState(CardState.WARN,
                    "Catch-up needed",
                    daysSinceLast == Long.MAX_VALUE
                            ? "No meeting on record"
                            : daysSinceLast + " days since last");
        } catch (Exception e) {
            log.debug("[ActiveInterns] meeting state failed: {}", e.getMessage());
            return new MonitorState(CardState.OK, "—", null);
        }
    }

    private MonitorState computeEvaluationState(UUID lifecycleId) {
        try {
            Instant lastPub = jdbc.queryForObject(
                    "SELECT MAX(published_at) FROM intern_evaluations "
                            + " WHERE intern_lifecycle_id = ? "
                            + "   AND evaluation_type = 'MONTHLY' "
                            + "   AND status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED')",
                    (rs, n) -> instantOf(rs.getTimestamp(1)), lifecycleId);
            if (lastPub == null) {
                return new MonitorState(CardState.WARN, "No monthly eval yet", null);
            }
            long days = Duration.between(lastPub, Instant.now()).toDays();
            if (days <= 30) {
                return new MonitorState(CardState.OK,
                        "Up to date", days + " days since last");
            }
            if (days <= 45) {
                return new MonitorState(CardState.WARN,
                        "Due soon", days + " days since last");
            }
            return new MonitorState(CardState.URGENT,
                    "Overdue", days + " days since last");
        } catch (Exception e) {
            log.debug("[ActiveInterns] evaluation state failed: {}", e.getMessage());
            return new MonitorState(CardState.OK, "—", null);
        }
    }

    private MonitorState computeTimesheetState(UUID userId, LocalDate today) {
        try {
            // Resolve candidate id once.
            UUID candidateId = jdbc.queryForObject(
                    "SELECT id FROM candidates WHERE user_id = ?",
                    (rs, n) -> nullableUuid(rs.getString(1)), userId);
            if (candidateId == null) {
                return new MonitorState(CardState.OK, "No timesheets", null);
            }
            LocalDate currentWeekStart = mondayOf(today);
            String currentStatus = jdbc.query(
                    "SELECT status FROM timesheets WHERE intern_id = ? AND week_start = ?",
                    new Object[]{candidateId, currentWeekStart},
                    (rs, n) -> rs.getString(1)).stream().findFirst().orElse("MISSING");

            int recentRejections = countOrZero(
                    "SELECT COUNT(*) FROM timesheets "
                            + " WHERE intern_id = ? "
                            + "   AND status = 'REJECTED' "
                            + "   AND week_start > CURRENT_DATE - INTERVAL '28 days'",
                    candidateId);
            int recentMissing = countOrZero(
                    "SELECT COUNT(*) FROM (SELECT generate_series(0,3) AS n) g "
                            + " WHERE NOT EXISTS (SELECT 1 FROM timesheets t "
                            + "                     WHERE t.intern_id = ? "
                            + "                       AND t.week_start = ? - (g.n || ' weeks')::interval)",
                    candidateId, currentWeekStart);

            if (recentRejections >= 2 || recentMissing >= 2) {
                return new MonitorState(CardState.URGENT,
                        recentRejections >= 2 ? "Repeated rejections" : "≥ 2 weeks missing",
                        "current: " + currentStatus);
            }
            if (Set.of("SUBMITTED", "APPROVED").contains(currentStatus)) {
                return new MonitorState(CardState.OK,
                        "Current week " + currentStatus.toLowerCase(), null);
            }
            if (recentRejections >= 1) {
                return new MonitorState(CardState.WARN,
                        "Recent rejection", "current: " + currentStatus);
            }
            // Previous-week missing → WARN.
            LocalDate prevWeek = currentWeekStart.minusWeeks(1);
            boolean prevSubmitted = countOrZero(
                    "SELECT COUNT(*) FROM timesheets WHERE intern_id = ? AND week_start = ? "
                            + "  AND status IN ('SUBMITTED','APPROVED')",
                    candidateId, prevWeek) > 0;
            if (!prevSubmitted) {
                return new MonitorState(CardState.WARN,
                        "Previous week not submitted",
                        "current: " + currentStatus);
            }
            return new MonitorState(CardState.OK,
                    "Current week " + currentStatus.toLowerCase(), null);
        } catch (Exception e) {
            log.debug("[ActiveInterns] timesheet state failed: {}", e.getMessage());
            return new MonitorState(CardState.OK, "—", null);
        }
    }

    private MonitorState computeComplianceState(UUID userId, LocalDate today) {
        try {
            int urgent = countOrZero(
                    "SELECT COUNT(*) FROM exception_records "
                            + " WHERE subject_user_id = ? "
                            + "   AND status IN ('OPEN','ASSIGNED','IN_PROGRESS') "
                            + "   AND exception_type IN ('I9_EVERIFY_TIMING_RISK','EVERIFY_NONCONFIRMATION',"
                            + "                          'WORK_AUTH_EXPIRING_30','I983_EVALUATION_OVERDUE')",
                    userId);
            int warn = countOrZero(
                    "SELECT COUNT(*) FROM exception_records "
                            + " WHERE subject_user_id = ? "
                            + "   AND status IN ('OPEN','ASSIGNED','IN_PROGRESS') "
                            + "   AND severity = 'WARN' "
                            + "   AND exception_type IN ('ONBOARDING_DOC_REJECTED')",
                    userId);
            if (urgent >= 1) {
                return new MonitorState(CardState.URGENT,
                        urgent + " urgent compliance alert(s)", null);
            }
            if (warn >= 1) {
                return new MonitorState(CardState.WARN,
                        warn + " compliance alert(s)", null);
            }
            return new MonitorState(CardState.OK, "All clear", null);
        } catch (Exception e) {
            log.debug("[ActiveInterns] compliance state failed: {}", e.getMessage());
            return new MonitorState(CardState.OK, "—", null);
        }
    }

    private MonitorState computeEscalationState(ExceptionCounts ec) {
        if (ec.urgent() >= 1) {
            return new MonitorState(CardState.URGENT,
                    ec.urgent() + " urgent open", null);
        }
        if (ec.open() >= 1) {
            return new MonitorState(CardState.WARN,
                    ec.open() + " open", null);
        }
        return new MonitorState(CardState.OK, "No open exceptions", null);
    }

    private record ExceptionCounts(long open, long urgent) {}

    private ExceptionCounts countExceptions(UUID userId) {
        try {
            Map<String, Object> r = jdbc.queryForMap(
                    "SELECT COUNT(*) AS total, "
                            + "       COUNT(*) FILTER (WHERE severity = 'URGENT') AS urg "
                            + "  FROM exception_records "
                            + " WHERE subject_user_id = ? "
                            + "   AND status IN ('OPEN','ASSIGNED','IN_PROGRESS')",
                    userId);
            long total = ((Number) r.getOrDefault("total", 0L)).longValue();
            long urgent = ((Number) r.getOrDefault("urg", 0L)).longValue();
            return new ExceptionCounts(total, urgent);
        } catch (Exception e) {
            return new ExceptionCounts(0, 0);
        }
    }

    // ── Detail ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ErmActiveDtos.InternMonitorView getMonitor(UUID lifecycleId, User caller) {
        ActiveInternRow basic;
        try {
            basic = jdbc.queryForObject(
                    "SELECT il.id AS lifecycle_id, il.user_id, u.full_name, il.employee_id, "
                            + "       il.trainer_id, tu.full_name AS trainer_name, "
                            + "       il.evaluator_id, eu.full_name AS evaluator_name, "
                            + "       il.manager_id, mu.full_name AS manager_name, "
                            + "       il.erm_id, ru.full_name AS erm_name, il.started_at "
                            + "  FROM intern_lifecycles il "
                            + "  JOIN users u ON u.id = il.user_id "
                            + "  LEFT JOIN users tu ON tu.id = il.trainer_id "
                            + "  LEFT JOIN users eu ON eu.id = il.evaluator_id "
                            + "  LEFT JOIN users mu ON mu.id = il.manager_id "
                            + "  LEFT JOIN users ru ON ru.id = il.erm_id "
                            + " WHERE il.id = ?",
                    new Object[]{lifecycleId},
                    this::mapBasicRow);
        } catch (Exception e) {
            throw new ResourceNotFoundException("Active intern lifecycle not found: " + lifecycleId);
        }
        ActiveInternRow hydrated = hydrateStates(basic);

        ErmActiveDtos.InternProfile profile = loadProfile(basic);

        ErmActiveDtos.ProjectCard project = loadProjectCard(basic);
        ErmActiveDtos.TrainerMeetingCard meeting = loadMeetingCard(basic);
        ErmActiveDtos.EvaluationCard evaluation = loadEvaluationCard(basic);
        ErmActiveDtos.TimesheetCard timesheet = loadTimesheetCard(basic);
        ErmActiveDtos.ComplianceCard compliance = loadComplianceCard(basic);
        ErmActiveDtos.EscalationsCard escalations = loadEscalationsCard(basic);
        List<ErmActiveDtos.ActivityEntry> activity = loadRecentActivity(basic.internUserId());

        return new ErmActiveDtos.InternMonitorView(
                lifecycleId, profile, hydrated,
                project, meeting, evaluation,
                timesheet, compliance, escalations, activity);
    }

    private ErmActiveDtos.InternProfile loadProfile(ActiveInternRow basic) {
        Map<String, Object> base;
        try {
            base = jdbc.queryForMap(
                    "SELECT u.email, il.hired_at, w.work_auth_type, "
                            + "       o.role_title, o.compensation_summary, o.signed_at "
                            + "  FROM intern_lifecycles il "
                            + "  JOIN users u ON u.id = il.user_id "
                            + "  LEFT JOIN work_authorization_records w ON w.user_id = il.user_id "
                            + "  LEFT JOIN applications a ON a.candidate_id = "
                            + "          (SELECT id FROM candidates WHERE user_id = il.user_id) "
                            + "  LEFT JOIN offers o ON o.application_id = a.id "
                            + "                     AND o.status = 'SIGNED' "
                            + "                     AND o.archived_at IS NULL "
                            + " WHERE il.id = ? "
                            + " LIMIT 1",
                    basic.internLifecycleId());
        } catch (Exception e) {
            base = new HashMap<>();
        }
        return new ErmActiveDtos.InternProfile(
                basic.internUserId(),
                basic.internName(),
                (String) base.get("email"),
                basic.employeeId(),
                (String) base.get("work_auth_type"),
                (String) base.get("role_title"),
                (String) base.get("compensation_summary"),
                instantOf((java.sql.Timestamp) base.get("signed_at")),
                basic.startedAt(),
                instantOf((java.sql.Timestamp) base.get("hired_at")));
    }

    private ErmActiveDtos.ProjectCard loadProjectCard(ActiveInternRow basic) {
        List<ErmActiveDtos.ProjectSummary> projects;
        try {
            projects = jdbc.query(
                    "SELECT pa.id, p.title, pa.status, pa.assignment_date, pa.due_date, "
                            + "       pa.submitted_at, pa.assigned_by_id "
                            + "  FROM project_assignments pa "
                            + "  LEFT JOIN projects p ON p.id = pa.project_id "
                            + " WHERE pa.intern_id = ? "
                            + " ORDER BY pa.assignment_date DESC NULLS LAST "
                            + " LIMIT 5",
                    new Object[]{basic.internUserId()},
                    (rs, n) -> new ErmActiveDtos.ProjectSummary(
                            nullableUuid(rs.getString("id")),
                            rs.getString("title"),
                            rs.getString("status"),
                            toLocalDate(rs.getDate("assignment_date")),
                            toLocalDate(rs.getDate("due_date")),
                            instantOf(rs.getTimestamp("submitted_at")),
                            nullableUuid(rs.getString("assigned_by_id"))));
        } catch (Exception e) {
            projects = List.of();
        }
        boolean noActive = projects.stream().noneMatch(p ->
                Set.of("ASSIGNED", "IN_PROGRESS", "SUBMITTED", "RETURNED").contains(p.status()));
        MonitorState state = hydrateStates(basic).project();
        return new ErmActiveDtos.ProjectCard(state, projects, noActive);
    }

    private ErmActiveDtos.TrainerMeetingCard loadMeetingCard(ActiveInternRow basic) {
        List<ErmActiveDtos.MeetingSummary> upcoming;
        List<ErmActiveDtos.MeetingSummary> past;
        try {
            upcoming = jdbc.query(
                    "SELECT id, topic, status, scheduled_for, host_user_id "
                            + "  FROM weekly_meetings "
                            + " WHERE intern_lifecycle_id = ? AND scheduled_for > NOW() "
                            + " ORDER BY scheduled_for ASC LIMIT 5",
                    new Object[]{basic.internLifecycleId()},
                    (rs, n) -> new ErmActiveDtos.MeetingSummary(
                            nullableUuid(rs.getString("id")), rs.getString("topic"),
                            rs.getString("status"),
                            instantOf(rs.getTimestamp("scheduled_for")),
                            nullableUuid(rs.getString("host_user_id"))));
            past = jdbc.query(
                    "SELECT id, topic, status, scheduled_for, host_user_id "
                            + "  FROM weekly_meetings "
                            + " WHERE intern_lifecycle_id = ? AND scheduled_for <= NOW() "
                            + " ORDER BY scheduled_for DESC LIMIT 5",
                    new Object[]{basic.internLifecycleId()},
                    (rs, n) -> new ErmActiveDtos.MeetingSummary(
                            nullableUuid(rs.getString("id")), rs.getString("topic"),
                            rs.getString("status"),
                            instantOf(rs.getTimestamp("scheduled_for")),
                            nullableUuid(rs.getString("host_user_id"))));
        } catch (Exception e) {
            upcoming = List.of();
            past = List.of();
        }
        MonitorState state = computeMeetingState(basic.internLifecycleId());
        return new ErmActiveDtos.TrainerMeetingCard(state, upcoming, past, upcoming.isEmpty());
    }

    private ErmActiveDtos.EvaluationCard loadEvaluationCard(ActiveInternRow basic) {
        List<ErmActiveDtos.EvaluationSummary> evals;
        try {
            evals = jdbc.query(
                    "SELECT id, evaluation_type, status, published_at, scheduled_for, evaluator_id "
                            + "  FROM intern_evaluations "
                            + " WHERE intern_lifecycle_id = ? "
                            + " ORDER BY COALESCE(published_at, scheduled_for, created_at) DESC NULLS LAST "
                            + " LIMIT 5",
                    new Object[]{basic.internLifecycleId()},
                    (rs, n) -> new ErmActiveDtos.EvaluationSummary(
                            nullableUuid(rs.getString("id")),
                            rs.getString("evaluation_type"),
                            rs.getString("status"),
                            instantOf(rs.getTimestamp("published_at")),
                            instantOf(rs.getTimestamp("scheduled_for")),
                            nullableUuid(rs.getString("evaluator_id"))));
        } catch (Exception e) {
            evals = List.of();
        }
        MonitorState state = computeEvaluationState(basic.internLifecycleId());
        return new ErmActiveDtos.EvaluationCard(state, evals, true);
    }

    private ErmActiveDtos.TimesheetCard loadTimesheetCard(ActiveInternRow basic) {
        UUID candidateId;
        try {
            candidateId = jdbc.queryForObject(
                    "SELECT id FROM candidates WHERE user_id = ?",
                    (rs, n) -> nullableUuid(rs.getString(1)), basic.internUserId());
        } catch (Exception e) {
            candidateId = null;
        }
        if (candidateId == null) {
            return new ErmActiveDtos.TimesheetCard(
                    new MonitorState(CardState.OK, "No candidate record", null),
                    "MISSING", mondayOf(LocalDate.now()), List.of(), "0");
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate currentWeek = mondayOf(today);
        List<ErmActiveDtos.TimesheetSummary> last4;
        try {
            last4 = jdbc.query(
                    "SELECT id, week_start, status, hours, approved_at "
                            + "  FROM timesheets "
                            + " WHERE intern_id = ? "
                            + "   AND week_start > CURRENT_DATE - INTERVAL '28 days' "
                            + " ORDER BY week_start DESC",
                    new Object[]{candidateId},
                    (rs, n) -> new ErmActiveDtos.TimesheetSummary(
                            nullableUuid(rs.getString("id")),
                            toLocalDate(rs.getDate("week_start")),
                            rs.getString("status"),
                            rs.getBigDecimal("hours") != null
                                    ? rs.getBigDecimal("hours").toPlainString() : "0",
                            instantOf(rs.getTimestamp("approved_at"))));
        } catch (Exception e) {
            last4 = List.of();
        }
        String currentStatus = last4.stream()
                .filter(t -> currentWeek.equals(t.weekStart()))
                .map(ErmActiveDtos.TimesheetSummary::status)
                .findFirst().orElse("MISSING");
        String totalApproved = "0";
        try {
            java.math.BigDecimal sum = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(hours), 0) FROM timesheets "
                            + " WHERE intern_id = ? AND status = 'APPROVED'",
                    java.math.BigDecimal.class, candidateId);
            totalApproved = sum != null ? sum.toPlainString() : "0";
        } catch (Exception ignored) {}
        MonitorState state = computeTimesheetState(basic.internUserId(), today);
        return new ErmActiveDtos.TimesheetCard(
                state, currentStatus, currentWeek, last4, totalApproved);
    }

    private ErmActiveDtos.ComplianceCard loadComplianceCard(ActiveInternRow basic) {
        String workAuthType = null;
        LocalDate workAuthExp = null;
        String i9Status = null;
        String everifyStatus = null;
        String i983Status = "NOT_REQUIRED";
        try {
            Map<String, Object> r = jdbc.queryForMap(
                    "SELECT w.work_auth_type, "
                            + "       LEAST(COALESCE(w.authorized_until, DATE '9999-12-31'), "
                            + "             COALESCE(w.ead_expiration,    DATE '9999-12-31'), "
                            + "             COALESCE(w.i20_expiration,    DATE '9999-12-31')) AS earliest, "
                            + "       w.i983_required, "
                            + "       (SELECT f.status FROM i9_forms f "
                            + "          JOIN candidates c ON c.id = f.candidate_id "
                            + "         WHERE c.user_id = ?) AS i9_status, "
                            + "       (SELECT ec.status FROM everify_cases ec "
                            + "          JOIN i9_forms f ON f.id = ec.i9_form_id "
                            + "          JOIN candidates c ON c.id = f.candidate_id "
                            + "         WHERE c.user_id = ?) AS everify_status "
                            + "  FROM work_authorization_records w "
                            + " WHERE w.user_id = ?",
                    basic.internUserId(), basic.internUserId(), basic.internUserId());
            workAuthType = (String) r.get("work_auth_type");
            java.sql.Date d = (java.sql.Date) r.get("earliest");
            workAuthExp = d != null && d.toLocalDate().isBefore(LocalDate.of(9999, 1, 1))
                    ? d.toLocalDate() : null;
            i9Status = (String) r.get("i9_status");
            everifyStatus = (String) r.get("everify_status");
            if (Boolean.TRUE.equals(r.get("i983_required"))) {
                i983Status = "REQUIRED";
            }
        } catch (Exception ignored) {}

        List<ErmActiveDtos.ComplianceAlertSummary> alerts = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT exception_type, severity FROM exception_records "
                            + " WHERE subject_user_id = ? "
                            + "   AND status IN ('OPEN','ASSIGNED','IN_PROGRESS') "
                            + "   AND exception_type IN ('I9_EVERIFY_TIMING_RISK','EVERIFY_NONCONFIRMATION',"
                            + "                          'WORK_AUTH_EXPIRING_30','I983_EVALUATION_OVERDUE',"
                            + "                          'ONBOARDING_DOC_REJECTED')",
                    new Object[]{basic.internUserId()},
                    (rs, n) -> {
                        alerts.add(new ErmActiveDtos.ComplianceAlertSummary(
                                rs.getString("exception_type"), null, rs.getString("severity")));
                        return null;
                    });
        } catch (Exception ignored) {}

        MonitorState state = computeComplianceState(basic.internUserId(), LocalDate.now());
        return new ErmActiveDtos.ComplianceCard(
                state, workAuthType, workAuthExp, i9Status, everifyStatus, i983Status, alerts);
    }

    private ErmActiveDtos.EscalationsCard loadEscalationsCard(ActiveInternRow basic) {
        List<ErmActiveDtos.EscalationSummary> open;
        List<ErmActiveDtos.EscalationSummary> past;
        try {
            open = jdbc.query(
                    "SELECT id, exception_type, severity, status, opened_at, "
                            + "       EXTRACT(EPOCH FROM (NOW() - opened_at))/86400 AS age_days "
                            + "  FROM exception_records "
                            + " WHERE subject_user_id = ? "
                            + "   AND status IN ('OPEN','ASSIGNED','IN_PROGRESS') "
                            + " ORDER BY CASE severity WHEN 'URGENT' THEN 0 "
                            + "                          WHEN 'WARN'   THEN 1 ELSE 2 END, opened_at ASC",
                    new Object[]{basic.internUserId()},
                    (rs, n) -> new ErmActiveDtos.EscalationSummary(
                            nullableUuid(rs.getString("id")),
                            rs.getString("exception_type"),
                            rs.getString("severity"),
                            rs.getString("status"),
                            instantOf(rs.getTimestamp("opened_at")),
                            Math.max(0, (int) rs.getDouble("age_days"))));
            past = jdbc.query(
                    "SELECT id, exception_type, severity, status, opened_at, "
                            + "       EXTRACT(EPOCH FROM (NOW() - opened_at))/86400 AS age_days "
                            + "  FROM exception_records "
                            + " WHERE subject_user_id = ? "
                            + "   AND status IN ('RESOLVED','DISMISSED','AUTO_RESOLVED') "
                            + " ORDER BY resolved_at DESC NULLS LAST LIMIT 10",
                    new Object[]{basic.internUserId()},
                    (rs, n) -> new ErmActiveDtos.EscalationSummary(
                            nullableUuid(rs.getString("id")),
                            rs.getString("exception_type"),
                            rs.getString("severity"),
                            rs.getString("status"),
                            instantOf(rs.getTimestamp("opened_at")),
                            Math.max(0, (int) rs.getDouble("age_days"))));
        } catch (Exception e) {
            open = List.of();
            past = List.of();
        }
        MonitorState state = computeEscalationState(countExceptions(basic.internUserId()));
        return new ErmActiveDtos.EscalationsCard(state, open, past);
    }

    private List<ErmActiveDtos.ActivityEntry> loadRecentActivity(UUID userId) {
        try {
            return jdbc.query(
                    "SELECT al.timestamp, al.entity_type, al.entity_id, al.action, "
                            + "       al.user_id AS actor_id, u.full_name AS actor_name "
                            + "  FROM audit_logs al "
                            + "  LEFT JOIN users u ON u.id = al.user_id "
                            + " WHERE al.subject_user_id = ? "
                            + " ORDER BY al.timestamp DESC LIMIT 20",
                    new Object[]{userId},
                    (rs, n) -> new ErmActiveDtos.ActivityEntry(
                            instantOf(rs.getTimestamp("timestamp")),
                            rs.getString("entity_type"),
                            nullableUuid(rs.getString("entity_id")),
                            rs.getString("action"),
                            nullableUuid(rs.getString("actor_id")),
                            rs.getString("actor_name")));
        } catch (Exception e) {
            log.debug("[ActiveInterns] recent activity failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private int countOrZero(String sql, Object... params) {
        try {
            Long c = jdbc.queryForObject(sql, Long.class, params);
            return c == null ? 0 : c.intValue();
        } catch (Exception e) {
            return 0;
        }
    }

    private static LocalDate mondayOf(LocalDate today) {
        return today.with(WeekFields.of(Locale.US).dayOfWeek(), 1)
                // ISO Monday = 1; Locale.US treats Sunday=1, so adjust:
                .with(java.time.DayOfWeek.MONDAY);
    }

    private static long daysBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null) return 0;
        return java.time.temporal.ChronoUnit.DAYS.between(from, to);
    }

    private static UUID nullableUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private static Instant instantOf(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static LocalDate toLocalDate(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
