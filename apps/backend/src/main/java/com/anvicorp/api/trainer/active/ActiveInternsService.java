package com.anvicorp.api.trainer.active;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.trainer.active.ActiveInternsDtos.*;
import com.anvicorp.api.trainer.dashboard.TrainerThresholds;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Trainer Phase 1 — backs the Active Interns list + detail.
 *
 * <p>Scope is always {@code intern_lifecycles.trainer_id = caller.id}
 * per Trainer doc §3 ("View active interns assigned to the trainer").
 * SUPER_ADMIN bypasses for support / debugging.</p>
 *
 * <p>Field RBAC: Trainer DTOs never carry SSN / DOB / address / W-4 /
 * I-9 / ACH / immigration fields. The DTO records simply don't have
 * those fields; the queries don't select them either.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActiveInternsService {

    private static final ZoneId ZONE = ZoneId.of(TrainerThresholds.DEFAULT_TIMEZONE);

    private final JdbcTemplate jdbc;

    // ── List ─────────────────────────────────────────────────────────────

    /**
     * Phase C — roster scope. {@code ALL} is the Trainer + ERM view (no
     * ownership filter); {@code MANAGER_OWNED} narrows to interns whose
     * {@code intern_lifecycles.manager_id == caller.id}. SUPER_ADMIN
     * always sees {@code ALL} regardless of which scope is requested.
     */
    public enum Scope { ALL, MANAGER_OWNED }

    @Transactional(readOnly = true)
    public ActiveInternListPage list(
            User caller, String search,
            List<String> projectFilter,
            List<String> meetingFilter,
            List<String> evaluationFilter,
            List<String> timesheetFilter,
            Integer yearParam, Integer monthParam,
            int page, int pageSize) {
        // Back-compat overload — Trainer path keeps its existing signature.
        return list(caller, search, projectFilter, meetingFilter,
                evaluationFilter, timesheetFilter,
                yearParam, monthParam, page, pageSize, Scope.ALL);
    }

    @Transactional(readOnly = true)
    public ActiveInternListPage list(
            User caller, String search,
            List<String> projectFilter,
            List<String> meetingFilter,
            List<String> evaluationFilter,
            List<String> timesheetFilter,
            Integer yearParam, Integer monthParam,
            int page, int pageSize,
            Scope scope) {
        requireRosterRole(caller, scope);
        int p = Math.max(0, page);
        int ps = Math.min(100, Math.max(1, pageSize));

        // Resolve the period. Single org-wide Trainer → no trainer_id
        // filter; ERM sees all; Manager is filtered by manager_id.
        // "Active that month" = started on/before month-end AND
        // (still active OR ended on/after month-start).
        YearMonth period = (yearParam != null && monthParam != null)
                ? YearMonth.of(yearParam, monthParam)
                : YearMonth.now(ZONE);
        String monthYear = period.toString(); // "YYYY-MM"
        LocalDate periodStart = period.atDay(1);
        LocalDate periodEndExclusive = period.plusMonths(1).atDay(1);
        java.sql.Timestamp tsStart = java.sql.Timestamp.from(
                periodStart.atStartOfDay(ZONE).toInstant());
        java.sql.Timestamp tsEndExclusive = java.sql.Timestamp.from(
                periodEndExclusive.atStartOfDay(ZONE).toInstant());

        // "Active during month" = started on/before period end AND (still active
        // OR ended on/after period start). A freshly-activated intern whose
        // started_at + hired_at were never stamped is still considered active
        // now — fall through to the active_status='ACTIVE' branch so they
        // appear in the roster instead of silently disappearing. The boot-time
        // self-heal in SchemaFixupRunner stamps started_at = NOW() on those
        // rows so the regular predicate covers them on the next deploy.
        StringBuilder where = new StringBuilder(
                " WHERE ( "
                        + "      (COALESCE(il.started_at, il.hired_at) < ? "
                        + "       AND (il.ended_at IS NULL OR il.ended_at >= ?)) "
                        + "   OR (COALESCE(il.started_at, il.hired_at) IS NULL "
                        + "       AND il.active_status = 'ACTIVE' "
                        + "       AND il.ended_at IS NULL) "
                        + " ) ");
        List<Object> params = new ArrayList<>();
        params.add(tsEndExclusive);
        params.add(tsStart);
        // SUPER_ADMIN sees everything regardless of requested scope.
        boolean isSuperAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (scope == Scope.MANAGER_OWNED && !isSuperAdmin) {
            where.append(" AND il.manager_id = ? ");
            params.add(caller.getId());
        }
        if (search != null && !search.isBlank()) {
            String s = "%" + search.trim().toLowerCase() + "%";
            where.append(" AND (LOWER(u.full_name) LIKE ? "
                    + "OR LOWER(u.email) LIKE ? "
                    + "OR LOWER(il.employee_id) LIKE ?) ");
            params.add(s); params.add(s); params.add(s);
        }

        long total = countOrZero(
                "SELECT COUNT(*) FROM intern_lifecycles il "
                        + "  JOIN users u ON u.id = il.user_id" + where,
                params.toArray());

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(ps);
        pageParams.add(p * ps);
        List<ActiveInternRow> raw;
        try {
            raw = jdbc.query(
                    "SELECT il.id AS lifecycle_id, il.user_id, u.full_name, u.email, "
                            + "       u.phone_number, il.employee_id, il.started_at, il.hired_at, "
                            + "       il.trainer_id, il.evaluator_id, il.manager_id, il.erm_id, "
                            + "       tu.full_name AS trainer_name, eu.full_name AS evaluator_name, "
                            + "       mu.full_name AS manager_name, ru.full_name AS erm_name, "
                            + "       c.id AS candidate_id, c.skillset "
                            + "  FROM intern_lifecycles il "
                            + "  JOIN users u ON u.id = il.user_id "
                            + "  LEFT JOIN candidates c ON c.user_id = il.user_id "
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
            raw = List.of();
        }

        // Hydrate per-intern state blocks for the requested month.
        // Page sizes ≤100 so the N+1 hit is acceptable.
        List<ActiveInternRow> hydrated = new ArrayList<>(raw.size());
        for (ActiveInternRow r : raw) {
            hydrated.add(hydrateForMonth(r, period));
        }

        MonthRosterSummary summary = computeRosterSummary(hydrated);
        // Apply Java-side state filters AFTER summary so the strip
        // reflects the whole month, not just what filters surface.
        List<ActiveInternRow> filtered = applyStateFilters(
                hydrated, projectFilter, meetingFilter,
                evaluationFilter, timesheetFilter);

        int totalPages = ps == 0 ? 0 : (int) Math.ceil((double) total / ps);
        return new ActiveInternListPage(filtered, p, ps, total, totalPages,
                monthYear, summary);
    }

    private ActiveInternRow mapBasicRow(ResultSet rs, int n) throws SQLException {
        Instant startedAt = instantOf(rs.getTimestamp("started_at"));
        if (startedAt == null) startedAt = instantOf(rs.getTimestamp("hired_at"));
        LocalDate startDate = startedAt != null ? startedAt.atZone(ZONE).toLocalDate() : null;
        Integer daysActive = startedAt == null ? 0
                : (int) Duration.between(startedAt, Instant.now()).toDays();

        ReportingStructure reporting = new ReportingStructure(
                rs.getString("trainer_name"),
                rs.getString("evaluator_name"),
                rs.getString("manager_name"),
                rs.getString("erm_name"),
                nullableUuid(rs.getString("manager_id")),
                nullableUuid(rs.getString("trainer_id")),
                nullableUuid(rs.getString("evaluator_id")),
                nullableUuid(rs.getString("erm_id")));

        // Empty state blocks; hydrate() fills them.
        CurrentMonthProjectsBlock emptyProjects = new CurrentMonthProjectsBlock(
                currentMonthYear(), null, null, "NO_PROJECTS");
        MeetingStateBlock emptyMeeting = new MeetingStateBlock(null, null, null, "NONE");
        EvaluationStateBlock emptyEval = new EvaluationStateBlock(null, null, null, "NONE");
        TimesheetStateBlock emptyTs = new TimesheetStateBlock(null, null, null, "MISSING",
                0, 0, 0, 0, 0, 0);

        return new ActiveInternRow(
                nullableUuid(rs.getString("lifecycle_id")),
                rs.getString("employee_id"),
                rs.getString("full_name"),
                rs.getString("email"),
                rs.getString("phone_number"),
                rs.getString("skillset"),    // doc §5 "Technology title"
                startDate,
                Math.max(0, daysActive),
                emptyProjects, emptyMeeting, emptyEval, emptyTs,
                reporting);
    }

    private ActiveInternRow hydrate(
            ActiveInternRow basic, String currentMonth, LocalDate currentWeek) {
        // Legacy entry-point kept for getDetail() which still asks for
        // "current month + current week" semantics.
        CurrentMonthProjectsBlock projects = loadMonthProjects(
                basic.internLifecycleId(), currentMonth);
        MeetingStateBlock meeting = loadMeetingState(basic.internLifecycleId());
        EvaluationStateBlock evaluation = loadEvaluationState(basic.internLifecycleId());
        TimesheetStateBlock timesheet = loadTimesheetState(basic, currentWeek);
        return new ActiveInternRow(
                basic.internLifecycleId(),
                basic.employeeId(),
                basic.fullName(),
                basic.email(),
                basic.phone(),
                basic.technologyTitle(),
                basic.startDate(),
                basic.daysActive(),
                projects, meeting, evaluation, timesheet,
                basic.reportingStructure());
    }

    /** Phase A roster: scope every per-intern state block to the
     *  requested month rather than "right now". */
    private ActiveInternRow hydrateForMonth(ActiveInternRow basic, YearMonth period) {
        String monthYear = period.toString();
        CurrentMonthProjectsBlock projects = loadMonthProjects(
                basic.internLifecycleId(), monthYear);
        MeetingStateBlock meeting = loadMeetingState(basic.internLifecycleId());
        EvaluationStateBlock evaluation = loadMonthEvaluationState(
                basic.internLifecycleId(), period, projects);
        TimesheetStateBlock timesheet = loadMonthTimesheetState(basic, period);
        return new ActiveInternRow(
                basic.internLifecycleId(),
                basic.employeeId(),
                basic.fullName(),
                basic.email(),
                basic.phone(),
                basic.technologyTitle(),
                basic.startDate(),
                basic.daysActive(),
                projects, meeting, evaluation, timesheet,
                basic.reportingStructure());
    }

    private CurrentMonthProjectsBlock loadMonthProjects(UUID lifecycleId, String monthYear) {
        if (lifecycleId == null) {
            return new CurrentMonthProjectsBlock(monthYear, null, null, "NO_PROJECTS");
        }
        Map<Short, ProjectSlot> slots = new LinkedHashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT id, title, status, due_date, project_number, "
                            + "       kt_status, kt_completed_at "
                            + "  FROM projects "
                            + " WHERE intern_lifecycle_id = ? "
                            + "   AND month_year = ? "
                            + "   AND status <> 'CANCELLED'",
                    lifecycleId, monthYear)) {
                Short num = r.get("project_number") == null ? null
                        : ((Number) r.get("project_number")).shortValue();
                if (num == null) continue;
                String status = (String) r.get("status");
                LocalDate dd = r.get("due_date") != null
                        ? ((java.sql.Date) r.get("due_date")).toLocalDate() : null;
                String state = projectSlotState(status, dd);
                String ktStatus = (String) r.get("kt_status");
                Instant ktAt = instantOf((java.sql.Timestamp) r.get("kt_completed_at"));
                slots.put(num, new ProjectSlot(
                        nullableUuid(String.valueOf(r.get("id"))),
                        (String) r.get("title"),
                        status, dd, state,
                        ktStatus != null ? ktStatus : "NOT_DONE",
                        ktAt));
            }
        } catch (Exception e) {
            log.debug("[ActiveInterns] project slot load failed: {}", e.getMessage());
        }
        ProjectSlot s1 = slots.get((short) 1);
        ProjectSlot s2 = slots.get((short) 2);
        String overall;
        if (s1 == null && s2 == null) overall = "NO_PROJECTS";
        else if (s1 != null && s2 != null) {
            boolean overdue = "OVERDUE".equals(s1.state()) || "OVERDUE".equals(s2.state());
            boolean bothComplete = "COMPLETED".equals(s1.state()) && "COMPLETED".equals(s2.state());
            overall = overdue ? "OVERDUE" : bothComplete ? "COMPLETE" : "BOTH_ASSIGNED";
        } else {
            ProjectSlot only = s1 != null ? s1 : s2;
            overall = "OVERDUE".equals(only.state()) ? "OVERDUE" : "PARTIAL";
        }
        return new CurrentMonthProjectsBlock(monthYear, s1, s2, overall);
    }

    /**
     * Per-spec gating: Training Evaluation is "—" (NONE) until any
     * project in the month is COMPLETED. Once a project completes →
     * DONE if a MONTHLY evaluation exists overlapping the month;
     * NOT_DONE otherwise. We map NOT_DONE → "OVERDUE" so the existing
     * StateBadge palette renders the right tone (rose) without
     * inventing a new state.
     */
    private EvaluationStateBlock loadMonthEvaluationState(
            UUID lifecycleId, YearMonth period, CurrentMonthProjectsBlock projects) {
        if (lifecycleId == null) {
            return new EvaluationStateBlock(null, null, null, "NONE");
        }
        boolean anyComplete =
                (projects.project1() != null && "COMPLETED".equals(projects.project1().state()))
                || (projects.project2() != null && "COMPLETED".equals(projects.project2().state()));
        if (!anyComplete) {
            return new EvaluationStateBlock(null, null, null, "NONE");
        }
        LocalDate periodStart = period.atDay(1);
        LocalDate periodEnd = period.atEndOfMonth();
        Instant publishedAt = null;
        try {
            publishedAt = jdbc.query(
                    "SELECT MAX(published_at) FROM intern_evaluations "
                            + " WHERE intern_lifecycle_id = ? "
                            + "   AND evaluation_type = 'MONTHLY' "
                            + "   AND status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED') "
                            + "   AND (period_start IS NULL OR period_start <= ?) "
                            + "   AND (period_end   IS NULL OR period_end   >= ?)",
                    new Object[]{lifecycleId, periodEnd, periodStart},
                    (rs, n) -> instantOf(rs.getTimestamp(1)))
                    .stream().findFirst().orElse(null);
        } catch (Exception e) {
            log.debug("[ActiveInterns] month eval load failed: {}", e.getMessage());
        }
        String state = publishedAt != null ? "COMPLETED" : "OVERDUE";
        return new EvaluationStateBlock(publishedAt, "MONTHLY", null, state);
    }

    /**
     * Roll up the requested month's weekly timesheets into a single
     * state pill. ALL_APPROVED → "APPROVED"; any REJECTED → "REJECTED";
     * any SUBMITTED pending → "SUBMITTED"; else → "MISSING". Counts
     * carry on {@link TimesheetStateBlock#currentWeekStatus} as a
     * compact "approved/total" string the frontend renders verbatim.
     */
    private TimesheetStateBlock loadMonthTimesheetState(ActiveInternRow basic, YearMonth period) {
        UUID candidateId = resolveCandidateId(basic.internLifecycleId());
        // Single source of truth — same week list the intern view and the
        // ERM rollup use, so counts agree by construction. A week belongs
        // wholly to the month containing its Monday (no partial / no
        // double-counting at the boundary).
        List<com.anvicorp.api.service.timesheet.MonthWeeks.WorkWeek> weeks =
                com.anvicorp.api.service.timesheet.MonthWeeks.workWeeksOf(period);
        int expectedWeeks = weeks.size();
        LocalDate firstMonday = weeks.isEmpty() ? mondayOf(period.atDay(1))
                : weeks.get(0).weekStart();
        if (candidateId == null) {
            return new TimesheetStateBlock(firstMonday, "0/0", null, "MISSING",
                    0, 0, 0, 0, 0, expectedWeeks);
        }
        // Build a Set of in-month Monday dates so the SQL pull can be
        // filtered Java-side without smearing onto adjacent months. We
        // also keep the range filter as a server-side narrower scan so
        // we don't ship every timesheet row across the wire for the
        // intern.
        java.util.Set<LocalDate> mondaysInMonth = new java.util.HashSet<>(weeks.size());
        for (var w : weeks) mondaysInMonth.add(w.weekStart());
        LocalDate scanFrom = firstMonday;
        LocalDate scanToExclusive = weeks.isEmpty()
                ? firstMonday.plusDays(7)
                : weeks.get(weeks.size() - 1).weekStart().plusDays(7);

        int approved = 0, verified = 0, submitted = 0, rejected = 0, draft = 0, total = 0;
        Instant lastApprovedAt = null;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT week_start, status, approved_at FROM timesheets "
                            + " WHERE intern_id = ? "
                            + "   AND week_start >= ? "
                            + "   AND week_start <  ? ",
                    candidateId, scanFrom, scanToExclusive);
            for (Map<String, Object> r : rows) {
                LocalDate weekStart = ((java.sql.Date) r.get("week_start")).toLocalDate();
                // Skip any row whose Monday isn't in THIS month (defensive
                // — the scan range is broad enough that adjacent-month
                // rows could leak in if the scheduler ever wrote one).
                if (!mondaysInMonth.contains(weekStart)) continue;
                total++;
                String st = (String) r.get("status");
                if ("APPROVED".equals(st)) approved++;
                else if ("VERIFIED".equals(st)) verified++;
                else if ("REJECTED".equals(st)) rejected++;
                else if ("SUBMITTED".equals(st)) submitted++;
                else draft++;
                Instant aAt = instantOf((java.sql.Timestamp) r.get("approved_at"));
                if (aAt != null && (lastApprovedAt == null || aAt.isAfter(lastApprovedAt))) {
                    lastApprovedAt = aAt;
                }
            }
        } catch (Exception e) {
            log.debug("[ActiveInterns] month timesheet load failed: {}", e.getMessage());
        }
        // Overall pill keeps the existing semantics so existing readers
        // (Phase A roster, exit checks) aren't disturbed. The new
        // per-status counts power the per-stage chip row on the cell.
        String state;
        if (total == 0) state = "MISSING";
        else if (rejected > 0) state = "REJECTED";
        else if (approved >= expectedWeeks && expectedWeeks > 0) state = "APPROVED";
        else if (submitted + approved + verified > 0) state = "SUBMITTED";
        else state = "MISSING";
        String summary = approved + "/" + Math.max(expectedWeeks, total) + " approved";
        int missing = Math.max(0, expectedWeeks - total);
        return new TimesheetStateBlock(firstMonday, summary, lastApprovedAt, state,
                submitted, verified, approved, rejected, missing, expectedWeeks);
    }

    private MonthRosterSummary computeRosterSummary(List<ActiveInternRow> rows) {
        int totalActive = rows.size();
        int projectsUnassigned = 0, ktNotDone = 0;
        int timesheetsIncomplete = 0, evaluationsOverdue = 0, attention = 0;
        int noManager = 0;
        for (ActiveInternRow r : rows) {
            boolean rowAttention = false;
            CurrentMonthProjectsBlock pr = r.currentMonthProjects();
            if (pr == null || "NO_PROJECTS".equals(pr.overallState())) {
                projectsUnassigned++;
                rowAttention = true;
            } else {
                boolean anyKtMissing =
                        (pr.project1() != null && !"DONE".equalsIgnoreCase(pr.project1().ktStatus()))
                        || (pr.project2() != null && !"DONE".equalsIgnoreCase(pr.project2().ktStatus()));
                if (anyKtMissing) {
                    ktNotDone++;
                    rowAttention = true;
                }
            }
            String tsState = r.timesheet() != null ? r.timesheet().state() : "MISSING";
            if (!"APPROVED".equals(tsState)) {
                timesheetsIncomplete++;
                if ("REJECTED".equals(tsState) || "MISSING".equals(tsState)) {
                    rowAttention = true;
                }
            }
            String evState = r.evaluation() != null ? r.evaluation().state() : "NONE";
            if ("OVERDUE".equals(evState)) {
                evaluationsOverdue++;
                rowAttention = true;
            }
            // Phase C — "no manager" is an attention signal for the ERM
            // roster. Trainer + Manager don't see this counter render
            // (the frontend tile hides at zero anyway).
            if (r.reportingStructure() == null
                    || r.reportingStructure().managerId() == null) {
                noManager++;
                rowAttention = true;
            }
            if (rowAttention) attention++;
        }
        return new MonthRosterSummary(totalActive, projectsUnassigned, ktNotDone,
                timesheetsIncomplete, evaluationsOverdue, attention, noManager);
    }

    private static String projectSlotState(String status, LocalDate dueDate) {
        if (status == null) return "ASSIGNED";
        if (Objects.equals(status, "COMPLETED")
                || Objects.equals(status, "TECH_APPROVED")
                || Objects.equals(status, "PENDING_VIVA")) return "COMPLETED";
        if (dueDate != null && dueDate.isBefore(LocalDate.now())
                && !"SUBMITTED".equals(status)) return "OVERDUE";
        if ("IN_PROGRESS".equals(status) || "RETURNED".equals(status)
                || "SUBMITTED".equals(status)) return "IN_PROGRESS";
        return "ASSIGNED";   // NOT_STARTED + fallback
    }

    private MeetingStateBlock loadMeetingState(UUID lifecycleId) {
        if (lifecycleId == null) return new MeetingStateBlock(null, null, null, "NONE");
        Instant lastAt = null;
        String lastStatus = null;
        Instant nextAt = null;
        try {
            Map<String, Object> r = jdbc.queryForMap(
                    "SELECT "
                            + "  (SELECT scheduled_for FROM weekly_meetings "
                            + "     WHERE intern_lifecycle_id = ? AND scheduled_for <= NOW() "
                            + "     ORDER BY scheduled_for DESC LIMIT 1) AS last_at, "
                            + "  (SELECT status FROM weekly_meetings "
                            + "     WHERE intern_lifecycle_id = ? AND scheduled_for <= NOW() "
                            + "     ORDER BY scheduled_for DESC LIMIT 1) AS last_status, "
                            + "  (SELECT scheduled_for FROM weekly_meetings "
                            + "     WHERE intern_lifecycle_id = ? AND scheduled_for > NOW() "
                            + "       AND status = 'SCHEDULED' "
                            + "     ORDER BY scheduled_for ASC LIMIT 1) AS next_at",
                    lifecycleId, lifecycleId, lifecycleId);
            lastAt = instantOf((java.sql.Timestamp) r.get("last_at"));
            lastStatus = (String) r.get("last_status");
            nextAt = instantOf((java.sql.Timestamp) r.get("next_at"));
        } catch (Exception e) {
            log.debug("[ActiveInterns] meeting state load failed: {}", e.getMessage());
        }
        return new MeetingStateBlock(
                lastAt, lastStatus, nextAt,
                meetingDocState(lastAt, lastStatus, nextAt));
    }

    /** Map DB status → doc-spec'd label (NO_SHOW → MISSED, CANCELLED → RESCHEDULED). */
    private static String meetingDocState(Instant lastAt, String lastStatus, Instant nextAt) {
        if (nextAt != null) return "SCHEDULED";
        if (lastAt == null) return "NONE";
        long daysSince = Duration.between(lastAt, Instant.now()).toDays();
        if ("NO_SHOW".equals(lastStatus)
                || (daysSince > TrainerThresholds.MEETING_MISSING_DAYS
                        && !"COMPLETED".equals(lastStatus))) return "MISSED";
        if ("CANCELLED".equals(lastStatus)) return "RESCHEDULED";
        if ("COMPLETED".equals(lastStatus)) return "COMPLETED";
        return "SCHEDULED";
    }

    private EvaluationStateBlock loadEvaluationState(UUID lifecycleId) {
        if (lifecycleId == null) return new EvaluationStateBlock(null, null, null, "NONE");
        Instant lastPub = null;
        String lastType = null;
        Instant nextScheduled = null;
        try {
            Map<String, Object> r = jdbc.queryForMap(
                    "SELECT "
                            + "  (SELECT MAX(published_at) FROM intern_evaluations "
                            + "     WHERE intern_lifecycle_id = ? "
                            + "       AND evaluation_type = 'MONTHLY' "
                            + "       AND status IN ('PUBLISHED','ACKNOWLEDGED','AMENDED')) AS last_pub, "
                            + "  (SELECT scheduled_for FROM intern_evaluations "
                            + "     WHERE intern_lifecycle_id = ? "
                            + "       AND status IN ('SCHEDULED','IN_PROGRESS','DRAFT') "
                            + "     ORDER BY COALESCE(scheduled_for, created_at) ASC LIMIT 1) AS next_at",
                    lifecycleId, lifecycleId);
            lastPub = instantOf((java.sql.Timestamp) r.get("last_pub"));
            nextScheduled = instantOf((java.sql.Timestamp) r.get("next_at"));
            lastType = lastPub != null ? "MONTHLY" : null;
        } catch (Exception e) {
            log.debug("[ActiveInterns] evaluation state load failed: {}", e.getMessage());
        }
        String state;
        if (nextScheduled != null) state = "SCHEDULED";
        else if (lastPub == null) state = "NONE";
        else {
            long days = Duration.between(lastPub, Instant.now()).toDays();
            state = days > 45 ? "OVERDUE" : "COMPLETED";
        }
        return new EvaluationStateBlock(lastPub, lastType, nextScheduled, state);
    }

    private TimesheetStateBlock loadTimesheetState(ActiveInternRow basic, LocalDate currentWeek) {
        UUID candidateId = resolveCandidateId(basic.internLifecycleId());
        if (candidateId == null) {
            return new TimesheetStateBlock(currentWeek, null, null, "MISSING",
                    0, 0, 0, 0, 0, 0);
        }
        String currentStatus = null;
        Instant lastApprovedAt = null;
        try {
            currentStatus = jdbc.query(
                    "SELECT status FROM timesheets WHERE intern_id = ? AND week_start = ?",
                    new Object[]{candidateId, currentWeek},
                    (rs, n) -> rs.getString(1))
                    .stream().findFirst().orElse(null);
            lastApprovedAt = jdbc.query(
                    "SELECT MAX(approved_at) FROM timesheets "
                            + " WHERE intern_id = ? AND status = 'APPROVED'",
                    new Object[]{candidateId},
                    (rs, n) -> instantOf(rs.getTimestamp(1)))
                    .stream().findFirst().orElse(null);
        } catch (Exception e) {
            log.debug("[ActiveInterns] timesheet state load failed: {}", e.getMessage());
        }
        String state = currentStatus == null ? "MISSING" : currentStatus;
        // Detail-view single-week block — counts are 0; the roster path
        // populates them via loadMonthTimesheetState.
        return new TimesheetStateBlock(currentWeek, currentStatus, lastApprovedAt, state,
                0, 0, 0, 0, 0, 0);
    }

    private UUID resolveCandidateId(UUID lifecycleId) {
        if (lifecycleId == null) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT c.id FROM candidates c "
                            + "  JOIN intern_lifecycles il ON il.user_id = c.user_id "
                            + " WHERE il.id = ?",
                    (rs, n) -> nullableUuid(rs.getString(1)), lifecycleId);
        } catch (Exception e) {
            return null;
        }
    }

    private List<ActiveInternRow> applyStateFilters(
            List<ActiveInternRow> rows,
            List<String> projectFilter,
            List<String> meetingFilter,
            List<String> evaluationFilter,
            List<String> timesheetFilter) {
        return rows.stream()
                .filter(r -> matchesProjectFilter(r, projectFilter))
                .filter(r -> matchesAny(r.weeklyMeeting().state(), meetingFilter))
                .filter(r -> matchesAny(r.evaluation().state(), evaluationFilter))
                .filter(r -> matchesAny(r.timesheet().state(), timesheetFilter))
                .toList();
    }

    private static boolean matchesProjectFilter(ActiveInternRow r, List<String> f) {
        if (f == null || f.isEmpty()) return true;
        String s = r.currentMonthProjects().overallState();
        for (String want : f) {
            if (want == null) continue;
            switch (want.toLowerCase()) {
                case "no_project" -> { if ("NO_PROJECTS".equals(s)) return true; }
                case "in_progress" -> { if ("BOTH_ASSIGNED".equals(s) || "PARTIAL".equals(s)) return true; }
                case "overdue" -> { if ("OVERDUE".equals(s)) return true; }
                case "completed" -> { if ("COMPLETE".equals(s)) return true; }
                default -> {}
            }
        }
        return false;
    }

    private static boolean matchesAny(String value, List<String> filter) {
        if (filter == null || filter.isEmpty()) return true;
        for (String f : filter) {
            if (f != null && f.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    // ── Detail ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ActiveInternDetail getDetail(UUID lifecycleId, User caller) {
        requireTrainer(caller);
        ActiveInternRow basic;
        try {
            basic = jdbc.queryForObject(
                    "SELECT il.id AS lifecycle_id, il.user_id, u.full_name, u.email, "
                            + "       u.phone_number, il.employee_id, il.started_at, il.hired_at, "
                            + "       il.trainer_id, il.evaluator_id, il.manager_id, il.erm_id, "
                            + "       tu.full_name AS trainer_name, eu.full_name AS evaluator_name, "
                            + "       mu.full_name AS manager_name, ru.full_name AS erm_name, "
                            + "       c.id AS candidate_id, c.skillset "
                            + "  FROM intern_lifecycles il "
                            + "  JOIN users u ON u.id = il.user_id "
                            + "  LEFT JOIN candidates c ON c.user_id = il.user_id "
                            + "  LEFT JOIN users tu ON tu.id = il.trainer_id "
                            + "  LEFT JOIN users eu ON eu.id = il.evaluator_id "
                            + "  LEFT JOIN users mu ON mu.id = il.manager_id "
                            + "  LEFT JOIN users ru ON ru.id = il.erm_id "
                            + " WHERE il.id = ? ",
                    new Object[]{lifecycleId},
                    this::mapBasicRow);
        } catch (Exception e) {
            throw new ResourceNotFoundException("Active intern not found or not in scope: "
                    + lifecycleId);
        }

        String currentMonth = currentMonthYear();
        LocalDate currentWeek = mondayOf(LocalDate.now(ZONE));
        ActiveInternRow hydrated = hydrate(basic, currentMonth, currentWeek);

        InternProfile profile = new InternProfile(
                nullableUuid(jdbc.queryForObject(
                        "SELECT user_id FROM intern_lifecycles WHERE id = ?",
                        String.class, lifecycleId)),
                hydrated.fullName(),
                hydrated.email(),
                hydrated.phone(),
                hydrated.employeeId(),
                hydrated.technologyTitle(),
                hydrated.startDate());

        SignedOfferSummary offer = loadSignedOffer(lifecycleId);
        List<RecentProjectRow> recentProjects = loadRecentProjects(lifecycleId);
        List<RecentMeetingRow> recentMeetings = loadRecentMeetings(lifecycleId);
        List<RecentSubmissionRow> recentSubmissions = loadRecentSubmissions(lifecycleId);
        List<RecentTimesheetRow> recentTimesheets = loadRecentTimesheets(profile.userId());
        List<ActivityEntry> recentActivity = loadActivity(profile.userId());
        Boolean i983Required = loadI983Required(profile.userId());
        String i983Badge = Boolean.TRUE.equals(i983Required) ? "I-983 required" : null;

        return new ActiveInternDetail(
                lifecycleId, profile, hydrated, offer,
                recentProjects, recentMeetings, recentSubmissions, recentTimesheets,
                recentActivity, i983Required, i983Badge);
    }

    private SignedOfferSummary loadSignedOffer(UUID lifecycleId) {
        try {
            Map<String, Object> r = jdbc.queryForMap(
                    "SELECT o.role_title, o.start_date AS tentative_start, o.signed_at "
                            + "  FROM intern_lifecycles il "
                            + "  LEFT JOIN candidates c ON c.user_id = il.user_id "
                            + "  LEFT JOIN applications a ON a.candidate_id = c.id "
                            + "  LEFT JOIN offers o ON o.application_id = a.id "
                            + "                     AND o.status = 'SIGNED' "
                            + "                     AND o.archived_at IS NULL "
                            + " WHERE il.id = ? LIMIT 1",
                    lifecycleId);
            return new SignedOfferSummary(
                    (String) r.get("role_title"),
                    "Per ANVI policy",
                    r.get("tentative_start") != null
                            ? ((java.sql.Date) r.get("tentative_start")).toLocalDate() : null,
                    instantOf((java.sql.Timestamp) r.get("signed_at")));
        } catch (Exception e) {
            return new SignedOfferSummary(null, "Per ANVI policy", null, null);
        }
    }

    /**
     * Trainer "Recent projects" feed for an intern's detail page.
     *
     * <p>Source: UNION of (a) the legacy {@code projects.intern_lifecycle_id}
     * lookup and (b) the canonical {@code project_assignments.intern_id}
     * lookup joined back to {@code projects}. The legacy column was the
     * original source-of-truth but doesn't always carry the right value on
     * wizard-assigned rows — the intern's "My Projects" reads exclusively
     * from {@code project_assignments} (which is what made the discrepancy
     * visible: intern sees the project, trainer sees "Nothing yet"). Sourcing
     * both ways here means every project the intern can see, the trainer
     * can see too, with no double-counting (DISTINCT on project id).</p>
     */
    private List<RecentProjectRow> loadRecentProjects(UUID lifecycleId) {
        try {
            return jdbc.query(
                    "SELECT p.id, p.title, p.status, p.project_number, "
                            + "       p.month_year, p.due_date, p.reviewed_at, "
                            + "       p.kt_status, p.kt_completed_at, p.kt_meeting_link, "
                            + "       p.kt_zoom_meeting_id, p.kt_zoom_join_url, "
                            + "       p.kt_zoom_start_url, p.kt_scheduled_for, "
                            + "       p.kt_duration_minutes, p.kt_timezone "
                            + "  FROM projects p "
                            + " WHERE p.intern_lifecycle_id = ? "
                            + "    OR p.id IN ( "
                            + "         SELECT pa.project_id "
                            + "           FROM project_assignments pa "
                            + "          WHERE pa.intern_id = ( "
                            + "                  SELECT il.user_id FROM intern_lifecycles il "
                            + "                   WHERE il.id = ? "
                            + "                ) "
                            + "       ) "
                            + " ORDER BY COALESCE(p.month_year, '0000-00') DESC, "
                            + "          COALESCE(p.project_number, 9) ASC "
                            + " LIMIT 5",
                    new Object[]{lifecycleId, lifecycleId},
                    (rs, n) -> {
                        // pgjdbc on the Railway image returns Integer for
                        // SMALLINT columns (not Short), so `(Short)
                        // rs.getObject(...)` throws ClassCastException at
                        // runtime — was the b160d83 "Recent projects empty"
                        // root cause. getShort()+wasNull() is the robust
                        // version that works against either driver behavior.
                        short pn = rs.getShort("project_number");
                        Short projectNumber = rs.wasNull() ? null : pn;
                        int dur = rs.getInt("kt_duration_minutes");
                        Integer ktDur = rs.wasNull() ? null : dur;
                        return new RecentProjectRow(
                                nullableUuid(rs.getString("id")),
                                rs.getString("title"),
                                rs.getString("status"),
                                projectNumber,
                                rs.getString("month_year"),
                                rs.getDate("due_date") != null
                                        ? rs.getDate("due_date").toLocalDate() : null,
                                instantOf(rs.getTimestamp("reviewed_at")),
                                rs.getString("kt_status"),
                                instantOf(rs.getTimestamp("kt_completed_at")),
                                rs.getString("kt_meeting_link"),
                                rs.getString("kt_zoom_meeting_id"),
                                rs.getString("kt_zoom_join_url"),
                                rs.getString("kt_zoom_start_url"),
                                instantOf(rs.getTimestamp("kt_scheduled_for")),
                                ktDur,
                                rs.getString("kt_timezone"));
                    });
        } catch (Exception e) {
            log.warn("[ActiveInterns] loadRecentProjects failed for lifecycle {}: {}",
                    lifecycleId, e.getMessage());
            return List.of();
        }
    }

    private List<RecentMeetingRow> loadRecentMeetings(UUID lifecycleId) {
        try {
            return jdbc.query(
                    "SELECT id, scheduled_for, status, topic, trainer_notes "
                            + "  FROM weekly_meetings "
                            + " WHERE intern_lifecycle_id = ? "
                            + " ORDER BY scheduled_for DESC LIMIT 5",
                    new Object[]{lifecycleId},
                    (rs, n) -> new RecentMeetingRow(
                            nullableUuid(rs.getString("id")),
                            instantOf(rs.getTimestamp("scheduled_for")),
                            rs.getString("status"),
                            rs.getString("topic"),
                            excerpt(rs.getString("trainer_notes"), 140)));
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<RecentSubmissionRow> loadRecentSubmissions(UUID lifecycleId) {
        try {
            return jdbc.query(
                    "SELECT ps.id, ps.project_id, p.title, ps.submitted_at, "
                            + "       ps.technical_score, ps.communication_score, ps.next_action "
                            + "  FROM project_submissions ps "
                            + "  JOIN projects p ON p.id = ps.project_id "
                            + " WHERE p.intern_lifecycle_id = ? "
                            + " ORDER BY ps.submitted_at DESC LIMIT 5",
                    new Object[]{lifecycleId},
                    (rs, n) -> {
                        // Same pgjdbc SMALLINT→Integer pitfall as
                        // loadRecentProjects — read via getShort()+wasNull()
                        // to be robust against either driver behavior.
                        short tech = rs.getShort("technical_score");
                        Short technicalScore = rs.wasNull() ? null : tech;
                        short comm = rs.getShort("communication_score");
                        Short communicationScore = rs.wasNull() ? null : comm;
                        return new RecentSubmissionRow(
                                nullableUuid(rs.getString("id")),
                                nullableUuid(rs.getString("project_id")),
                                rs.getString("title"),
                                instantOf(rs.getTimestamp("submitted_at")),
                                technicalScore,
                                communicationScore,
                                rs.getString("next_action"));
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<RecentTimesheetRow> loadRecentTimesheets(UUID userId) {
        if (userId == null) return List.of();
        try {
            return jdbc.query(
                    "SELECT t.id, t.week_start, t.status, t.hours "
                            + "  FROM timesheets t "
                            + "  JOIN candidates c ON c.id = t.intern_id "
                            + " WHERE c.user_id = ? "
                            + " ORDER BY t.week_start DESC LIMIT 4",
                    new Object[]{userId},
                    (rs, n) -> new RecentTimesheetRow(
                            nullableUuid(rs.getString("id")),
                            rs.getDate("week_start") != null
                                    ? rs.getDate("week_start").toLocalDate() : null,
                            rs.getString("status"),
                            rs.getBigDecimal("hours") != null
                                    ? rs.getBigDecimal("hours").toPlainString() : "0"));
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ActivityEntry> loadActivity(UUID userId) {
        if (userId == null) return List.of();
        try {
            // Only show Trainer-visible events. Skip immigration / payroll
            // / offer mutation entries per doc §3 RBAC.
            return jdbc.query(
                    "SELECT al.timestamp, al.entity_type, al.entity_id, al.action, "
                            + "       al.user_id AS actor_id, u.full_name AS actor_name "
                            + "  FROM audit_logs al "
                            + "  LEFT JOIN users u ON u.id = al.user_id "
                            + " WHERE al.subject_user_id = ? "
                            + "   AND al.entity_type IN ('Project','ProjectSubmission',"
                            + "                          'WeeklyMeeting','InternEvaluation',"
                            + "                          'Timesheet','ProjectAssignmentEventLog') "
                            + " ORDER BY al.timestamp DESC LIMIT 20",
                    new Object[]{userId},
                    (rs, n) -> new ActivityEntry(
                            instantOf(rs.getTimestamp("timestamp")),
                            rs.getString("entity_type"),
                            nullableUuid(rs.getString("entity_id")),
                            rs.getString("action"),
                            nullableUuid(rs.getString("actor_id")),
                            rs.getString("actor_name")));
        } catch (Exception e) {
            return List.of();
        }
    }

    private Boolean loadI983Required(UUID userId) {
        if (userId == null) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT i983_required FROM work_authorization_records WHERE user_id = ?",
                    Boolean.class, userId);
        } catch (Exception e) {
            return null;
        }
    }

    // ── CSV export (preview of Phase 4 Reports) ─────────────────────────

    @Transactional(readOnly = true)
    public List<List<Object>> exportCsvRows(User caller, String search) {
        ActiveInternListPage all = list(caller, search,
                null, null, null, null, null, null, 0, 1000);
        List<List<Object>> out = new ArrayList<>();
        out.add(List.of(
                "Employee ID", "Full name", "Phone", "Email", "Technology",
                "Start date", "Current month projects",
                "Weekly meeting", "Evaluation", "Timesheet"));
        for (ActiveInternRow r : all.items()) {
            out.add(java.util.Arrays.asList(
                    nz(r.employeeId()), nz(r.fullName()),
                    nz(r.phone()), nz(r.email()),
                    nz(r.technologyTitle()),
                    r.startDate() != null ? r.startDate().toString() : "",
                    r.currentMonthProjects().overallState(),
                    r.weeklyMeeting().state(),
                    r.evaluation().state(),
                    r.timesheet().state()));
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void requireTrainer(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (!caller.getRoles().contains(UserRole.TRAINER)
                && !caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            throw new ForbiddenException("TRAINER or SUPER_ADMIN required");
        }
    }

    /**
     * Phase C — gate the roster by requested scope.
     * <ul>
     *   <li>{@code ALL} — TRAINER, MANAGER, ERM, or SUPER_ADMIN (the
     *       Manager + ERM rosters request {@code ALL} too; ERM's view
     *       is "all interns", so no SQL filter applies. The Manager
     *       roster uses {@code MANAGER_OWNED}.)</li>
     *   <li>{@code MANAGER_OWNED} — MANAGER or SUPER_ADMIN. The SQL
     *       filter on {@code manager_id} is added in {@link #list} so
     *       a Manager can never see another manager's interns even
     *       with a crafted request.</li>
     * </ul>
     */
    private void requireRosterRole(User caller, Scope scope) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (caller.getRoles() == null) {
            throw new ForbiddenException("Caller has no roles");
        }
        boolean superAdmin = caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (superAdmin) return;
        if (scope == Scope.MANAGER_OWNED) {
            if (!caller.getRoles().contains(UserRole.MANAGER)) {
                throw new ForbiddenException("MANAGER or SUPER_ADMIN required");
            }
            return;
        }
        // Scope.ALL — anyone with a roster-eligible role.
        boolean ok = caller.getRoles().contains(UserRole.TRAINER)
                || caller.getRoles().contains(UserRole.MANAGER)
                || caller.getRoles().contains(UserRole.ERM);
        if (!ok) {
            throw new ForbiddenException("TRAINER, MANAGER, ERM or SUPER_ADMIN required");
        }
    }

    private long countOrZero(String sql, Object... params) {
        try {
            Long c = jdbc.queryForObject(sql, Long.class, params);
            return c == null ? 0L : c;
        } catch (Exception e) {
            log.debug("[ActiveInterns] count fallback: {}", e.getMessage());
            return 0L;
        }
    }

    private static String currentMonthYear() {
        return YearMonth.now(ZONE).toString();   // "YYYY-MM"
    }

    private static LocalDate mondayOf(LocalDate today) {
        return today.with(WeekFields.of(Locale.US).dayOfWeek(), 1)
                .with(java.time.DayOfWeek.MONDAY);
    }

    private static UUID nullableUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private static Instant instantOf(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String excerpt(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Suppress the otherwise-unused ChronoUnit import warning on Windows IDE. */
    @SuppressWarnings("unused")
    private static long unused() { return ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.now()); }
}
