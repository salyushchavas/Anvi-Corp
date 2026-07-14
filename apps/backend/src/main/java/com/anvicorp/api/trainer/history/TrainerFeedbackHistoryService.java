package com.anvicorp.api.trainer.history;

import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.ProjectSubmission;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.ProjectSubmissionRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.trainer.TrainerScopeGuard;
import com.anvicorp.api.trainer.history.TrainerFeedbackHistoryDtos.HistoryDetail;
import com.anvicorp.api.trainer.history.TrainerFeedbackHistoryDtos.HistoryPage;
import com.anvicorp.api.trainer.history.TrainerFeedbackHistoryDtos.HistoryRow;
import com.anvicorp.api.trainer.history.TrainerFeedbackHistoryDtos.InternTimeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Trainer Phase 4 — read-only view of all feedback the caller has
 *  already published. Mirrors the Phase 3 listPending query but flips
 *  the {@code trainer_decision IS NULL} predicate to {@code IS NOT NULL}. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrainerFeedbackHistoryService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final long MAX_RANGE_DAYS = 365L;

    private final ProjectSubmissionRepository submissionRepository;
    private final ProjectRepository projectRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;
    private final TrainerScopeGuard trainerScopeGuard;

    @Transactional(readOnly = true)
    public HistoryPage list(UUID internLifecycleId, LocalDate from, LocalDate to,
                             String decision, String search,
                             int page, int pageSize, User caller) {
        requireTrainer(caller);
        if (from != null && to != null) {
            if (from.isAfter(to)) {
                throw new BadRequestException("from must be on or before to");
            }
            if (from.until(to).getDays() > MAX_RANGE_DAYS) {
                throw new BadRequestException(
                        "Date range cannot exceed 365 days");
            }
        }
        if (search != null && search.length() > 200) {
            throw new BadRequestException("search query max 200 chars");
        }
        int p = Math.max(0, page);
        int ps = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));

        StringBuilder where = new StringBuilder(" WHERE s.trainer_decision IS NOT NULL ");
        List<Object> params = new ArrayList<>();
        if (internLifecycleId != null) {
            where.append(" AND p.intern_lifecycle_id = ? ");
            params.add(internLifecycleId);
        }
        // Org-wide trainer model: TRAINER + SUPER_ADMIN both see the
        // shared feedback history across all interns. The previous
        // `il.trainer_id = caller.id OR IS NULL` was a half-step toward
        // the same outcome (it included null-trainer rows); we now make
        // it explicit — no per-intern fence. requireTrainer(caller)
        // above already gates the role.
        if (decision != null && !decision.isBlank()
                && !"ALL".equalsIgnoreCase(decision.trim())) {
            where.append(" AND s.trainer_decision = ? ");
            params.add(decision.trim().toUpperCase());
        }
        if (from != null) {
            where.append(" AND s.reviewed_at >= ? ");
            params.add(java.sql.Timestamp.from(
                    from.atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        if (to != null) {
            where.append(" AND s.reviewed_at < ? ");
            params.add(java.sql.Timestamp.from(
                    to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(p.title) LIKE ? OR LOWER(s.trainer_feedback) LIKE ?) ");
            String q = "%" + search.trim().toLowerCase() + "%";
            params.add(q); params.add(q);
        }
        String base = " FROM project_submissions s "
                + "   JOIN projects p ON p.id = s.project_id "
                + "   JOIN intern_lifecycles il ON il.id = p.intern_lifecycle_id "
                + "   JOIN users u ON u.id = il.user_id "
                + where;
        long total;
        try {
            Long v = jdbc.queryForObject("SELECT COUNT(*) " + base,
                    Long.class, params.toArray());
            total = v == null ? 0L : v;
        } catch (Exception e) {
            log.warn("[TrainerHistory] count failed: {}", e.getMessage());
            total = 0L;
        }
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(ps);
        pageParams.add(p * ps);
        String select = "SELECT s.id AS submission_id, s.project_id, "
                + "       p.intern_lifecycle_id, il.user_id AS intern_user_id, "
                + "       u.full_name AS intern_name, il.employee_id, "
                + "       p.title AS project_title, p.tech_stack AS technology_area, "
                + "       p.month_year, p.project_number, s.version, "
                + "       s.trainer_decision, s.completion_status, "
                + "       s.technical_score, s.communication_score, "
                + "       s.submitted_at, s.reviewed_at "
                + base + " ORDER BY s.reviewed_at DESC NULLS LAST LIMIT ? OFFSET ?";
        List<HistoryRow> rows = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(select, pageParams.toArray())) {
                rows.add(mapRow(r));
            }
        } catch (Exception e) {
            log.warn("[TrainerHistory] page query failed: {}", e.getMessage());
        }
        int totalPages = ps == 0 ? 0 : (int) Math.ceil((double) total / ps);
        return new HistoryPage(rows, p, ps, total, totalPages);
    }

    @Transactional(readOnly = true)
    public HistoryDetail getDetail(UUID submissionId, User caller) {
        requireTrainer(caller);
        ProjectSubmission s = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Submission not found: " + submissionId));
        Project p = projectRepository.findById(s.getProject().getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project not found"));
        InternLifecycle lc = p.getInternLifecycleId() != null
                ? lifecycleRepository.findById(p.getInternLifecycleId()).orElse(null)
                : null;
        requireProjectScope(lc, caller);
        if (s.getTrainerDecision() == null) {
            throw new ResourceNotFoundException(
                    "Submission has no recorded decision yet (still in pending queue)");
        }
        User intern = lc != null && lc.getUserId() != null
                ? userRepository.findById(lc.getUserId()).orElse(null) : null;
        User reviewer = s.getReviewedById() != null
                ? userRepository.findById(s.getReviewedById()).orElse(null) : null;
        return new HistoryDetail(
                s.getId(), p.getId(),
                p.getInternLifecycleId(),
                lc != null ? lc.getUserId() : null,
                intern != null ? intern.getFullName() : null,
                lc != null ? lc.getEmployeeId() : null,
                p.getTitle(), p.getTechStack(), p.getInstructions(),
                p.getMonthYear(), p.getProjectNumber(),
                s.getVersion() == null ? 1 : s.getVersion(),
                s.getDescription(), s.getLinksJson(), s.getSubmittedAt(),
                s.getTrainerDecision(), s.getCompletionStatus(),
                s.getTechnicalScore(), s.getCommunicationScore(),
                s.getTrainerFeedback(), s.getBlockersNote(),
                s.getNextAction(), s.getNextActionDueDate(),
                s.getReviewedLinksCsv(),
                s.getReviewedAt(), s.getReviewedById(),
                reviewer != null ? reviewer.getFullName() : null,
                p.getStatus() != null ? p.getStatus().name() : null,
                p.getDueDate(), p.getCompletedAt());
    }

    @Transactional(readOnly = true)
    public InternTimeline getInternTimeline(UUID internLifecycleId, User caller) {
        requireTrainer(caller);
        InternLifecycle lc = lifecycleRepository.findById(internLifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + internLifecycleId));
        requireProjectScope(lc, caller);
        User intern = lc.getUserId() != null
                ? userRepository.findById(lc.getUserId()).orElse(null) : null;
        // Load entire history for this intern (newest first), bounded
        // by pagination but realistically a couple dozen rows max.
        HistoryPage page = list(internLifecycleId, null, null, null, null,
                0, MAX_PAGE_SIZE, caller);
        int accepted = 0, revision = 0, escalated = 0, none = 0;
        int techCount = 0; int commCount = 0;
        double techSum = 0; double commSum = 0;
        Instant latest = null;
        for (HistoryRow r : page.items()) {
            switch (r.trainerDecision() == null ? "" : r.trainerDecision()) {
                case "ACCEPT" -> accepted++;
                case "REQUEST_REVISION" -> revision++;
                case "ESCALATE" -> escalated++;
                case "NO_ACTION_YET" -> none++;
                default -> {}
            }
            if (r.technicalScore() != null) {
                techCount++; techSum += r.technicalScore();
            }
            if (r.communicationScore() != null) {
                commCount++; commSum += r.communicationScore();
            }
            if (r.reviewedAt() != null
                    && (latest == null || r.reviewedAt().isAfter(latest))) {
                latest = r.reviewedAt();
            }
        }
        return new InternTimeline(
                lc.getId(), lc.getUserId(),
                intern != null ? intern.getFullName() : null,
                lc.getEmployeeId(),
                page.items().size(),
                accepted, revision, escalated, none,
                techCount > 0 ? techSum / techCount : null,
                commCount > 0 ? commSum / commCount : null,
                latest,
                page.items());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void requireTrainer(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (!caller.getRoles().contains(UserRole.TRAINER)
                && !caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            throw new ForbiddenException("TRAINER or SUPER_ADMIN required");
        }
    }

    private void requireProjectScope(InternLifecycle lc, User caller) {
        // Delegate to TrainerScopeGuard so feedback-history detail/timeline
        // inherits the single-trainer null-fallback used everywhere else.
        trainerScopeGuard.requireTrainerOwnership(lc, caller);
    }

    private HistoryRow mapRow(Map<String, Object> r) {
        return new HistoryRow(
                uuid(r.get("submission_id")),
                uuid(r.get("project_id")),
                uuid(r.get("intern_lifecycle_id")),
                uuid(r.get("intern_user_id")),
                (String) r.get("intern_name"),
                (String) r.get("employee_id"),
                (String) r.get("project_title"),
                (String) r.get("technology_area"),
                (String) r.get("month_year"),
                shortVal(r.get("project_number")),
                intVal(r.get("version")),
                (String) r.get("trainer_decision"),
                (String) r.get("completion_status"),
                shortVal(r.get("technical_score")),
                shortVal(r.get("communication_score")),
                toInstant(r.get("submitted_at")),
                toInstant(r.get("reviewed_at")));
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(String.valueOf(o)); }
        catch (Exception e) { return null; }
    }

    private static int intVal(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); }
        catch (Exception e) { return 0; }
    }

    private static Short shortVal(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.shortValue();
        return null;
    }

    private static Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof java.sql.Timestamp t) return t.toInstant();
        return null;
    }
}
