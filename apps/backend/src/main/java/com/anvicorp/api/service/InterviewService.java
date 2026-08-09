package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.interview.CandidateInterviewResponse;
import com.anvicorp.api.dto.interview.InterviewResponse;
import com.anvicorp.api.dto.interview.InterviewScorecardSummary;
import com.anvicorp.api.dto.interview.InterviewSummaryResponse;
import com.anvicorp.api.dto.interview.ScheduleInterviewRequest;
import com.anvicorp.api.dto.interview.SubmitFeedbackRequest;
import com.anvicorp.api.dto.interview.SubmitScorecardRequest;
import com.anvicorp.api.dto.interview.UpdateInterviewRequest;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.InterviewStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.event.InterviewCompletedEvent;
import com.anvicorp.api.notification.NotificationService;
import org.springframework.context.ApplicationEventPublisher;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.InterviewRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewService {

    private static final Set<ApplicationStatus> SCHEDULABLE_FROM = EnumSet.of(ApplicationStatus.SHORTLISTED, ApplicationStatus.INTERVIEW_SCHEDULED, ApplicationStatus.INTERVIEWED);

    private final InterviewRepository interviewRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationService applicationService;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.anvicorp.api.integration.meeting.MeetingProvider meetingProvider;
    private final com.anvicorp.api.intern.InternLifecycleService internLifecycleService;
    private final com.anvicorp.api.config.BrandConfig brand;

    // ── Commands ────────────────────────────────────────────────────────────

    @Transactional
    public InterviewResponse schedule(ScheduleInterviewRequest req, User scheduler) {
        Application application = applicationRepository.findById(req.getApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + req.getApplicationId()));

        if (!SCHEDULABLE_FROM.contains(application.getStatus())) {
            throw new BadRequestException(
                    "Cannot schedule interview for application in status " + application.getStatus());
        }

        User interviewer = userRepository.findById(req.getInterviewerId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Interviewer not found: " + req.getInterviewerId()));

        if ((interviewer.getRoles().contains(UserRole.INTERN) || interviewer.getRoles().contains(UserRole.INTERN))
                && interviewer.getRoles().size() == 1) {
            throw new BadRequestException("Interviewer must be an internal user, not a candidate");
        }

        Interview interview = Interview.builder()
                .application(application)
                .interviewer(interviewer)
                .scheduledAt(req.getScheduledAt())
                .durationMinutes(req.getDurationMinutes() != null ? req.getDurationMinutes() : 60)
                .type(req.getType())
                .status(InterviewStatus.SCHEDULED)
                .meetingUrl(req.getMeetingUrl())
                .candidateNotes(req.getCandidateNotes())
                .createdBy(scheduler.getId())
                .build();

        // Phase 2 doc-spec fields (timezone, prep, applicant-safe outcome).
        if (req.getTimezone() != null && !req.getTimezone().isBlank()) {
            interview.setTimezone(req.getTimezone());
        }
        if (req.getPrepInstructions() != null && !req.getPrepInstructions().isBlank()) {
            interview.setPrepInstructions(req.getPrepInstructions());
        }

        // Phase 2 — real Zoom meeting create. Best-effort: if Zoom fails (or
        // the integration is disabled) the interview persists without Zoom
        // fields and the caller can populate them manually. The HTTP response
        // header X-Zoom-Status=degraded is set by the controller in that case.
        if (meetingProvider.isReady()) {
            try {
                String hostId = interviewer.getZoomEmail() != null
                        && !interviewer.getZoomEmail().isBlank()
                        ? interviewer.getZoomEmail()
                        : "me";
                String topic = brand.getName() + " interview — "
                        + (application.getJobPosting() != null
                                ? application.getJobPosting().getTitle() : "Application");
                com.anvicorp.api.integration.meeting.MeetingResponse meeting =
                        meetingProvider.createMeeting(
                                new com.anvicorp.api.integration.meeting.MeetingRequest(
                                        hostId,
                                        topic,
                                        req.getScheduledAt(),
                                        interview.getDurationMinutes(),
                                        interview.getTimezone(),
                                        req.getPrepInstructions()));
                interview.setZoomMeetingId(meeting.providerMeetingId());
                interview.setZoomJoinUrl(meeting.joinUrl());
                interview.setZoomStartUrl(meeting.startUrl());
                interview.setZoomPassword(meeting.password());
                // Keep the legacy meeting_url in sync — frontend hero card
                // falls back to it when zoom_join_url isn't populated.
                if (interview.getMeetingUrl() == null || interview.getMeetingUrl().isBlank()) {
                    interview.setMeetingUrl(meeting.joinUrl());
                }
                log.info("[Zoom] meeting created id={} for interview application={}",
                        meeting.providerMeetingId(), application.getId());
            } catch (Exception e) {
                log.warn("[Zoom] createMeeting failed for application={} — interview "
                        + "persists without Zoom fields: {}",
                        application.getId(), e.getMessage());
            }
        }

        interview = interviewRepository.save(interview);

        if (application.getStatus() == ApplicationStatus.SHORTLISTED) {
            // SHORTLISTED → INTERVIEW_SCHEDULED is legal in LEGAL_TRANSITIONS;
            // transitionTo writes the single STATUS_CHANGE audit row.
            applicationService.transitionTo(application, ApplicationStatus.INTERVIEW_SCHEDULED,
                    "STATUS_CHANGE", scheduler);
        }

        // Phase 2 — advance the applicant's lifecycle to INTERVIEW_SCHEDULED
        // atomically. Monotonic; subsequent reschedules are no-ops.
        try {
            User applicantUser = application.getCandidate() != null
                    ? application.getCandidate().getUser() : null;
            if (applicantUser != null) {
                internLifecycleService.advance(applicantUser,
                        com.anvicorp.api.enums.InternLifecycleStatus.INTERVIEW_SCHEDULED,
                        scheduler != null ? scheduler.getId() : null);
            }
        } catch (Exception e) {
            log.warn("Lifecycle advance failed (non-fatal) on schedule for app {}: {}",
                    application.getId(), e.getMessage());
        }

        writeAudit("Interview", interview.getId(), "SCHEDULE", scheduler.getId(),
                null, snapshot(interview));

        // Batch-1 notification — applicant gets interview details. Best-effort:
        // a send failure must NOT block scheduling.
        try {
            notificationService.sendInterviewScheduled(interview);
        } catch (Exception e) {
            log.warn("INTERVIEW_SCHEDULED notify failed (non-fatal) for {}: {}",
                    interview.getId(), e.getMessage());
        }
        return toResponse(interview);
    }

    // ── Phase 2 doc-spec commands ───────────────────────────────────────────

    /**
     * Phase 2 — ERM marks an interview complete with a doc-spec decision and
     * an applicant-safe message. Atomically:
     *   1. status=COMPLETED, decision, applicant_visible_notes, internal_notes
     *   2. application stage advances to INTERVIEWED (Phase 0/1 mapping)
     *   3. application.applicant_visible_feedback mirrors the message so the
     *      detail page shows the same copy without an extra fetch
     *   4. applicant's lifecycle status advances to INTERVIEW_COMPLETED
     *   5. InterviewCompletedEvent published for the existing notification chain
     */
    @Transactional
    public InterviewResponse complete(UUID interviewId,
                                      com.anvicorp.api.dto.interview.CompleteInterviewRequest req,
                                      User actor) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview not found: " + interviewId));

        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw new BadRequestException(
                    "Cannot complete interview in status " + interview.getStatus());
        }
        if (req == null || req.getDecision() == null) {
            throw new BadRequestException("decision is required (SELECTED | HOLD | REJECTED)");
        }
        // applicantVisibleNotes is optional as of the F8 revision — no
        // mandatory minimum. The DTO still enforces the 4000-char upper
        // bound so a runaway paste can't OOM the write.

        Map<String, Object> before = snapshot(interview);
        interview.setStatus(InterviewStatus.COMPLETED);
        interview.setDecision(req.getDecision().name());
        interview.setApplicantVisibleNotes(req.getApplicantVisibleNotes().trim());
        if (req.getInternalNotes() != null && !req.getInternalNotes().isBlank()) {
            interview.setInternalNotes(req.getInternalNotes().trim());
        }
        Interview saved = interviewRepository.save(interview);

        Application application = saved.getApplication();
        if (application != null) {
            application.setApplicantVisibleFeedback(saved.getApplicantVisibleNotes());
            if (application.getStatus() == ApplicationStatus.INTERVIEW_SCHEDULED
                    || application.getStatus() == ApplicationStatus.SHORTLISTED) {
                applicationService.transitionTo(application, ApplicationStatus.INTERVIEWED,
                        "STATUS_CHANGE", actor);
            } else {
                applicationRepository.save(application);
            }
            try {
                User applicantUser = application.getCandidate() != null
                        ? application.getCandidate().getUser() : null;
                if (applicantUser != null) {
                    internLifecycleService.advance(applicantUser,
                            com.anvicorp.api.enums.InternLifecycleStatus.INTERVIEW_COMPLETED,
                            actor != null ? actor.getId() : null);
                }
            } catch (Exception e) {
                log.warn("Lifecycle advance failed (non-fatal) on complete for interview {}: {}",
                        saved.getId(), e.getMessage());
            }
        }

        writeAudit("Interview", saved.getId(), "COMPLETE",
                actor != null ? actor.getId() : null, before, snapshot(saved));

        try {
            Candidate cand = application != null ? application.getCandidate() : null;
            User candidateUser = cand != null ? cand.getUser() : null;
            // Map doc-spec decision (SELECTED|HOLD|REJECTED) onto the existing
            // InterviewRecommendation enum the event already carries.
            // HOLD has no first-class recommendation enum value; pass null so
            // downstream listeners can decide how to fan out.
            com.anvicorp.api.enums.InterviewRecommendation rec =
                    switch (req.getDecision()) {
                        case SELECTED -> com.anvicorp.api.enums.InterviewRecommendation.HIRE;
                        case HOLD     -> null;
                        case REJECTED -> com.anvicorp.api.enums.InterviewRecommendation.NO_HIRE;
                    };
            eventPublisher.publishEvent(new InterviewCompletedEvent(
                    saved.getId(),
                    application != null ? application.getId() : null,
                    candidateUser != null ? candidateUser.getId() : null,
                    candidateUser != null ? candidateUser.getEmail() : null,
                    rec,
                    Instant.now(),
                    actor != null ? actor.getId() : null));
        } catch (Exception e) {
            log.warn("InterviewCompletedEvent publish failed (non-fatal) for {}: {}",
                    saved.getId(), e.getMessage());
        }
        return toResponse(saved);
    }

    /**
     * Phase 2 — ERM cancels a SCHEDULED interview. Deletes the Zoom meeting
     * best-effort and writes a CANCEL audit row. Lifecycle status is NOT
     * regressed — once an applicant has been interview-scheduled, that's
     * recorded; the dashboard simply shows the cancellation.
     */
    @Transactional
    public InterviewResponse cancel(UUID interviewId, User actor) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview not found: " + interviewId));
        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw new BadRequestException("Only SCHEDULED interviews can be cancelled");
        }
        Map<String, Object> before = snapshot(interview);

        String zoomId = interview.getZoomMeetingId();
        if (zoomId != null && meetingProvider.isReady()) {
            try {
                meetingProvider.deleteMeeting(zoomId);
            } catch (Exception e) {
                log.warn("[Zoom] deleteMeeting failed (non-fatal) for interview {}: {}",
                        interview.getId(), e.getMessage());
            }
        }

        interview.setStatus(InterviewStatus.CANCELLED);
        Interview saved = interviewRepository.save(interview);
        writeAudit("Interview", saved.getId(), "CANCEL",
                actor != null ? actor.getId() : null, before, snapshot(saved));
        return toResponse(saved);
    }

    /**
     * Phase 2 — ERM reschedules a SCHEDULED interview. New time + duration
     * pushed to Zoom; lifecycle status untouched (already INTERVIEW_SCHEDULED).
     */
    @Transactional
    public InterviewResponse reschedule(UUID interviewId,
                                        com.anvicorp.api.dto.interview.RescheduleInterviewRequest req,
                                        User actor) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview not found: " + interviewId));
        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw new BadRequestException("Only SCHEDULED interviews can be rescheduled");
        }
        if (req == null || req.getScheduledAt() == null) {
            throw new BadRequestException("scheduledAt is required");
        }
        if (req.getScheduledAt().isBefore(Instant.now())) {
            throw new BadRequestException("scheduledAt must be in the future");
        }
        Map<String, Object> before = snapshot(interview);

        interview.setScheduledAt(req.getScheduledAt());
        if (req.getDurationMinutes() != null) {
            interview.setDurationMinutes(req.getDurationMinutes());
        }
        if (req.getTimezone() != null && !req.getTimezone().isBlank()) {
            interview.setTimezone(req.getTimezone());
        }

        String zoomId = interview.getZoomMeetingId();
        if (zoomId != null && meetingProvider.isReady()) {
            try {
                String topic = brand.getName() + " interview — "
                        + (interview.getApplication() != null
                                && interview.getApplication().getJobPosting() != null
                                ? interview.getApplication().getJobPosting().getTitle()
                                : "Application");
                meetingProvider.updateMeeting(zoomId,
                        new com.anvicorp.api.integration.meeting.MeetingRequest(
                                null,
                                topic,
                                interview.getScheduledAt(),
                                interview.getDurationMinutes(),
                                interview.getTimezone(),
                                interview.getPrepInstructions()));
            } catch (Exception e) {
                log.warn("[Zoom] updateMeeting failed (non-fatal) for interview {}: {}",
                        interview.getId(), e.getMessage());
            }
        }

        Interview saved = interviewRepository.save(interview);
        writeAudit("Interview", saved.getId(), "RESCHEDULE",
                actor != null ? actor.getId() : null, before, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public InterviewResponse update(UUID interviewId, UpdateInterviewRequest req, User actor) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview not found: " + interviewId));

        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw new BadRequestException(
                    "Cannot edit interview in status " + interview.getStatus() + " — only SCHEDULED is editable");
        }

        Map<String, Object> before = snapshot(interview);

        if (req.getScheduledAt() != null) interview.setScheduledAt(req.getScheduledAt());
        if (req.getDurationMinutes() != null) interview.setDurationMinutes(req.getDurationMinutes());
        if (req.getType() != null) interview.setType(req.getType());
        if (req.getMeetingUrl() != null) interview.setMeetingUrl(req.getMeetingUrl());
        if (req.getCandidateNotes() != null) interview.setCandidateNotes(req.getCandidateNotes());
        boolean interviewerChanged = false;
        if (req.getInterviewerId() != null
                && !req.getInterviewerId().equals(interview.getInterviewer().getId())) {
            User newInterviewer = userRepository.findById(req.getInterviewerId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Interviewer not found: " + req.getInterviewerId()));
            if ((newInterviewer.getRoles().contains(UserRole.INTERN) || newInterviewer.getRoles().contains(UserRole.INTERN))
                    && newInterviewer.getRoles().size() == 1) {
                throw new BadRequestException("Interviewer must be an internal user, not a candidate");
            }
            interview.setInterviewer(newInterviewer);
            interviewerChanged = true;
        }

        // Sync Zoom when the interviewer changes. Zoom S2S OAuth doesn't
        // support host swap on an existing meeting, so we follow the
        // documented delete + create workaround used by
        // ErmInterviewService.changeInterviewer. Previously, swapping the
        // interviewer left the Zoom meeting orphaned on the OLD host.
        if (interviewerChanged) {
            String oldZoomId = interview.getZoomMeetingId();
            if (oldZoomId != null && meetingProvider.isReady()) {
                try {
                    meetingProvider.deleteMeeting(oldZoomId);
                } catch (Exception e) {
                    log.warn("[Zoom] deleteMeeting failed on interviewer swap for {} (non-fatal): {}",
                            interview.getId(), e.getMessage());
                }
            }
            interview.setZoomMeetingId(null);
            interview.setZoomJoinUrl(null);
            interview.setZoomStartUrl(null);
            interview.setZoomPassword(null);
            if (meetingProvider.isReady()) {
                try {
                    User newInterviewer = interview.getInterviewer();
                    String hostId = newInterviewer.getZoomEmail() != null
                            && !newInterviewer.getZoomEmail().isBlank()
                            ? newInterviewer.getZoomEmail() : "me";
                    String topic = brand.getName() + " interview — "
                            + (interview.getApplication() != null
                                    && interview.getApplication().getJobPosting() != null
                                    ? interview.getApplication().getJobPosting().getTitle()
                                    : "Application");
                    com.anvicorp.api.integration.meeting.MeetingResponse meeting =
                            meetingProvider.createMeeting(
                                    new com.anvicorp.api.integration.meeting.MeetingRequest(
                                            hostId, topic, interview.getScheduledAt(),
                                            interview.getDurationMinutes(),
                                            interview.getTimezone(),
                                            interview.getPrepInstructions()));
                    interview.setZoomMeetingId(meeting.providerMeetingId());
                    interview.setZoomJoinUrl(meeting.joinUrl());
                    interview.setZoomStartUrl(meeting.startUrl());
                    interview.setZoomPassword(meeting.password());
                    if (interview.getMeetingUrl() == null || interview.getMeetingUrl().isBlank()) {
                        interview.setMeetingUrl(meeting.joinUrl());
                    }
                } catch (Exception e) {
                    log.warn("[Zoom] createMeeting failed on interviewer swap for {} (degraded): {}",
                            interview.getId(), e.getMessage());
                }
            }
        }

        interview = interviewRepository.save(interview);
        writeAudit("Interview", interview.getId(), "UPDATE", actor.getId(),
                before, snapshot(interview));
        return toResponse(interview);
    }

    @Transactional
    public InterviewResponse submitFeedback(UUID interviewId, SubmitFeedbackRequest req, User submitter) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview not found: " + interviewId));

        ensureCanSubmitFeedback(interview, submitter);

        if (interview.getStatus() != InterviewStatus.SCHEDULED
                && interview.getStatus() != InterviewStatus.COMPLETED) {
            throw new BadRequestException(
                    "Cannot submit feedback for interview in status " + interview.getStatus());
        }

        Map<String, Object> before = snapshot(interview);

        interview.setFeedbackOverallRating(req.getOverallRating());
        interview.setFeedbackTechnicalRating(req.getTechnicalRating());
        interview.setFeedbackCommunicationRating(req.getCommunicationRating());
        interview.setFeedbackStrengths(req.getStrengths());
        interview.setFeedbackConcerns(req.getConcerns());
        interview.setFeedbackRecommendation(req.getRecommendation());
        interview.setFeedbackSubmittedAt(Instant.now());
        interview.setFeedbackSubmittedBy(submitter.getId());
        interview.setStatus(InterviewStatus.COMPLETED);
        interview = interviewRepository.save(interview);

        Application application = interview.getApplication();
        if (application.getStatus() == ApplicationStatus.INTERVIEW_SCHEDULED) {
            // INTERVIEW_SCHEDULED → INTERVIEWED is legal in LEGAL_TRANSITIONS;
            // transitionTo writes the single STATUS_CHANGE audit row.
            applicationService.transitionTo(application, ApplicationStatus.INTERVIEWED,
                    "STATUS_CHANGE", submitter);
        }

        writeAudit("Interview", interview.getId(), "SUBMIT_FEEDBACK", submitter.getId(),
                before, snapshot(interview));
        return toResponse(interview);
    }

    /**
     * Phase 2.2 — structured scorecard submission. Writes the three dimension
     * ratings + recommendation + unified comments. The overall rating is auto-
     * computed as the rounded average of the dimensions so existing surfaces
     * (interview summary, recruiter table) keep working without a schema-only
     * change. Same submitter idempotency as {@code submitFeedback}: the assigned
     * interviewer (or ERM/ADMIN) can resubmit to overwrite their prior scorecard.
     */
    @Transactional
    public InterviewResponse submitScorecard(UUID interviewId,
                                             SubmitScorecardRequest req,
                                             User submitter) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Interview not found: " + interviewId));

        ensureCanSubmitFeedback(interview, submitter);

        if (interview.getStatus() != InterviewStatus.SCHEDULED
                && interview.getStatus() != InterviewStatus.COMPLETED) {
            throw new BadRequestException(
                    "Cannot submit scorecard for interview in status " + interview.getStatus());
        }

        Map<String, Object> before = snapshot(interview);

        int tech = req.getTechnicalRating();
        int comm = req.getCommunicationRating();
        int ps = req.getProblemSolvingRating();
        // Rounded-half-up average; min 1, max 5 — bounds are already enforced
        // by @Min/@Max on the DTO so the clamp here is belt-and-braces.
        int overall = Math.max(1, Math.min(5, (int) Math.round((tech + comm + ps) / 3.0)));

        interview.setFeedbackTechnicalRating(tech);
        interview.setFeedbackCommunicationRating(comm);
        interview.setFeedbackProblemSolvingRating(ps);
        interview.setFeedbackOverallRating(overall);
        interview.setFeedbackRecommendation(req.getRecommendation());
        interview.setFeedbackComments(req.getComments());
        interview.setFeedbackSubmittedAt(Instant.now());
        interview.setFeedbackSubmittedBy(submitter.getId());
        interview.setStatus(InterviewStatus.COMPLETED);
        interview = interviewRepository.save(interview);

        Application application = interview.getApplication();
        if (application.getStatus() == ApplicationStatus.INTERVIEW_SCHEDULED) {
            // INTERVIEW_SCHEDULED → INTERVIEWED is the only legal advance from
            // this state; the 1.1 guard rejects any other move, and the audit
            // row is written inside transitionTo.
            applicationService.transitionTo(application, ApplicationStatus.INTERVIEWED,
                    "STATUS_CHANGE", submitter);
        }

        writeAudit("Interview", interview.getId(), "SUBMIT_SCORECARD", submitter.getId(),
                before, snapshot(interview));

        // Change 4 — fire applicant acknowledgment AFTER_COMMIT. Listener
        // writes a sent_notifications row and is best-effort: listener failure
        // never rolls back the scorecard write above.
        Candidate cand = application.getCandidate();
        UUID candUserId = (cand != null && cand.getUser() != null) ? cand.getUser().getId() : null;
        String candEmail = (cand != null && cand.getUser() != null) ? cand.getUser().getEmail() : null;
        eventPublisher.publishEvent(new InterviewCompletedEvent(
                interview.getId(),
                application.getId(),
                candUserId,
                candEmail,
                interview.getFeedbackRecommendation(),
                interview.getFeedbackSubmittedAt(),
                submitter.getId()));

        return toResponse(interview);
    }

    @Transactional
    public InterviewResponse updateStatus(UUID interviewId, InterviewStatus newStatus, User actor) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview not found: " + interviewId));

        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw new BadRequestException(
                    "Cannot transition interview from " + interview.getStatus()
                            + " (only SCHEDULED interviews accept status changes)");
        }
        if (newStatus != InterviewStatus.CANCELLED
                && newStatus != InterviewStatus.NO_SHOW
                && newStatus != InterviewStatus.COMPLETED) {
            throw new BadRequestException(
                    "Invalid target status " + newStatus + " — allowed: CANCELLED, NO_SHOW, COMPLETED");
        }

        Map<String, Object> before = snapshot(interview);
        interview.setStatus(newStatus);
        interview = interviewRepository.save(interview);
        writeAudit("Interview", interview.getId(), "STATUS_CHANGE", actor.getId(),
                before, snapshot(interview));
        return toResponse(interview);
    }

    @Transactional
    public void delete(UUID interviewId, User actor) {
        Interview interview = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview not found: " + interviewId));

        if (interview.getStatus() != InterviewStatus.SCHEDULED) {
            throw new BadRequestException(
                    "Cannot delete interview in status " + interview.getStatus()
                            + " — completed/cancelled interviews are preserved for audit");
        }

        writeAudit("Interview", interview.getId(), "DELETE", actor.getId(),
                snapshot(interview), null);
        interviewRepository.delete(interview);
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<InterviewSummaryResponse> list(UUID applicationId,
                                               InterviewStatus status,
                                               UUID interviewerId,
                                               Boolean upcoming,
                                               Pageable pageable) {
        // Phase-3 fix — staff list runs through
        // {@link InterviewSpecifications#withFilters}. The previous @Query
        // bound null Instants via {@code :cutoff IS NULL OR ...} which
        // surfaced Postgres SQLSTATE 42P18 ("could not determine data type
        // of parameter $7"). Specifications add predicates only when a
        // filter is provided, so null cutoffs never reach the SQL.
        Instant now = Instant.now();
        Instant upcomingCutoff = Boolean.TRUE.equals(upcoming) ? now : null;
        Instant pastCutoff = Boolean.FALSE.equals(upcoming) ? now : null;
        var spec = com.anvicorp.api.repository.InterviewSpecifications.withFilters(
                applicationId, status, interviewerId, upcomingCutoff, pastCutoff);
        return interviewRepository.findAll(spec, pageable).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public InterviewResponse getDetail(UUID interviewId, User caller) {
        // Fetch-join application → candidate → user + jobPosting + interviewer
        // so the DTO mapper doesn't N+1 / lazy-load after this method returns.
        Interview interview = interviewRepository.findByIdWithGraph(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview not found: " + interviewId));

        if (caller == null) {
            throw new ForbiddenException("Authentication required");
        }

        Set<UserRole> roles = caller.getRoles();
        boolean privileged = roles.contains(UserRole.ERM)
                || roles.contains(UserRole.ERM)
                || roles.contains(UserRole.ERM)
                || roles.contains(UserRole.ERM)
                || roles.contains(UserRole.TRAINER);
        boolean isInterviewer = interview.getInterviewer() != null
                && caller.getId().equals(interview.getInterviewer().getId());

        if (privileged || isInterviewer) {
            return toResponse(interview);
        }

        if ((roles.contains(UserRole.INTERN) || roles.contains(UserRole.INTERN)) && belongsToCandidate(interview, caller)) {
            // Candidates technically use /me, but if they hit this directly we hide
            // feedback by 404-ing rather than 200 with sanitized payload, to be safe.
            throw new ResourceNotFoundException("Interview not found: " + interviewId);
        }

        throw new ForbiddenException("Not allowed to view this interview");
    }

    /**
     * Phase 2 — applicant-safe detail. Returns the
     * {@link CandidateInterviewResponse} (no zoomStartUrl, no internalNotes)
     * when the caller is the applicant the interview belongs to. Throws
     * 403 / 404 otherwise.
     */
    @Transactional(readOnly = true)
    public CandidateInterviewResponse getDetailForCandidate(UUID interviewId, User caller) {
        Interview interview = interviewRepository.findByIdWithGraph(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview not found: " + interviewId));
        if (caller == null) {
            throw new ForbiddenException("Authentication required");
        }
        if (!belongsToCandidate(interview, caller)) {
            throw new ForbiddenException("Not allowed to view this interview");
        }
        return toCandidateResponse(interview);
    }

    /**
     * Phase 2.2 — latest submitted scorecard for the application, used by the
     * recruiter review screen to surface the recommendation + scores so the
     * advance-vs-reject decision is informed. Returns {@code null} when no
     * interview on this application has feedback yet (the review screen
     * renders an empty state for that case).
     */
    @Transactional(readOnly = true)
    public InterviewScorecardSummary findLatestScorecardForApplication(UUID applicationId,
                                                                       User caller) {
        ensureStaffCanRead(caller);
        // findByApplicationIdOrderByScheduledAtDesc already orders DESC by
        // scheduledAt — we walk that list and take the first one with feedback.
        List<Interview> interviews = interviewRepository
                .findByApplicationIdOrderByScheduledAtDesc(applicationId);
        for (Interview i : interviews) {
            if (i.getFeedbackSubmittedAt() != null) {
                return toScorecardSummary(i);
            }
        }
        return null;
    }

    private InterviewScorecardSummary toScorecardSummary(Interview i) {
        return InterviewScorecardSummary.builder()
                .interviewId(i.getId())
                .applicationId(i.getApplication() != null ? i.getApplication().getId() : null)
                .technicalRating(i.getFeedbackTechnicalRating())
                .communicationRating(i.getFeedbackCommunicationRating())
                .problemSolvingRating(i.getFeedbackProblemSolvingRating())
                .overallRating(i.getFeedbackOverallRating())
                .recommendation(i.getFeedbackRecommendation())
                .comments(firstNonBlank(i.getFeedbackComments(), i.getFeedbackStrengths()))
                .submittedByName(lookupUserName(i.getFeedbackSubmittedBy()))
                .submittedAt(i.getFeedbackSubmittedAt())
                .scheduledAt(i.getScheduledAt())
                .build();
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private void ensureStaffCanRead(User caller) {
        if (caller == null || caller.getRoles() == null) {
            throw new ForbiddenException("Authentication required");
        }
        boolean allowed = caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.TRAINER);
        if (!allowed) {
            throw new ForbiddenException("Not allowed to view interview scorecards");
        }
    }

    @Transactional(readOnly = true)
    public List<CandidateInterviewResponse> listForCandidate(User candidate) {
        return interviewRepository.findAllForCandidateUser(candidate.getId())
                .stream()
                .map(this::toCandidateResponse)
                .toList();
    }

    // ── Permission helpers ──────────────────────────────────────────────────

    private void ensureCanSubmitFeedback(Interview interview, User submitter) {
        if (submitter == null) {
            throw new ForbiddenException("Authentication required");
        }
        Set<UserRole> roles = submitter.getRoles();
        boolean privileged = roles.contains(UserRole.ERM);
        boolean isInterviewer = interview.getInterviewer() != null
                && submitter.getId().equals(interview.getInterviewer().getId());
        if (!privileged && !isInterviewer) {
            throw new ForbiddenException("Only the assigned interviewer or OPERATIONS can submit feedback");
        }
    }

    private boolean belongsToCandidate(Interview interview, User candidate) {
        Application app = interview.getApplication();
        if (app == null || app.getCandidate() == null || app.getCandidate().getUser() == null) {
            return false;
        }
        return app.getCandidate().getUser().getId().equals(candidate.getId());
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private InterviewResponse toResponse(Interview i) {
        Application app = i.getApplication();
        Candidate c = app != null ? app.getCandidate() : null;
        User candidateUser = c != null ? c.getUser() : null;
        JobPosting jp = app != null ? app.getJobPosting() : null;
        User interviewer = i.getInterviewer();

        return InterviewResponse.builder()
                .id(i.getId())
                .applicationId(app != null ? app.getId() : null)
                .applicationStatus(app != null ? app.getStatus() : null)
                .candidateName(candidateUser != null ? candidateUser.getFullName() : null)
                .candidateEmail(candidateUser != null ? candidateUser.getEmail() : null)
                .jobPostingTitle(jp != null ? jp.getTitle() : null)
                .interviewerName(interviewer != null ? interviewer.getFullName() : null)
                .interviewerId(interviewer != null ? interviewer.getId() : null)
                .scheduledAt(i.getScheduledAt())
                .durationMinutes(i.getDurationMinutes())
                .type(i.getType())
                .status(i.getStatus())
                .meetingUrl(i.getMeetingUrl())
                .candidateNotes(i.getCandidateNotes())
                .feedbackOverallRating(i.getFeedbackOverallRating())
                .feedbackTechnicalRating(i.getFeedbackTechnicalRating())
                .feedbackCommunicationRating(i.getFeedbackCommunicationRating())
                .feedbackProblemSolvingRating(i.getFeedbackProblemSolvingRating())
                .feedbackStrengths(i.getFeedbackStrengths())
                .feedbackConcerns(i.getFeedbackConcerns())
                .feedbackComments(i.getFeedbackComments())
                .feedbackRecommendation(i.getFeedbackRecommendation())
                .feedbackSubmittedAt(i.getFeedbackSubmittedAt())
                .feedbackSubmittedByName(lookupUserName(i.getFeedbackSubmittedBy()))
                .createdAt(i.getCreatedAt())
                .createdByName(lookupUserName(i.getCreatedBy()))
                // Phase 2 doc-spec fields. ERM/MANAGER/SUPER_ADMIN-facing DTO
                // includes zoom_start_url + internal_notes; the applicant
                // DTO (toCandidateResponse) deliberately omits both.
                .timezone(i.getTimezone())
                .zoomMeetingId(i.getZoomMeetingId())
                .zoomJoinUrl(i.getZoomJoinUrl())
                .zoomStartUrl(i.getZoomStartUrl())
                .zoomPassword(i.getZoomPassword())
                .decision(i.getDecision())
                .applicantVisibleNotes(i.getApplicantVisibleNotes())
                .internalNotes(i.getInternalNotes())
                .prepInstructions(i.getPrepInstructions())
                .build();
    }

    private InterviewSummaryResponse toSummary(Interview i) {
        Application app = i.getApplication();
        Candidate c = app != null ? app.getCandidate() : null;
        User candidateUser = c != null ? c.getUser() : null;
        JobPosting jp = app != null ? app.getJobPosting() : null;
        User interviewer = i.getInterviewer();
        return InterviewSummaryResponse.builder()
                .id(i.getId())
                .candidateName(candidateUser != null ? candidateUser.getFullName() : null)
                .jobPostingTitle(jp != null ? jp.getTitle() : null)
                .interviewerName(interviewer != null ? interviewer.getFullName() : null)
                .scheduledAt(i.getScheduledAt())
                .durationMinutes(i.getDurationMinutes())
                .type(i.getType())
                .status(i.getStatus())
                .hasFeedback(i.getFeedbackSubmittedAt() != null)
                .build();
    }

    private CandidateInterviewResponse toCandidateResponse(Interview i) {
        Application app = i.getApplication();
        JobPosting jp = app != null ? app.getJobPosting() : null;
        User interviewer = i.getInterviewer();
        // Applicant-safe — by construction never includes zoomStartUrl or
        // internalNotes. Adding host-only state here is a bug; route those
        // through InterviewResponse instead.
        return CandidateInterviewResponse.builder()
                .id(i.getId())
                .applicationId(app != null ? app.getId() : null)
                .jobPostingTitle(jp != null ? jp.getTitle() : null)
                .scheduledAt(i.getScheduledAt())
                .durationMinutes(i.getDurationMinutes())
                .timezone(i.getTimezone())
                .type(i.getType())
                .status(i.getStatus())
                .meetingUrl(i.getZoomJoinUrl() != null ? i.getZoomJoinUrl() : i.getMeetingUrl())
                .zoomJoinUrl(i.getZoomJoinUrl())
                .zoomPassword(i.getZoomPassword())
                .candidateNotes(i.getCandidateNotes())
                .prepInstructions(i.getPrepInstructions())
                .interviewerName(interviewer != null ? interviewer.getFullName() : null)
                .decision(i.getDecision())
                .applicantVisibleNotes(i.getApplicantVisibleNotes())
                .build();
    }

    private String lookupUserName(UUID userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(User::getFullName).orElse(null);
    }

    // ── Audit log ───────────────────────────────────────────────────────────

    private Map<String, Object> snapshot(Interview i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("applicationId", i.getApplication() != null ? i.getApplication().getId() : null);
        m.put("interviewerId", i.getInterviewer() != null ? i.getInterviewer().getId() : null);
        m.put("scheduledAt", i.getScheduledAt());
        m.put("durationMinutes", i.getDurationMinutes());
        m.put("type", i.getType());
        m.put("status", i.getStatus());
        m.put("meetingUrl", i.getMeetingUrl());
        m.put("candidateNotes", i.getCandidateNotes());
        m.put("feedbackOverallRating", i.getFeedbackOverallRating());
        m.put("feedbackTechnicalRating", i.getFeedbackTechnicalRating());
        m.put("feedbackCommunicationRating", i.getFeedbackCommunicationRating());
        m.put("feedbackProblemSolvingRating", i.getFeedbackProblemSolvingRating());
        m.put("feedbackStrengths", i.getFeedbackStrengths());
        m.put("feedbackConcerns", i.getFeedbackConcerns());
        m.put("feedbackComments", i.getFeedbackComments());
        m.put("feedbackRecommendation", i.getFeedbackRecommendation());
        m.put("feedbackSubmittedAt", i.getFeedbackSubmittedAt());
        m.put("feedbackSubmittedBy", i.getFeedbackSubmittedBy());
        return m;
    }

    private void writeAudit(String entityType, UUID entityId, String action, UUID userId,
                            Map<String, Object> before, Map<String, Object> after) {
        AuditLog entry = AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .userId(userId)
                .beforeJson(serialize(before))
                .afterJson(serialize(after))
                .build();
        auditLogRepository.save(entry);
    }

    private String serialize(Map<String, Object> snapshot) {
        if (snapshot == null) return null;
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit snapshot: {}", e.getMessage());
            return new HashMap<>(snapshot).toString();
        }
    }
}
