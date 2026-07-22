package com.anvicorp.api.erm.interview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.application.ApplicationLifecycle;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.entity.InterviewEventLog;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.InterviewStatus;
import com.anvicorp.api.enums.InterviewType;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.ReasonCode;
import com.anvicorp.api.event.InterviewCancelledEvent;
import com.anvicorp.api.event.InterviewCompletedEvent;
import com.anvicorp.api.event.InterviewRescheduledEvent;
import com.anvicorp.api.event.InterviewScheduledEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.meeting.MeetingProvider;
import com.anvicorp.api.integration.meeting.MeetingRequest;
import com.anvicorp.api.integration.meeting.MeetingResponse;
import com.anvicorp.api.intern.InternLifecycleService;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.InterviewEventLogRepository;
import com.anvicorp.api.repository.InterviewRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ERM Phase 3 — interview scheduler + decision center.
 *
 * <p>Doc §6: create from SHORTLISTED applications via the existing
 * {@link ZoomService}, reschedule with required {@link ReasonCode} + audit,
 * change interviewer with Zoom recreation, complete with rubric scores +
 * decision + applicant-safe notes, cancel with required reason.
 * Cancel is the ONLY operation that reverses
 * {@code users.lifecycle_status} — INTERVIEW_SCHEDULED → SHORTLISTED. Per
 * doc §6 the reversal is the documented behaviour; logged at INFO with
 * reason so the audit trail captures the regression.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmInterviewService {

    /**
     * Advisory only — surfaced verbatim on the manager hire-approval
     * screen + ERM decision center as interviewer guidance. The binding
     * hire decision is the separate {@code manager_hire_decision} field
     * with its own Hire/Reject/Hold tri-state; nothing here branches on
     * {@code overallRecommendation}. The legacy four-bucket set
     * (STRONG_HIRE / HIRE / NO_HIRE / STRONG_NO_HIRE) was collapsed to
     * mirror the binding decision; legacy rows are remapped one-shot
     * by SchemaFixupRunner so the validator stays strict.
     */
    private static final Set<String> VALID_RECOMMENDATIONS = Set.of(
            "HIRE", "REJECT", "HOLD");

    private static final int APPLICANT_NOTES_MIN = 20;
    private static final int INTERNAL_NOTES_MAX = 5000;
    private static final int REASON_TEXT_MIN = 10;
    private static final int DURATION_MIN = 15;
    private static final int DURATION_MAX = 180;
    private static final int CALENDAR_RANGE_MAX_DAYS = 90;

    private final InterviewRepository interviewRepository;
    private final ApplicationRepository applicationRepository;
    private final InterviewEventLogRepository eventLogRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final InternLifecycleService internLifecycleService;
    private final MeetingProvider meetingProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final com.anvicorp.api.config.BrandConfig brand;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    /**
     * Per-request Zoom outcome — propagated to the response DTO so the UI
     * can render "Zoom not configured" vs "Zoom call failed: …" vs OK.
     * {@code null} means "interview created without a Zoom attempt being
     * relevant to this response" (e.g. read-only fetches).
     */
    private final ThreadLocal<ZoomCreateOutcome> lastZoomOutcome = new ThreadLocal<>();

    private static final class ZoomCreateOutcome {
        final ErmInterviewDtos.ZoomStatus status;
        final String errorMessage;
        ZoomCreateOutcome(ErmInterviewDtos.ZoomStatus s, String err) {
            this.status = s; this.errorMessage = err;
        }
    }

    // ── List + calendar ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ErmInterviewDtos.ErmInterviewListPage list(
            String statusFilter, UUID interviewerId, String search,
            String scope, User caller, int page, int pageSize) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page), Math.min(Math.max(1, pageSize), 100));
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (statusFilter != null && !statusFilter.isBlank()) {
            where.append(" AND i.status = ?");
            params.add(statusFilter.toUpperCase());
        }
        if (interviewerId != null) {
            where.append(" AND i.interviewer_id = ?");
            params.add(interviewerId);
        }
        if (search != null && !search.isBlank()) {
            String q = "%" + search.trim().toLowerCase() + "%";
            if (q.length() > 102) q = q.substring(0, 102);
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ?)");
            params.add(q); params.add(q);
        }
        if ("mine".equalsIgnoreCase(scope)) {
            where.append(" AND (a.erm_owner_id IS NULL OR a.erm_owner_id = ? OR i.interviewer_id = ? OR i.created_by = ?)");
            params.add(caller.getId()); params.add(caller.getId()); params.add(caller.getId());
        } else if ("unassigned".equalsIgnoreCase(scope)) {
            where.append(" AND a.erm_owner_id IS NULL");
        }
        String base = "FROM interviews i "
                + "JOIN applications a ON a.id = i.application_id "
                + "JOIN candidates c ON c.id = a.candidate_id "
                + "JOIN users u ON u.id = c.user_id "
                + "JOIN job_postings jp ON jp.id = a.job_posting_id "
                + "LEFT JOIN users iv ON iv.id = i.interviewer_id ";
        long total;
        try {
            Long v = jdbc.queryForObject("SELECT COUNT(*) " + base + where,
                    Long.class, params.toArray());
            total = v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ErmInterviews] count failed (non-fatal): {}", e.getMessage());
            total = 0L;
        }
        String select = "SELECT i.id, i.application_id, i.scheduled_at, i.duration_minutes, "
                + "i.timezone, i.status, i.decision, i.interviewer_id, i.reschedule_count, "
                + "u.full_name AS applicant_name, u.applicant_id AS applicant_id, "
                + "jp.title AS job_title, jp.job_type, "
                + "iv.full_name AS interviewer_name "
                + base + where
                + " ORDER BY i.scheduled_at ASC "
                + " LIMIT " + pageable.getPageSize()
                + " OFFSET " + (pageable.getPageNumber() * pageable.getPageSize());
        List<ErmInterviewDtos.ErmInterviewRow> rows = new ArrayList<>();
        try {
            rows = jdbc.query(select, params.toArray(), (rs, n) ->
                    new ErmInterviewDtos.ErmInterviewRow(
                            UUID.fromString(rs.getString("id")),
                            UUID.fromString(rs.getString("application_id")),
                            rs.getString("applicant_name"),
                            rs.getString("applicant_id"),
                            rs.getString("job_title"),
                            rs.getString("job_type"),
                            rs.getTimestamp("scheduled_at").toInstant(),
                            (Integer) rs.getObject("duration_minutes"),
                            rs.getString("timezone"),
                            InterviewStatus.valueOf(rs.getString("status")),
                            rs.getString("decision"),
                            nullableUuid(rs.getString("interviewer_id")),
                            rs.getString("interviewer_name"),
                            rs.getInt("reschedule_count")));
        } catch (Exception e) {
            log.warn("[ErmInterviews] list query failed (non-fatal): {}", e.getMessage());
        }
        Page<ErmInterviewDtos.ErmInterviewRow> p = new PageImpl<>(rows, pageable, total);
        return new ErmInterviewDtos.ErmInterviewListPage(p.getContent(), p.getNumber(),
                p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    @Transactional(readOnly = true)
    public List<ErmInterviewDtos.CalendarEntry> calendar(Instant from, Instant to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new BadRequestException("from + to are required (to > from)");
        }
        if (Duration.between(from, to).toDays() > CALENDAR_RANGE_MAX_DAYS) {
            throw new BadRequestException("Calendar range max " + CALENDAR_RANGE_MAX_DAYS + " days");
        }
        String sql = "SELECT i.id, i.application_id, i.scheduled_at, i.duration_minutes, "
                + "i.timezone, i.status, i.decision, "
                + "u.full_name AS applicant_name, jp.title AS job_title, "
                + "iv.full_name AS interviewer_name "
                + "FROM interviews i "
                + "JOIN applications a ON a.id = i.application_id "
                + "JOIN candidates c ON c.id = a.candidate_id "
                + "JOIN users u ON u.id = c.user_id "
                + "JOIN job_postings jp ON jp.id = a.job_posting_id "
                + "LEFT JOIN users iv ON iv.id = i.interviewer_id "
                + "WHERE i.scheduled_at >= ? AND i.scheduled_at < ? "
                + "ORDER BY i.scheduled_at ASC";
        try {
            return jdbc.query(sql,
                    new Object[]{java.sql.Timestamp.from(from), java.sql.Timestamp.from(to)},
                    (rs, n) -> new ErmInterviewDtos.CalendarEntry(
                            UUID.fromString(rs.getString("id")),
                            UUID.fromString(rs.getString("application_id")),
                            rs.getString("applicant_name"),
                            rs.getString("job_title"),
                            rs.getTimestamp("scheduled_at").toInstant(),
                            (Integer) rs.getObject("duration_minutes"),
                            rs.getString("timezone"),
                            InterviewStatus.valueOf(rs.getString("status")),
                            rs.getString("decision"),
                            rs.getString("interviewer_name")));
        } catch (Exception e) {
            log.warn("[ErmInterviews] calendar query failed (non-fatal): {}", e.getMessage());
            return List.of();
        }
    }

    // ── Detail ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ErmInterviewDtos.ErmInterviewDetail getDetail(UUID interviewId, User caller) {
        Interview iv = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Interview not found: " + interviewId));
        return toDetail(iv, caller);
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Transactional
    public ErmInterviewDtos.ErmInterviewDetail create(
            ErmInterviewDtos.ErmCreateInterviewRequest req, User caller) {
        if (req == null || req.applicationId() == null) {
            throw new BadRequestException("applicationId is required");
        }
        if (req.scheduledFor() == null || req.scheduledFor().isBefore(Instant.now())) {
            throw new BadRequestException("scheduledFor must be in the future");
        }
        int duration = req.durationMinutes() != null ? req.durationMinutes() : 60;
        if (duration < DURATION_MIN || duration > DURATION_MAX) {
            throw new BadRequestException(
                    "durationMinutes must be " + DURATION_MIN + "-" + DURATION_MAX);
        }
        Application app = applicationRepository.findById(req.applicationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + req.applicationId()));
        if (app.getStatus() != ApplicationStatus.SHORTLISTED) {
            throw new ConflictException(
                    "Application must be SHORTLISTED (current: " + app.getStatus() + ")");
        }
        // Existing active interview check.
        long activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interviews WHERE application_id = ? "
                        + " AND status IN ('SCHEDULED','COMPLETED')",
                Long.class, app.getId());
        if (activeCount > 0) {
            throw new ConflictException(
                    "Application already has an active or completed interview; cancel it first.");
        }
        // Phase 8.5 — the ERM scheduling the interview is the interviewer.
        // Any interviewerId in the request body is ignored (kept on the DTO
        // for backward compat with older clients).
        User interviewer = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Caller not found: " + caller.getId()));
        validateInterviewer(interviewer);
        // Phase 1.7 — timezone is now required at the API. Defaulting silently
        // to UTC let typos / blank inputs through (e.g. ERM Asia ⇄ US confusion
        // bug). The frontend selector defaults the input to the ERM's browser
        // zone; a hand-crafted request must supply a valid IANA id.
        String tz = req.timezone() != null ? req.timezone().trim() : "";
        if (tz.isBlank()) {
            throw new BadRequestException(
                    "timezone is required (IANA id, e.g. America/New_York). "
                            + "Pick one in the scheduling form.");
        }
        try {
            java.time.ZoneId.of(tz);
        } catch (Exception e) {
            throw new BadRequestException(
                    "timezone is not a recognized IANA id: '" + tz + "'.");
        }

        // Build + persist the Interview row FIRST so a Zoom failure never
        // loses the schedule. Then attach a Zoom meeting in a best-effort
        // pass, recording the outcome on the per-request ThreadLocal so the
        // response DTO can render a clear status to the ERM.
        InterviewType type = req.type() != null ? req.type() : InterviewType.TECHNICAL;
        Interview iv = Interview.builder()
                .application(app)
                .interviewer(interviewer)
                .scheduledAt(req.scheduledFor())
                .durationMinutes(duration)
                .timezone(tz)
                .type(type)
                .status(InterviewStatus.SCHEDULED)
                .prepInstructions(req.prepInstructions())
                .panelInterviewerIdsJson(serializePanel(req.panelInterviewerIds()))
                .rescheduleCount(0)
                .createdBy(caller.getId())
                .build();

        if (req.manualZoomLink() != null && !req.manualZoomLink().isBlank()) {
            // ERM-supplied link bypasses the Zoom API entirely.
            iv.setZoomJoinUrl(req.manualZoomLink().trim());
            lastZoomOutcome.set(new ZoomCreateOutcome(
                    ErmInterviewDtos.ZoomStatus.MANUAL_LINK, null));
        } else {
            attachZoomMeeting(iv, interviewer);
        }
        iv = interviewRepository.save(iv);

        // Application status advance + lifecycle advance.
        try {
            app.setStatus(ApplicationStatus.INTERVIEW_SCHEDULED);
            app.setStatusUpdatedBy(caller.getId());
            applicationRepository.save(app);
        } catch (Exception e) {
            log.warn("[ErmInterviews] application status advance failed: {}", e.getMessage());
        }
        if (app.getCandidate() != null && app.getCandidate().getUser() != null) {
            internLifecycleService.advance(app.getCandidate().getUser(),
                    InternLifecycleStatus.INTERVIEW_SCHEDULED, caller.getId());
        }

        // Event log + audit.
        writeEventLog(iv.getId(), caller.getId(), "CREATED", null, null,
                Map.of("scheduledFor", iv.getScheduledAt().toString(),
                        "interviewerId", interviewer.getId().toString(),
                        "timezone", tz));
        writeAudit(caller.getId(), applicantUserId(app),
                "INTERVIEW_CREATED", "Interview", iv.getId(),
                null, Map.of("scheduledFor", iv.getScheduledAt().toString(),
                        "interviewerId", interviewer.getId().toString()));

        // Fan-out.
        try {
            eventPublisher.publishEvent(new InterviewScheduledEvent(
                    iv.getId(), app.getId(),
                    applicantUserId(app), interviewer.getId(), caller.getId()));
        } catch (Exception e) {
            log.warn("[ErmInterviews] event publish failed: {}", e.getMessage());
        }
        return toDetail(iv, caller);
    }

    // ── Reschedule ────────────────────────────────────────────────────────

    @Transactional
    public ErmInterviewDtos.ErmInterviewDetail reschedule(
            UUID interviewId, ErmInterviewDtos.ErmRescheduleRequest req, User caller) {
        if (req == null) throw new BadRequestException("body required");
        if (req.scheduledFor() == null) {
            throw new BadRequestException("scheduledFor is required");
        }
        if (req.scheduledFor().isBefore(Instant.now())) {
            throw new BadRequestException("scheduledFor must be in the future");
        }
        Interview iv = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Interview not found: " + interviewId));
        if (iv.getStatus() != InterviewStatus.SCHEDULED) {
            throw new ConflictException(
                    "Reschedule only allowed from SCHEDULED (current: " + iv.getStatus() + ")");
        }
        ReasonCode rc = requireReason(req.reasonCode(), req.reasonText(),
                ReasonCode.Category.INTERVIEW_RESCHEDULE);
        int newDur = req.durationMinutes() != null ? req.durationMinutes() : iv.getDurationMinutes();
        if (newDur < DURATION_MIN || newDur > DURATION_MAX) {
            throw new BadRequestException(
                    "durationMinutes must be " + DURATION_MIN + "-" + DURATION_MAX);
        }
        Instant previous = iv.getScheduledAt();
        Integer previousDuration = iv.getDurationMinutes();

        // Phase 1.7 — optional new timezone on reschedule. Omitted preserves
        // the interview's existing zone; supplied must parse as a valid IANA
        // id so we don't write a typo.
        if (req.timezone() != null && !req.timezone().isBlank()) {
            String newTz = req.timezone().trim();
            try {
                java.time.ZoneId.of(newTz);
            } catch (Exception e) {
                throw new BadRequestException(
                        "timezone is not a recognized IANA id: '" + newTz + "'.");
            }
            iv.setTimezone(newTz);
        }
        iv.setScheduledAt(req.scheduledFor());
        iv.setDurationMinutes(newDur);
        iv.setRescheduleCount((iv.getRescheduleCount() != null
                ? iv.getRescheduleCount() : 0) + 1);
        iv.setLastRescheduleReasonCode(rc.name());
        iv.setLastRescheduleReasonText(trimOrNull(req.reasonText()));
        iv.setLastRescheduledAt(Instant.now());
        iv.setLastRescheduledById(caller.getId());

        if (iv.getZoomMeetingId() != null && meetingProvider.isReady()) {
            try {
                // updateMeeting() refetches via GET so joinUrl / password
                // reflect any Zoom-side rotation that the PATCH may have
                // triggered. Persist whatever comes back.
                MeetingResponse z = meetingProvider.updateMeeting(
                        iv.getZoomMeetingId(), new MeetingRequest(
                                null,
                                brand.getName() + " interview — "
                                        + candidateName(iv.getApplication())
                                        + " — " + jobTitle(iv.getApplication()),
                                iv.getScheduledAt(), iv.getDurationMinutes(),
                                iv.getTimezone(), iv.getPrepInstructions()));
                if (z.joinUrl() != null) iv.setZoomJoinUrl(z.joinUrl());
                if (z.startUrl() != null) iv.setZoomStartUrl(z.startUrl());
                if (z.password() != null) iv.setZoomPassword(z.password());
                lastZoomOutcome.set(new ZoomCreateOutcome(
                        ErmInterviewDtos.ZoomStatus.OK, null));
            } catch (Exception e) {
                log.warn("[ErmInterviews] Zoom updateMeeting failed (non-fatal): {}",
                        e.getMessage());
                lastZoomOutcome.set(new ZoomCreateOutcome(
                        ErmInterviewDtos.ZoomStatus.UPDATE_FAILED, e.getMessage()));
            }
        } else if (iv.getZoomMeetingId() == null && meetingProvider.isReady()) {
            // Previously degraded; try to attach a meeting at reschedule time.
            attachZoomMeeting(iv, iv.getInterviewer());
        }
        interviewRepository.save(iv);

        writeEventLog(iv.getId(), caller.getId(), "RESCHEDULED",
                rc.name(), trimOrNull(req.reasonText()),
                Map.of("oldScheduledFor", previous.toString(),
                        "newScheduledFor", req.scheduledFor().toString(),
                        "oldDuration", previousDuration,
                        "newDuration", newDur,
                        "reasonCode", rc.name()));
        writeAudit(caller.getId(),
                applicantUserId(iv.getApplication()),
                "INTERVIEW_RESCHEDULED", "Interview", iv.getId(),
                Map.of("scheduledFor", previous.toString()),
                Map.of("scheduledFor", req.scheduledFor().toString(),
                        "reasonCode", rc.name()));

        try {
            eventPublisher.publishEvent(new InterviewRescheduledEvent(
                    iv.getId(),
                    iv.getApplication().getId(),
                    applicantUserId(iv.getApplication()),
                    iv.getInterviewer() != null ? iv.getInterviewer().getId() : null,
                    caller.getId(),
                    previous, req.scheduledFor(),
                    rc.name(),
                    req.notifyApplicant() == null || req.notifyApplicant(),
                    req.notifyInterviewer() == null || req.notifyInterviewer()));
        } catch (Exception e) {
            log.warn("[ErmInterviews] reschedule event publish failed: {}", e.getMessage());
        }
        return toDetail(iv, caller);
    }

    // ── Change interviewer ────────────────────────────────────────────────

    @Transactional
    public ErmInterviewDtos.ErmInterviewDetail changeInterviewer(
            UUID interviewId, UUID newInterviewerId, User caller) {
        if (newInterviewerId == null) {
            throw new BadRequestException("interviewerId is required");
        }
        Interview iv = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Interview not found: " + interviewId));
        if (iv.getStatus() != InterviewStatus.SCHEDULED) {
            throw new ConflictException(
                    "Change interviewer only allowed from SCHEDULED (current: "
                            + iv.getStatus() + ")");
        }
        UUID oldId = iv.getInterviewer() != null ? iv.getInterviewer().getId() : null;
        if (oldId != null && oldId.equals(newInterviewerId)) {
            throw new BadRequestException("New interviewer must differ from current.");
        }
        User next = userRepository.findById(newInterviewerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: " + newInterviewerId));
        validateInterviewer(next);

        // Recreate Zoom (S2S OAuth doesn't support host swap on existing
        // meetings; delete + create is the documented workaround).
        deleteZoomMeetingQuietly(iv);
        iv.setInterviewer(next);
        attachZoomMeeting(iv, next);
        interviewRepository.save(iv);

        Map<String, Object> evtPayload = new LinkedHashMap<>();
        evtPayload.put("oldInterviewerId", oldId != null ? oldId.toString() : null);
        evtPayload.put("newInterviewerId", newInterviewerId.toString());
        writeEventLog(iv.getId(), caller.getId(), "INTERVIEWER_CHANGED", null, null, evtPayload);
        Map<String, Object> auditBefore = new LinkedHashMap<>();
        auditBefore.put("interviewerId", oldId != null ? oldId.toString() : null);
        writeAudit(caller.getId(), applicantUserId(iv.getApplication()),
                "INTERVIEW_INTERVIEWER_CHANGED", "Interview", iv.getId(),
                auditBefore,
                Map.of("interviewerId", newInterviewerId.toString()));
        try {
            eventPublisher.publishEvent(new InterviewScheduledEvent(
                    iv.getId(), iv.getApplication().getId(),
                    applicantUserId(iv.getApplication()),
                    next.getId(), caller.getId()));
        } catch (Exception e) {
            log.warn("[ErmInterviews] change-interviewer event publish failed: {}", e.getMessage());
        }
        return toDetail(iv, caller);
    }

    // ── Complete ──────────────────────────────────────────────────────────

    @Transactional
    public ErmInterviewDtos.ErmInterviewDetail complete(
            UUID interviewId, ErmInterviewDtos.ErmCompleteRequest req, User caller) {
        if (req == null) throw new BadRequestException("body required");
        if (req.applicantVisibleNotes() == null
                || req.applicantVisibleNotes().trim().length() < APPLICANT_NOTES_MIN) {
            throw new BadRequestException(
                    "applicantVisibleNotes must be at least " + APPLICANT_NOTES_MIN + " characters");
        }
        if (req.internalNotes() != null && req.internalNotes().length() > INTERNAL_NOTES_MAX) {
            throw new BadRequestException(
                    "internalNotes cannot exceed " + INTERNAL_NOTES_MAX + " characters");
        }
        if (req.overallRecommendation() != null
                && !req.overallRecommendation().isBlank()
                && !VALID_RECOMMENDATIONS.contains(req.overallRecommendation().toUpperCase())) {
            throw new BadRequestException(
                    "overallRecommendation must be " + VALID_RECOMMENDATIONS);
        }
        clampScore("technicalScore", req.technicalScore());
        clampScore("communicationScore", req.communicationScore());
        clampScore("culturalFitScore", req.culturalFitScore());

        Interview iv = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Interview not found: " + interviewId));
        if (iv.getStatus() != InterviewStatus.SCHEDULED) {
            throw new ConflictException(
                    "Complete only allowed from SCHEDULED (current: " + iv.getStatus() + ")");
        }
        // Phase: Manager hire-approval gate. The ERM submits the scorecard;
        // it does NOT set decision/SELECTED/REJECTED. Interview.decision
        // stays null until a Manager actions the hire from the Hire
        // Approvals queue, which flips managerHireDecision and decision
        // atomically. Candidate waits at INTERVIEW_COMPLETED / INTERVIEWED.
        iv.setStatus(InterviewStatus.COMPLETED);
        iv.setTechnicalScore(req.technicalScore());
        iv.setCommunicationScore(req.communicationScore());
        iv.setCulturalFitScore(req.culturalFitScore());
        if (req.overallRecommendation() != null && !req.overallRecommendation().isBlank()) {
            iv.setOverallRecommendation(req.overallRecommendation().toUpperCase());
        }
        iv.setApplicantVisibleNotes(req.applicantVisibleNotes().trim());
        iv.setInternalNotes(trimOrNull(req.internalNotes()));
        iv.setFeedbackSubmittedAt(Instant.now());
        iv.setFeedbackSubmittedBy(caller.getId());
        iv.setManagerHireDecision("PENDING");
        // Optional interview recording — the ERM uploaded the video
        // directly to S3 (presign-upload flow) and passes the resulting
        // Document id back here so we can wire it to the manager view.
        // We don't re-validate ownership of the Document row: the
        // presign-upload endpoint already checked ERM+interview scope
        // when it minted the row, and only that ERM has the id.
        if (req.recordingDocumentId() != null) {
            iv.setRecordingDocumentId(req.recordingDocumentId());
        }
        interviewRepository.save(iv);

        Application app = iv.getApplication();
        if (app != null) {
            app.setStatus(ApplicationStatus.INTERVIEWED);
            app.setStatusUpdatedBy(caller.getId());
            app.setApplicantVisibleFeedback(req.applicantVisibleNotes().trim());
            applicationRepository.save(app);
            if (app.getCandidate() != null && app.getCandidate().getUser() != null) {
                internLifecycleService.advance(app.getCandidate().getUser(),
                        InternLifecycleStatus.INTERVIEW_COMPLETED, caller.getId());
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("technicalScore", req.technicalScore());
        payload.put("communicationScore", req.communicationScore());
        payload.put("culturalFitScore", req.culturalFitScore());
        payload.put("overallRecommendation", iv.getOverallRecommendation());
        payload.put("managerHireDecision", "PENDING");
        writeEventLog(iv.getId(), caller.getId(), "SCORECARD_SUBMITTED",
                null, null, payload);
        writeAudit(caller.getId(), applicantUserId(app),
                "INTERVIEW_SCORECARD_SUBMITTED", "Interview", iv.getId(),
                null, payload);

        try {
            String recName = iv.getOverallRecommendation();
            com.anvicorp.api.enums.InterviewRecommendation recommendationEnum = null;
            if (recName != null) {
                try {
                    recommendationEnum =
                            com.anvicorp.api.enums.InterviewRecommendation.valueOf(recName);
                } catch (Exception ignored) {}
            }
            String candidateEmail = app != null && app.getCandidate() != null
                    && app.getCandidate().getUser() != null
                    ? app.getCandidate().getUser().getEmail() : null;
            eventPublisher.publishEvent(new InterviewCompletedEvent(
                    iv.getId(),
                    app != null ? app.getId() : null,
                    applicantUserId(app),
                    candidateEmail,
                    recommendationEnum,
                    Instant.now(),
                    caller.getId()));
        } catch (Exception e) {
            log.warn("[ErmInterviews] complete event publish failed: {}", e.getMessage());
        }
        return toDetail(iv, caller);
    }

    // ── Cancel ────────────────────────────────────────────────────────────

    @Transactional
    public void cancel(UUID interviewId, ErmInterviewDtos.ErmCancelRequest req, User caller) {
        if (req == null) throw new BadRequestException("body required");
        ReasonCode rc = requireReason(req.reasonCode(), req.reasonText(),
                ReasonCode.Category.INTERVIEW_CANCEL);
        Interview iv = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Interview not found: " + interviewId));
        if (iv.getStatus() != InterviewStatus.SCHEDULED) {
            throw new ConflictException(
                    "Cancel only allowed from SCHEDULED (current: " + iv.getStatus() + ")");
        }
        iv.setStatus(InterviewStatus.CANCELLED);
        iv.setCancellationReasonCode(rc.name());
        iv.setCancellationReasonText(trimOrNull(req.reasonText()));
        iv.setCancelledAt(Instant.now());
        iv.setCancelledById(caller.getId());

        deleteZoomMeetingQuietly(iv);
        interviewRepository.save(iv);

        // Revert application + lifecycle. Cancel is the ONLY operation that
        // reverses users.lifecycle_status — INTERVIEW_SCHEDULED → SHORTLISTED.
        // Documented at the class level + logged at INFO with reason.
        Application app = iv.getApplication();
        if (app != null && app.getStatus() == ApplicationStatus.INTERVIEW_SCHEDULED) {
            app.setStatus(ApplicationStatus.SHORTLISTED);
            app.setStatusUpdatedBy(caller.getId());
            applicationRepository.save(app);
            if (app.getCandidate() != null && app.getCandidate().getUser() != null) {
                User u = app.getCandidate().getUser();
                if (u.getLifecycleStatus() == InternLifecycleStatus.INTERVIEW_SCHEDULED) {
                    log.info("[ErmInterviews] cancel-induced lifecycle reversal "
                                    + "INTERVIEW_SCHEDULED → SHORTLISTED for user={} reason={}",
                            u.getId(), rc.name());
                    u.setLifecycleStatus(InternLifecycleStatus.SHORTLISTED);
                    userRepository.save(u);
                }
            }
        }

        writeEventLog(iv.getId(), caller.getId(), "CANCELLED",
                rc.name(), trimOrNull(req.reasonText()),
                Map.of("reasonCode", rc.name()));
        writeAudit(caller.getId(), applicantUserId(app),
                "INTERVIEW_CANCELLED", "Interview", iv.getId(),
                Map.of("status", "SCHEDULED"),
                Map.of("status", "CANCELLED", "reasonCode", rc.name()));

        try {
            eventPublisher.publishEvent(new InterviewCancelledEvent(
                    iv.getId(),
                    app != null ? app.getId() : null,
                    applicantUserId(app),
                    iv.getInterviewer() != null ? iv.getInterviewer().getId() : null,
                    caller.getId(),
                    rc.name(),
                    trimOrNull(req.reasonText()),
                    req.notifyApplicant() == null || req.notifyApplicant()));
        } catch (Exception e) {
            log.warn("[ErmInterviews] cancel event publish failed: {}", e.getMessage());
        }
    }

    // ── Notes ─────────────────────────────────────────────────────────────

    @Transactional
    public void recordNotes(UUID interviewId,
                             String applicantVisibleNotes, String internalNotes,
                             User caller) {
        if ((applicantVisibleNotes == null || applicantVisibleNotes.isBlank())
                && (internalNotes == null || internalNotes.isBlank())) {
            throw new BadRequestException("at least one of the note fields is required");
        }
        Interview iv = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Interview not found: " + interviewId));
        if (applicantVisibleNotes != null) iv.setApplicantVisibleNotes(applicantVisibleNotes.trim());
        if (internalNotes != null) {
            if (internalNotes.length() > INTERNAL_NOTES_MAX) {
                throw new BadRequestException(
                        "internalNotes cannot exceed " + INTERNAL_NOTES_MAX + " characters");
            }
            iv.setInternalNotes(internalNotes.trim());
        }
        interviewRepository.save(iv);
        writeEventLog(iv.getId(), caller.getId(), "NOTES_UPDATED", null, null, Map.of());
    }

    // ── Regenerate Zoom meeting ───────────────────────────────────────────

    /**
     * Recreate a Zoom meeting for an interview that doesn't have one (or
     * has one we want to replace). Used by the ERM when the original Zoom
     * call failed (creds were missing, transient outage) or the meeting
     * needs a fresh link. Only allowed while the interview is SCHEDULED.
     *
     * <p>If a meeting id already exists, we attempt to delete it first so
     * we don't orphan it in Zoom. Failures on the delete leg are logged
     * but don't block the recreate.</p>
     */
    @Transactional
    public ErmInterviewDtos.ErmInterviewDetail regenerateZoom(UUID interviewId, User caller) {
        Interview iv = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Interview not found: " + interviewId));
        if (iv.getStatus() != InterviewStatus.SCHEDULED) {
            throw new ConflictException(
                    "Regenerate only allowed from SCHEDULED (current: " + iv.getStatus() + ")");
        }
        if (!meetingProvider.isReady()) {
            String provider = meetingProvider.providerName();
            if (meetingProvider.isForceDisabled()) {
                throw new ConflictException(
                        "Meeting provider '" + provider + "' is force-disabled. "
                                + "Unset the kill-switch to use the configured credentials.");
            }
            throw new ConflictException(
                    "Meeting provider '" + provider + "' is not configured on the server.");
        }
        deleteZoomMeetingQuietly(iv);
        attachZoomMeeting(iv, iv.getInterviewer());
        interviewRepository.save(iv);

        writeEventLog(iv.getId(), caller.getId(), "ZOOM_REGENERATED", null, null,
                Map.of("hasJoinUrl", iv.getZoomJoinUrl() != null));
        ZoomCreateOutcome out = lastZoomOutcome.get();
        if (out != null && out.status != ErmInterviewDtos.ZoomStatus.OK) {
            // Surface the failure to the caller — they explicitly asked to
            // regenerate and need a clear signal it didn't work.
            throw new ConflictException("Zoom meeting recreation failed: "
                    + (out.errorMessage != null ? out.errorMessage : "unknown error"));
        }
        return toDetail(iv, caller);
    }

    // ── Eligible interviewers ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ErmInterviewDtos.InterviewerView> listEligibleInterviewers() {
        Set<UserRole> eligible = EnumSet.of(
                UserRole.ERM, UserRole.TRAINER, UserRole.REPORTING_MANAGER,
                UserRole.MANAGER, UserRole.SUPER_ADMIN);
        List<ErmInterviewDtos.InterviewerView> out = new ArrayList<>();
        for (UserRole r : eligible) {
            try {
                for (User u : userRepository.findByRole(r)) {
                    if (u == null || !Boolean.TRUE.equals(u.getActive())) continue;
                    out.add(toInterviewerView(u));
                }
            } catch (Exception ignored) {}
        }
        // Dedup by user id (a user may carry multiple roles).
        Map<UUID, ErmInterviewDtos.InterviewerView> dedup = new LinkedHashMap<>();
        for (ErmInterviewDtos.InterviewerView v : out) dedup.putIfAbsent(v.userId(), v);
        return new ArrayList<>(dedup.values());
    }

    // ── Reason codes ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ErmInterviewDtos.ReasonCodeGroup> listReasonCodes(String decisionFamily) {
        Set<ReasonCode.Category> cats;
        if (decisionFamily == null || decisionFamily.isBlank()) {
            cats = EnumSet.of(
                    ReasonCode.Category.INTERVIEW_DECISION,
                    ReasonCode.Category.INTERVIEW_RESCHEDULE,
                    ReasonCode.Category.INTERVIEW_CANCEL);
        } else {
            cats = switch (decisionFamily.toUpperCase()) {
                case "DECISION" -> EnumSet.of(ReasonCode.Category.INTERVIEW_DECISION);
                case "RESCHEDULE" -> EnumSet.of(ReasonCode.Category.INTERVIEW_RESCHEDULE);
                case "CANCEL" -> EnumSet.of(ReasonCode.Category.INTERVIEW_CANCEL);
                default -> EnumSet.noneOf(ReasonCode.Category.class);
            };
        }
        Map<ReasonCode.Category, List<ErmInterviewDtos.ReasonCodeOption>> bucket =
                new EnumMap<>(ReasonCode.Category.class);
        for (ReasonCode rc : ReasonCode.values()) {
            if (!cats.contains(rc.category())) continue;
            bucket.computeIfAbsent(rc.category(), k -> new ArrayList<>())
                    .add(new ErmInterviewDtos.ReasonCodeOption(
                            rc.name(), rc.humanLabel(), rc.requiresFreeText()));
        }
        List<ErmInterviewDtos.ReasonCodeGroup> out = new ArrayList<>();
        for (var e : bucket.entrySet()) {
            out.add(new ErmInterviewDtos.ReasonCodeGroup(e.getKey().name(), e.getValue()));
        }
        return out;
    }

    // ── Mapping with field RBAC ──────────────────────────────────────────

    private ErmInterviewDtos.ErmInterviewDetail toDetail(Interview iv, User caller) {
        Application app = iv.getApplication();
        Candidate c = app != null ? app.getCandidate() : null;
        User applicantUser = c != null ? c.getUser() : null;
        JobPosting jp = app != null ? app.getJobPosting() : null;
        User interviewer = iv.getInterviewer();

        Role role = roleOf(caller);
        boolean isErm = role == Role.ERM || role == Role.SUPER_ADMIN;
        boolean isInterviewer = interviewer != null && caller != null
                && interviewer.getId().equals(caller.getId());
        boolean isPanel = isOnPanel(iv, caller);
        boolean isManager = role == Role.MANAGER;
        boolean canSeePrivate = isErm || isInterviewer || isPanel;
        boolean canSeeScores = canSeePrivate || isManager;

        String startUrl = canSeePrivate ? iv.getZoomStartUrl() : null;
        String zoomPassword = canSeePrivate ? iv.getZoomPassword() : null;
        String internalNotes = isErm ? iv.getInternalNotes() : null;
        String decisionReasonText = isErm ? iv.getDecisionReasonText() : null;
        String lastResheduleReasonText = isErm ? iv.getLastRescheduleReasonText() : null;
        String cancellationReasonText = isErm ? iv.getCancellationReasonText() : null;

        Integer techScore = canSeeScores ? iv.getTechnicalScore() : null;
        Integer commScore = canSeeScores ? iv.getCommunicationScore() : null;
        Integer cultScore = canSeeScores ? iv.getCulturalFitScore() : null;
        String recommendation = canSeeScores ? iv.getOverallRecommendation() : null;

        ErmInterviewDtos.ApplicantView applicantView = applicantUser == null ? null
                : new ErmInterviewDtos.ApplicantView(
                        applicantUser.getId(),
                        firstName(applicantUser),
                        lastName(applicantUser),
                        applicantUser.getEmail(),
                        applicantUser.getApplicantId(),
                        app.getId(),
                        app.getStatus() != null ? app.getStatus().name() : null);
        ErmInterviewDtos.JobView jobView = jp == null ? null
                : new ErmInterviewDtos.JobView(jp.getId(), jp.getTitle(),
                        jp.getEmploymentType() != null ? jp.getEmploymentType().name() : null,
                        jp.getLocation());

        ErmInterviewDtos.InterviewerView interviewerView = interviewer == null ? null
                : toInterviewerView(interviewer);
        List<ErmInterviewDtos.InterviewerView> panel = new ArrayList<>();
        for (UUID uid : parsePanel(iv.getPanelInterviewerIdsJson())) {
            try { userRepository.findById(uid).ifPresent(u -> panel.add(toInterviewerView(u))); }
            catch (Exception ignored) {}
        }

        List<ErmInterviewDtos.EventLogEntry> history = new ArrayList<>();
        try {
            for (InterviewEventLog l : eventLogRepository
                    .findByInterviewIdOrderByCreatedAtDesc(iv.getId())) {
                User actor = l.getActorUserId() != null
                        ? userRepository.findById(l.getActorUserId()).orElse(null) : null;
                history.add(new ErmInterviewDtos.EventLogEntry(
                        l.getId(), l.getEventType(), l.getReasonCode(),
                        isErm ? l.getReasonText() : null,
                        l.getPayloadJson(),
                        l.getActorUserId(), actor != null ? actor.getFullName() : null,
                        l.getCreatedAt()));
            }
        } catch (Exception ignored) {}

        ErmInterviewDtos.AvailableActions actions = computeActions(iv, canSeePrivate);

        // Surface the most recent Zoom outcome to the ERM. If this call
        // mutated Zoom in some way, the ThreadLocal carries the live
        // result; on read-only fetches we infer status from the row.
        ZoomCreateOutcome out = lastZoomOutcome.get();
        lastZoomOutcome.remove();
        ErmInterviewDtos.ZoomStatus zStatus;
        String zErr;
        if (out != null) {
            zStatus = out.status;
            zErr = out.errorMessage;
        } else if (iv.getZoomJoinUrl() != null && iv.getZoomMeetingId() != null) {
            zStatus = ErmInterviewDtos.ZoomStatus.OK;
            zErr = null;
        } else if (iv.getZoomJoinUrl() != null) {
            // Manual link path — has a join url but no Zoom-issued meeting id.
            zStatus = ErmInterviewDtos.ZoomStatus.MANUAL_LINK;
            zErr = null;
        } else if (meetingProvider.isForceDisabled()) {
            zStatus = ErmInterviewDtos.ZoomStatus.DISABLED;
            zErr = null;
        } else if (!meetingProvider.hasCredentials()) {
            zStatus = ErmInterviewDtos.ZoomStatus.NOT_CONFIGURED;
            zErr = null;
        } else {
            // Configured but the row has no link — prior attempt failed
            // and wasn't retried yet. ERM can hit Regenerate.
            zStatus = ErmInterviewDtos.ZoomStatus.CREATE_FAILED;
            zErr = null;
        }
        // Hide Zoom failure details from non-staff viewers.
        if (!canSeePrivate) zErr = null;

        return new ErmInterviewDtos.ErmInterviewDetail(
                iv.getId(),
                iv.getStatus(),
                iv.getType(),
                iv.getScheduledAt(),
                iv.getDurationMinutes(),
                iv.getTimezone(),
                iv.getPrepInstructions(),
                iv.getZoomJoinUrl(),
                startUrl,
                zoomPassword,
                iv.getZoomMeetingId(),
                zStatus,
                zErr,
                iv.getDecision(),
                recommendation,
                techScore, commScore, cultScore,
                iv.getApplicantVisibleNotes(),
                internalNotes,
                iv.getDecisionReasonCode(),
                decisionReasonText,
                iv.getManagerHireDecision(),
                iv.getManagerHireDecisionAt(),
                iv.getManagerHireDecisionNote(),
                iv.getRescheduleCount() != null ? iv.getRescheduleCount() : 0,
                iv.getLastRescheduleReasonCode(),
                lastResheduleReasonText,
                iv.getLastRescheduledAt(),
                iv.getCancellationReasonCode(),
                cancellationReasonText,
                iv.getCancelledAt(),
                iv.getCreatedBy(),
                iv.getCreatedAt(),
                iv.getUpdatedAt(),
                applicantView,
                jobView,
                interviewerView,
                panel,
                history,
                actions,
                role.name());
    }

    private ErmInterviewDtos.InterviewerView toInterviewerView(User u) {
        String role = u.getRoles() != null && !u.getRoles().isEmpty()
                ? u.getRoles().iterator().next().name() : null;
        return new ErmInterviewDtos.InterviewerView(
                u.getId(), u.getFullName(), u.getEmail(), role,
                u.getZoomEmail(),
                u.getZoomEmail() != null && !u.getZoomEmail().isBlank());
    }

    private static ErmInterviewDtos.AvailableActions computeActions(Interview iv, boolean isErmOrInterviewer) {
        boolean isScheduled = iv.getStatus() == InterviewStatus.SCHEDULED;
        return new ErmInterviewDtos.AvailableActions(
                isScheduled && isErmOrInterviewer,
                isScheduled && isErmOrInterviewer,
                isScheduled && isErmOrInterviewer,
                isScheduled && isErmOrInterviewer,
                isErmOrInterviewer,
                isScheduled && isErmOrInterviewer);
    }

    // ── Zoom helpers ──────────────────────────────────────────────────────

    /**
     * Best-effort attach of a Zoom meeting to the interview row. Records
     * the outcome on the per-request ThreadLocal so {@link #toDetail} can
     * project it into the response DTO. Never throws — Zoom failure must
     * not lose or block the interview.
     */
    private void attachZoomMeeting(Interview iv, User host) {
        if (!meetingProvider.isReady()) {
            ErmInterviewDtos.ZoomStatus s = meetingProvider.isForceDisabled()
                    ? ErmInterviewDtos.ZoomStatus.DISABLED
                    : ErmInterviewDtos.ZoomStatus.NOT_CONFIGURED;
            lastZoomOutcome.set(new ZoomCreateOutcome(s, null));
            log.info("[ErmInterviews] Zoom {} — interview {} stored without link",
                    s.name().toLowerCase(), iv.getId());
            return;
        }
        String hostKey = host != null && host.getZoomEmail() != null
                && !host.getZoomEmail().isBlank()
                ? host.getZoomEmail() : "me";
        Application app = iv.getApplication();
        String topic = brand.getName() + " interview — " + candidateName(app) + " — " + jobTitle(app);
        try {
            MeetingResponse z = meetingProvider.createMeeting(new MeetingRequest(
                    hostKey, topic, iv.getScheduledAt(), iv.getDurationMinutes(),
                    iv.getTimezone(), iv.getPrepInstructions()));
            iv.setZoomMeetingId(z.providerMeetingId());
            iv.setZoomJoinUrl(z.joinUrl());
            iv.setZoomStartUrl(z.startUrl());
            iv.setZoomPassword(z.password());
            lastZoomOutcome.set(new ZoomCreateOutcome(
                    ErmInterviewDtos.ZoomStatus.OK, null));
        } catch (Exception e) {
            log.warn("[ErmInterviews] Zoom createMeeting failed for interview {} (degraded): {}",
                    iv.getId(), e.getMessage());
            lastZoomOutcome.set(new ZoomCreateOutcome(
                    ErmInterviewDtos.ZoomStatus.CREATE_FAILED, e.getMessage()));
        }
    }

    /**
     * Delete the Zoom meeting bound to the interview row (if any) and
     * clear all four Zoom columns locally. Used by cancel, change-
     * interviewer, and regenerate; never throws.
     */
    private void deleteZoomMeetingQuietly(Interview iv) {
        String mid = iv.getZoomMeetingId();
        if (mid != null && meetingProvider.isReady()) {
            try {
                meetingProvider.deleteMeeting(mid);
            } catch (Exception e) {
                log.warn("[ErmInterviews] Zoom deleteMeeting failed for interview {} (non-fatal): {}",
                        iv.getId(), e.getMessage());
            }
        }
        iv.setZoomMeetingId(null);
        iv.setZoomJoinUrl(null);
        iv.setZoomStartUrl(null);
        iv.setZoomPassword(null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private enum Role { INTERN, TRAINER, EVALUATOR, MANAGER, ERM, SUPER_ADMIN, UNKNOWN }

    private static Role roleOf(User u) {
        if (u == null || u.getRoles() == null) return Role.UNKNOWN;
        if (u.getRoles().contains(UserRole.SUPER_ADMIN)) return Role.SUPER_ADMIN;
        if (u.getRoles().contains(UserRole.ERM)) return Role.ERM;
        if (u.getRoles().contains(UserRole.MANAGER)) return Role.MANAGER;
        if (u.getRoles().contains(UserRole.REPORTING_MANAGER)) return Role.EVALUATOR;
        if (u.getRoles().contains(UserRole.TRAINER)) return Role.TRAINER;
        if (u.getRoles().contains(UserRole.INTERN)) return Role.INTERN;
        return Role.UNKNOWN;
    }

    private boolean isOnPanel(Interview iv, User caller) {
        if (caller == null) return false;
        for (UUID uid : parsePanel(iv.getPanelInterviewerIdsJson())) {
            if (caller.getId().equals(uid)) return true;
        }
        return false;
    }

    private void validateInterviewer(User u) {
        boolean ok = u.getRoles() != null && (
                u.getRoles().contains(UserRole.ERM)
                        || u.getRoles().contains(UserRole.TRAINER)
                        || u.getRoles().contains(UserRole.REPORTING_MANAGER)
                        || u.getRoles().contains(UserRole.MANAGER)
                        || u.getRoles().contains(UserRole.SUPER_ADMIN));
        if (!ok) {
            throw new BadRequestException(
                    "Interviewer must hold a staff role (ERM/TRAINER/EVALUATOR/MANAGER/SUPER_ADMIN)");
        }
        if (meetingProvider.isReady()
                && (u.getZoomEmail() == null || u.getZoomEmail().isBlank())) {
            log.warn("[ErmInterviews] interviewer {} has no zoom_email — host will fall back to 'me'",
                    u.getId());
        }
    }

    private ReasonCode requireReason(String code, String text, ReasonCode.Category cat) {
        if (code == null || code.isBlank()) {
            throw new BadRequestException("reasonCode is required");
        }
        ReasonCode rc;
        try { rc = ReasonCode.valueOf(code.trim().toUpperCase()); }
        catch (Exception e) { throw new BadRequestException("Unknown reasonCode: " + code); }
        if (rc.category() != cat) {
            throw new BadRequestException(
                    "reasonCode category " + rc.category() + " does not match expected " + cat);
        }
        if (rc.requiresFreeText() && (text == null || text.trim().length() < REASON_TEXT_MIN)) {
            throw new BadRequestException(
                    "reasonText must be at least " + REASON_TEXT_MIN
                            + " characters when reasonCode is " + rc.name());
        }
        return rc;
    }

    private static void clampScore(String field, Integer v) {
        if (v == null) return;
        if (v < 1 || v > 10) {
            throw new BadRequestException(field + " must be between 1 and 10");
        }
    }

    private String serializePanel(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return null;
        try { return objectMapper.writeValueAsString(ids); }
        catch (Exception e) { return null; }
    }

    private List<UUID> parsePanel(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> raw = objectMapper.readValue(json, STRING_LIST);
            List<UUID> out = new ArrayList<>();
            for (String s : raw) {
                try { out.add(UUID.fromString(s)); } catch (Exception ignored) {}
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static UUID applicantUserId(Application app) {
        if (app == null || app.getCandidate() == null
                || app.getCandidate().getUser() == null) return null;
        return app.getCandidate().getUser().getId();
    }

    private static String candidateName(Application app) {
        if (app == null || app.getCandidate() == null
                || app.getCandidate().getUser() == null) return "Candidate";
        return app.getCandidate().getUser().getFullName();
    }

    private static String jobTitle(Application app) {
        if (app == null || app.getJobPosting() == null) return "the role";
        return app.getJobPosting().getTitle();
    }

    private static String firstName(User u) {
        String full = u.getFullName();
        if (full == null || full.isBlank()) return "";
        return full.trim().split("\\s+", 2)[0];
    }

    private static String lastName(User u) {
        String full = u.getFullName();
        if (full == null || full.isBlank()) return "";
        String[] parts = full.trim().split("\\s+", 2);
        return parts.length > 1 ? parts[1] : "";
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static UUID nullableUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private void writeEventLog(UUID interviewId, UUID actorId, String eventType,
                                String reasonCode, String reasonText,
                                Map<String, Object> payload) {
        try {
            InterviewEventLog row = InterviewEventLog.builder()
                    .interviewId(interviewId)
                    .actorUserId(actorId)
                    .eventType(eventType)
                    .reasonCode(reasonCode)
                    .reasonText(reasonText)
                    .payloadJson(payload != null && !payload.isEmpty()
                            ? objectMapper.writeValueAsString(payload) : null)
                    .build();
            eventLogRepository.save(row);
        } catch (JsonProcessingException jpe) {
            log.warn("[ErmInterviews] event log JSON failed: {}", jpe.getMessage());
        } catch (Exception e) {
            log.warn("[ErmInterviews] event log write failed: {}", e.getMessage());
        }
    }

    private void writeAudit(UUID actorId, UUID subjectUserId, String action,
                             String entityType, UUID entityId,
                             Map<String, Object> before, Map<String, Object> after) {
        try {
            AuditLog row = AuditLog.builder()
                    .userId(actorId)
                    .subjectUserId(subjectUserId)
                    .entityType(entityType)
                    .entityId(entityId)
                    .action(action)
                    .beforeJson(before != null ? objectMapper.writeValueAsString(before) : null)
                    .afterJson(after != null ? objectMapper.writeValueAsString(after) : null)
                    .build();
            auditLogRepository.save(row);
        } catch (Exception e) {
            log.warn("[ErmInterviews] audit write failed: {}", e.getMessage());
        }
    }
}
