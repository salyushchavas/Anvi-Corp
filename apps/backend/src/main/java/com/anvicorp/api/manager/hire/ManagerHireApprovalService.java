package com.anvicorp.api.manager.hire;

import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.entity.Resume;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.InterviewStatus;
import com.anvicorp.api.event.ManagerHireDecisionEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.InterviewRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backs the Manager "Hire Approvals" surface — the org-wide gate that
 * was extracted from the ERM interview-complete flow. ERM submits the
 * scorecard ({@code Interview.managerHireDecision = PENDING}); any
 * Manager can open this queue, review the scorecard + ERM's
 * recommendation, and approve / reject.
 *
 * <p>Approval flips {@code managerHireDecision = APPROVED} AND
 * {@code Interview.decision = SELECTED} so {@link
 * com.anvicorp.api.erm.offer.SelectionAckPolicy#isSelected} starts
 * returning true — the existing selection-ack card + offer-send chain
 * then proceeds unchanged. Rejection flips
 * {@code managerHireDecision = REJECTED}, sets
 * {@code Interview.decision = REJECTED} (for downstream listeners that
 * still key off it), and walks the application to REJECTED.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerHireApprovalService {

    private final JdbcTemplate jdbc;
    private final InterviewRepository interviewRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public ManagerHireApprovalDtos.HireApprovalListPage list(
            String search, int page, int pageSize) {
        int p = Math.max(0, page);
        int ps = Math.min(100, Math.max(1, pageSize));
        // Include HOLD rows in the queue so the manager can revisit them.
        // HOLD is a pause, not a final decision — the row stays visible
        // until APPROVED or REJECTED.
        StringBuilder where = new StringBuilder(
                " WHERE iv.status = 'COMPLETED' "
                        + "   AND iv.manager_hire_decision IN ('PENDING','HOLD') ");
        List<Object> params = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            where.append(" AND LOWER(u.full_name) LIKE ? ");
            params.add("%" + search.trim().toLowerCase() + "%");
        }
        long total = countOrZero(
                "SELECT COUNT(*) FROM interviews iv "
                        + "  JOIN applications a ON a.id = iv.application_id "
                        + "  JOIN candidates c ON c.id = a.candidate_id "
                        + "  JOIN users u ON u.id = c.user_id "
                        + "  LEFT JOIN job_postings jp ON jp.id = a.job_posting_id "
                        + where,
                params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(ps);
        pageParams.add(p * ps);
        List<ManagerHireApprovalDtos.HireApprovalRow> rows = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT iv.id AS interview_id, a.id AS application_id, "
                            + "       u.id AS user_id, u.full_name, u.email, "
                            + "       jp.title AS job_title, c.skillset, "
                            + "       iv.feedback_submitted_at, "
                            + "       iv.technical_score, iv.communication_score, "
                            + "       iv.cultural_fit_score, iv.overall_recommendation, "
                            + "       iv.manager_hire_decision "
                            + "  FROM interviews iv "
                            + "  JOIN applications a ON a.id = iv.application_id "
                            + "  JOIN candidates c ON c.id = a.candidate_id "
                            + "  JOIN users u ON u.id = c.user_id "
                            + "  LEFT JOIN job_postings jp ON jp.id = a.job_posting_id "
                            + where
                            + " ORDER BY iv.feedback_submitted_at ASC NULLS LAST "
                            + " LIMIT ? OFFSET ?",
                    pageParams.toArray())) {
                Instant submittedAt = instantOf((java.sql.Timestamp) r.get("feedback_submitted_at"));
                long hoursWaiting = submittedAt == null ? 0
                        : Duration.between(submittedAt, Instant.now()).toHours();
                rows.add(new ManagerHireApprovalDtos.HireApprovalRow(
                        uuid(r.get("interview_id")),
                        uuid(r.get("application_id")),
                        uuid(r.get("user_id")),
                        (String) r.get("full_name"),
                        (String) r.get("email"),
                        (String) r.get("job_title"),
                        (String) r.get("skillset"),
                        submittedAt,
                        hoursWaiting,
                        intVal(r.get("technical_score")),
                        intVal(r.get("communication_score")),
                        intVal(r.get("cultural_fit_score")),
                        (String) r.get("overall_recommendation"),
                        (String) r.get("manager_hire_decision")));
            }
        } catch (Exception e) {
            log.warn("[ManagerHireApproval] queue query failed: {}", e.getMessage());
        }
        int totalPages = ps == 0 ? 0 : (int) Math.ceil((double) total / ps);
        return new ManagerHireApprovalDtos.HireApprovalListPage(
                rows, p, ps, total, totalPages);
    }

    @Transactional(readOnly = true)
    public ManagerHireApprovalDtos.HireApprovalDetail getDetail(UUID interviewId) {
        Interview iv = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Interview not found: " + interviewId));
        if (iv.getStatus() != InterviewStatus.COMPLETED) {
            throw new ConflictException(
                    "Interview is not COMPLETED (current: " + iv.getStatus() + ")");
        }
        Application app = iv.getApplication();
        String candidateName = app != null && app.getCandidate() != null
                && app.getCandidate().getUser() != null
                ? app.getCandidate().getUser().getFullName() : null;
        String candidateEmail = app != null && app.getCandidate() != null
                && app.getCandidate().getUser() != null
                ? app.getCandidate().getUser().getEmail() : null;
        UUID candidateUserId = app != null && app.getCandidate() != null
                && app.getCandidate().getUser() != null
                ? app.getCandidate().getUser().getId() : null;
        String jobTitle = app != null && app.getJobPosting() != null
                ? app.getJobPosting().getTitle() : null;
        String technology = app != null && app.getCandidate() != null
                ? app.getCandidate().getSkillset() : null;
        String scorecardSubmittedByName = null;
        if (iv.getFeedbackSubmittedBy() != null) {
            scorecardSubmittedByName = userRepository.findById(iv.getFeedbackSubmittedBy())
                    .map(User::getFullName).orElse(null);
        }
        // Resume on the application (null when the candidate applied
        // without one — Application.resume is optional). The downloadUrl
        // points at the shared resume endpoint; ResumeController gates
        // MANAGER + the row-level "linked to any application I can see"
        // check covers the access rule.
        ManagerHireApprovalDtos.ResumeView resume = null;
        if (app != null && app.getResume() != null) {
            Resume r = app.getResume();
            resume = new ManagerHireApprovalDtos.ResumeView(
                    r.getId(),
                    r.getFileName(),
                    r.getFileSize(),
                    r.getContentType(),
                    "/api/v1/resumes/" + r.getId() + "/download");
        }
        return new ManagerHireApprovalDtos.HireApprovalDetail(
                iv.getId(),
                app != null ? app.getId() : null,
                candidateUserId,
                candidateName,
                candidateEmail,
                jobTitle,
                technology,
                iv.getScheduledAt(),
                iv.getFeedbackSubmittedAt(),
                scorecardSubmittedByName,
                iv.getTechnicalScore(),
                iv.getCommunicationScore(),
                iv.getCulturalFitScore(),
                iv.getOverallRecommendation(),
                iv.getInternalNotes(),
                iv.getApplicantVisibleNotes(),
                iv.getManagerHireDecision(),
                iv.getManagerHireDecisionAt(),
                iv.getManagerHireDecisionNote(),
                null,
                resume);
    }

    @Transactional
    public ManagerHireApprovalDtos.HireApprovalDetail approve(
            UUID interviewId, String note, User caller) {
        return decide(interviewId, "APPROVED", note, caller);
    }

    @Transactional
    public ManagerHireApprovalDtos.HireApprovalDetail reject(
            UUID interviewId, String note, User caller) {
        return decide(interviewId, "REJECTED", note, caller);
    }

    /**
     * Park the hire decision without advancing the lifecycle. HOLD is a
     * pause, not a final outcome — the row stays in the manager queue
     * (via the PENDING/HOLD filter) and can later transition to
     * APPROVED or REJECTED. Unlike {@link #decide}, this:
     * <ul>
     *   <li>does NOT touch {@code Interview.decision} (no SELECTED/REJECTED
     *       echo, no offer-send unlock);</li>
     *   <li>does NOT change {@code Application.status} (the candidate
     *       stays in their current pipeline state);</li>
     *   <li>does NOT publish {@link ManagerHireDecisionEvent} (no offer
     *       letter, no rejection email);</li>
     *   <li>is idempotent — toggling HOLD while already on HOLD just
     *       refreshes the timestamp/note.</li>
     * </ul>
     */
    @Transactional
    public ManagerHireApprovalDtos.HireApprovalDetail hold(
            UUID interviewId, String note, User caller) {
        if (caller == null) throw new BadRequestException("caller required");
        Interview iv = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Interview not found: " + interviewId));
        if (iv.getStatus() != InterviewStatus.COMPLETED) {
            throw new ConflictException(
                    "Interview must be COMPLETED before a manager decision "
                            + "(current: " + iv.getStatus() + ")");
        }
        String prev = iv.getManagerHireDecision();
        if ("APPROVED".equalsIgnoreCase(prev) || "REJECTED".equalsIgnoreCase(prev)) {
            throw new ConflictException(
                    "Hire decision already recorded: " + prev
                            + " — HOLD is only valid from PENDING/HOLD.");
        }
        iv.setManagerHireDecision("HOLD");
        iv.setManagerHireDecisionAt(Instant.now());
        iv.setManagerHireDecisionById(caller.getId());
        if (note != null && !note.isBlank()) {
            iv.setManagerHireDecisionNote(note.trim());
        }
        interviewRepository.save(iv);
        return getDetail(interviewId);
    }

    private ManagerHireApprovalDtos.HireApprovalDetail decide(
            UUID interviewId, String decision, String note, User caller) {
        if (caller == null) throw new BadRequestException("caller required");
        Interview iv = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Interview not found: " + interviewId));
        if (iv.getStatus() != InterviewStatus.COMPLETED) {
            throw new ConflictException(
                    "Interview must be COMPLETED before a manager decision "
                            + "(current: " + iv.getStatus() + ")");
        }
        String prev = iv.getManagerHireDecision();
        // Only block when a FINAL decision (APPROVED/REJECTED) is already
        // recorded. PENDING and HOLD are non-final and can transition.
        if ("APPROVED".equalsIgnoreCase(prev) || "REJECTED".equalsIgnoreCase(prev)) {
            throw new ConflictException(
                    "Hire decision already recorded: " + prev);
        }
        iv.setManagerHireDecision(decision);
        iv.setManagerHireDecisionAt(Instant.now());
        iv.setManagerHireDecisionById(caller.getId());
        if (note != null && !note.isBlank()) {
            iv.setManagerHireDecisionNote(note.trim());
        }
        // Also flip Interview.decision so downstream listeners + the
        // ERM interview detail UI (which reads iv.decision) reflect
        // the outcome. The SelectionAckPolicy gate already pivoted to
        // managerHireDecision; setting decision is a back-compat
        // convenience for the email listener + the interview-detail
        // chip on the ERM page.
        iv.setDecision("APPROVED".equals(decision) ? "SELECTED" : "REJECTED");
        interviewRepository.save(iv);

        Application app = iv.getApplication();
        if (app != null && "REJECTED".equals(decision)) {
            app.setStatus(ApplicationStatus.REJECTED);
            app.setStatusUpdatedBy(caller.getId());
            applicationRepository.save(app);
        }

        try {
            eventPublisher.publishEvent(new ManagerHireDecisionEvent(
                    iv.getId(),
                    app != null ? app.getId() : null,
                    app != null && app.getCandidate() != null
                            && app.getCandidate().getUser() != null
                            ? app.getCandidate().getUser().getId() : null,
                    app != null && app.getCandidate() != null
                            && app.getCandidate().getUser() != null
                            ? app.getCandidate().getUser().getEmail() : null,
                    decision,
                    caller.getId(),
                    iv.getFeedbackSubmittedBy(),
                    Instant.now()));
        } catch (Exception e) {
            log.warn("[ManagerHireApproval] event publish failed (non-fatal): {}",
                    e.getMessage());
        }
        return getDetail(interviewId);
    }

    private long countOrZero(String sql, Object... params) {
        try {
            Long c = jdbc.queryForObject(sql, Long.class, params);
            return c == null ? 0L : c;
        } catch (Exception e) {
            log.debug("[ManagerHireApproval] count fallback: {}", e.getMessage());
            return 0L;
        }
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        try { return UUID.fromString(o.toString()); } catch (Exception e) { return null; }
    }

    private static Integer intVal(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.valueOf(o.toString()); } catch (Exception e) { return null; }
    }

    private static Instant instantOf(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
