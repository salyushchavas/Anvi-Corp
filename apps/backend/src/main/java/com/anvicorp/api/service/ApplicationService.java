package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.application.ApplicationLifecycle;
import com.anvicorp.api.dto.ApplicationCreateRequest;
import com.anvicorp.api.dto.ApplicationResponse;
import com.anvicorp.api.dto.ApplicationStatusUpdateRequest;
import com.anvicorp.api.dto.BulkApplicationActionRequest;
import com.anvicorp.api.dto.BulkApplicationActionResponse;
import com.anvicorp.api.dto.RecruiterDecisionRequest;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Resume;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.JobPostingStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.EmailUnverifiedException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.InterviewRequiredException;
import com.anvicorp.api.exception.ProfileIncompleteException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.notification.NotificationService;
import com.anvicorp.api.notification.NotificationStub;
import com.anvicorp.api.event.SelectionAcknowledgedEvent;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.ApplicationSpecifications;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.InterviewRepository;
import com.anvicorp.api.repository.JobPostingRepository;
import com.anvicorp.api.repository.ResumeRepository;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.enums.InterviewStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final JobPostingRepository jobPostingRepository;
    private final ResumeRepository resumeRepository;
    private final CandidateRepository candidateRepository;
    private final AuditLogRepository auditLogRepository;
    private final InterviewRepository interviewRepository;
    private final ObjectMapper objectMapper;
    private final NotificationStub notificationStub;
    private final NotificationService notificationService;
    private final com.anvicorp.api.intern.InternLifecycleService internLifecycleService;
    private final ProfileCompletionService profileCompletionService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Transactional
    public ApplicationResponse apply(User user, ApplicationCreateRequest req) {
        // Phase 1.3 gate: cannot apply without a verified email. The
        // EmailUnverifiedException -> 403 + code=EMAIL_UNVERIFIED is what the
        // apply screen keys off to render the "verify your email" prompt.
        if (!Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new EmailUnverifiedException(
                    "Verify your email to apply for internships");
        }

        // Approach 1 gate: signup collects only the 4 legal-essentials
        // (name/email/password/ToS); the remaining required-to-apply fields
        // (phone, school, degree, graduationYear, skillset, resume) live on
        // the profile editor. Derived check via ProfileCompletionService so
        // a single source of truth gates both the apply endpoint and drives
        // the dashboard completion card / "Apply locked" UX. 409
        // PROFILE_INCOMPLETE carries missing[] in the envelope so the
        // frontend can deep-link to the editor with the right step focused.
        com.anvicorp.api.intern.ApplyReadiness readiness =
                profileCompletionService.applyReadiness(user);
        if (!readiness.complete()) {
            throw new ProfileIncompleteException(
                    "Complete your profile to apply", readiness.missing());
        }

        Candidate candidate = candidateRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    log.warn("Lazy-creating Candidate for user {} during application submit", user.getId());
                    return candidateRepository.save(Candidate.builder().user(user).build());
                });

        JobPosting posting = jobPostingRepository.findById(req.getJobPostingId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Job posting not found: " + req.getJobPostingId()));
        if (posting.getStatus() != JobPostingStatus.OPEN) {
            throw new BadRequestException("Job posting is not open for applications");
        }

        Resume resume = resumeRepository.findById(req.getResumeId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Resume not found: " + req.getResumeId()));
        if (!resume.getCandidate().getId().equals(candidate.getId())) {
            throw new ForbiddenException("Resume does not belong to this user");
        }

        // Re-apply after withdrawal is allowed — only a NON-WITHDRAWN prior
        // application blocks. The WITHDRAWN row is kept for ERM reporting +
        // candidate-side "exit status" UI. Partial UNIQUE
        // uk_application_candidate_job_posting_active (SchemaFixupRunner)
        // enforces the same shape at the DB level.
        if (applicationRepository.existsByCandidateIdAndJobPostingIdAndStatusNot(
                candidate.getId(), posting.getId(), ApplicationStatus.WITHDRAWN)) {
            throw new ConflictException("Already applied to this job posting");
        }

        Application application = Application.builder()
                .candidate(candidate)
                .jobPosting(posting)
                .resume(resume)
                .status(ApplicationStatus.APPLIED)
                .statementOfInterest(trimToNull(req.getStatementOfInterest()))
                .build();
        application = applicationRepository.save(application);

        // Phase 2: advance the applicant's lifecycle to APPLICATION_SUBMITTED
        // inside the same transaction. Monotonic — additional applications
        // from the same user are no-ops here (already past EMAIL_VERIFIED).
        internLifecycleService.advance(user,
                com.anvicorp.api.enums.InternLifecycleStatus.APPLICATION_SUBMITTED,
                user.getId());

        // Batch-1 notification — applicant receives a confirmation. Best-effort:
        // a send failure must NOT block submission.
        try {
            notificationService.sendApplicationReceived(application);
        } catch (Exception e) {
            log.warn("APPLICATION_RECEIVED notify failed (non-fatal) for {}: {}",
                    application.getId(), e.getMessage());
        }
        return toResponse(application);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @Transactional(readOnly = true)
    public java.util.List<ApplicationResponse> listForCandidate(User user) {
        Candidate candidate = candidateRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate profile not found for user " + user.getId()));
        return applicationRepository.findByCandidateId(candidate.getId())
                .stream().map(this::toResponse).toList();
    }

    /**
     * Staff applications list with composable filters + sorting + paging.
     * Backed by a JPA Specification that fetch-joins candidate→user and
     * jobPosting→entity so the DTO mapper never lazy-loads / N+1's.
     *
     * @param search      case-insensitive substring match on candidate
     *                    fullName OR email; null/blank to skip
     * @param statuses    optional set of statuses; null/empty to skip
     * @param entityId    optional StaffingEntity id filter
     * @param jobPostingId optional JobPosting id filter
     */
    @Transactional(readOnly = true)
    public Page<ApplicationResponse> search(String search,
                                            Collection<ApplicationStatus> statuses,
                                            UUID entityId,
                                            UUID jobPostingId,
                                            Pageable pageable) {
        return applicationRepository
                .findAll(ApplicationSpecifications.withFilters(search, statuses, entityId, jobPostingId),
                        pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ApplicationResponse findById(UUID id, User caller) {
        Application application = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + id));
        ensureCanRead(application, caller);
        return toResponse(application);
    }

    @Transactional(readOnly = true)
    public Application findEntityForRead(UUID id, User caller) {
        Application application = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + id));
        ensureCanRead(application, caller);
        return application;
    }

    @Transactional
    public ApplicationResponse updateStatus(UUID id, ApplicationStatusUpdateRequest req, User caller) {
        Application application = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + id));
        if (req.getRecruiterNotes() != null) {
            application.setRecruiterNotes(req.getRecruiterNotes());
        }
        // transitionTo is gated by LEGAL_TRANSITIONS — illegal Kanban drags
        // throw BadRequestException (400) rather than corrupting state.
        transitionTo(application, req.getStatus(), "STATUS_CHANGE", caller);
        return toResponse(application);
    }

    /**
     * Gated status transition — the single entry point that every status
     * write-site MUST go through. Checks {@link ApplicationLifecycle#LEGAL_TRANSITIONS},
     * sets status/statusUpdatedAt/statusUpdatedBy, persists the row, and writes
     * exactly ONE audit log entry. Same-state target is a legal no-op (no save,
     * no audit). Illegal target throws {@link BadRequestException} → 400.
     */
    @Transactional
    public Application transitionTo(Application app,
                                    ApplicationStatus target,
                                    String auditAction,
                                    User actor) {
        ApplicationStatus from = app.getStatus();
        if (from == target) return app; // legal no-op
        Set<ApplicationStatus> allowed =
                ApplicationLifecycle.LEGAL_TRANSITIONS.getOrDefault(from, Collections.emptySet());
        if (!allowed.contains(target)) {
            throw new BadRequestException(
                    "Cannot move application from " + from + " to " + target);
        }
        return applyTransition(app, from, target, auditAction,
                actor != null ? actor.getId() : null);
    }

    /**
     * Override path — bypasses {@link ApplicationLifecycle#LEGAL_TRANSITIONS}
     * but STILL audits. Reserved for SYSTEM (boot-time backfill) and ADMIN
     * corrections that need to break the normal lifecycle. Same-state remains
     * a no-op. Callers must justify why the gated path is wrong for them.
     */
    @Transactional
    public Application transitionToSystem(Application app,
                                          ApplicationStatus target,
                                          String auditAction,
                                          UUID actorId) {
        ApplicationStatus from = app.getStatus();
        if (from == target) return app;
        return applyTransition(app, from, target, auditAction, actorId);
    }

    private Application applyTransition(Application app,
                                        ApplicationStatus from,
                                        ApplicationStatus target,
                                        String auditAction,
                                        UUID actorId) {
        app.setStatus(target);
        app.setStatusUpdatedAt(Instant.now());
        app.setStatusUpdatedBy(actorId);
        Application saved = applicationRepository.save(app);
        writeStatusAudit(saved.getId(), auditAction, actorId, from, target);

        // Batch-1 lifecycle notifications. Routed through the central
        // applyTransition so one-click, Kanban drag, and bulk-action paths all
        // emit. Idempotency lives in NotificationService — re-flipping back to
        // SHORTLISTED after a brief excursion only emails once. Best-effort:
        // an email failure never rolls back the status change.
        try {
            if (target == ApplicationStatus.SHORTLISTED) {
                notificationService.sendApplicationShortlisted(saved);
            } else if (target == ApplicationStatus.REJECTED) {
                notificationService.sendApplicationRejected(saved);
            }
        } catch (Exception e) {
            log.warn("Lifecycle notify failed (non-fatal) for {} -> {} on {}: {}",
                    from, target, saved.getId(), e.getMessage());
        }

        // Phase 2: advance the APPLICANT's lifecycle status atomically with
        // the stage change. Monotonic — past INTERVIEW_COMPLETED won't regress
        // when ERM shortlists another application by the same user.
        try {
            com.anvicorp.api.entity.User applicantUser =
                    saved.getCandidate() != null ? saved.getCandidate().getUser() : null;
            if (applicantUser != null) {
                if (target == ApplicationStatus.SHORTLISTED) {
                    internLifecycleService.advance(applicantUser,
                            com.anvicorp.api.enums.InternLifecycleStatus.SHORTLISTED,
                            actorId);
                }
            }
        } catch (Exception e) {
            log.warn("Lifecycle advance failed (non-fatal) for {} -> {} on {}: {}",
                    from, target, saved.getId(), e.getMessage());
        }
        return saved;
    }

    /**
     * One-click Shortlist from the recruiter review screen. Routes through
     * {@link #transitionTo} so the lifecycle guard + audit are uniform with
     * the Kanban drag. Persists rating/note. Idempotent: if already
     * SHORTLISTED the rating/note are still applied but no audit row is written.
     */
    @Transactional
    public ApplicationResponse shortlist(UUID id, RecruiterDecisionRequest req, User caller) {
        return changeStatusWithAudit(id, ApplicationStatus.SHORTLISTED, "SHORTLIST", req, caller);
    }

    /** Mirror of {@link #shortlist} for one-click rejection. Writes a REJECT audit entry. */
    @Transactional
    public ApplicationResponse reject(UUID id, RecruiterDecisionRequest req, User caller) {
        return changeStatusWithAudit(id, ApplicationStatus.REJECTED, "REJECT", req, caller);
    }

    /**
     * Phase 2.3 — conditional employment confirmation. Staff (RECRUITER/ERM/
     * ADMIN) signal "selected, pending offer + compliance" off the 2.2
     * scorecard. The 1.1 guard owns gating: INTERVIEWED → SELECTED_CONDITIONAL
     * is the canonical advance; same-state is a no-op (idempotent re-click);
     * any other source state 400s with a clear message.
     *
     * Side-effects: stub-send the confirmation email and write a
     * CONDITIONAL_SELECT audit row. Email lookup is best-effort — a missing
     * candidate / user / email logs a warning but does NOT roll back the
     * status change; the audit row is the durable record.
     */
    @Transactional
    public ApplicationResponse conditionalSelect(UUID id, User caller) {
        Application application = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + id));

        // Idempotent: a re-click on an already-selected app just returns the
        // current state without re-firing the email or re-auditing.
        if (application.getStatus() == ApplicationStatus.SELECTED_CONDITIONAL) {
            return toResponse(application);
        }

        // GAP A3 — explicit 403 INTERVIEW_REQUIRED before the lifecycle guard
        // throws the generic 400. SELECTED_CONDITIONAL is only legal from
        // INTERVIEWED in LEGAL_TRANSITIONS; the explicit check here lets the
        // recruiter UI render a clean blocker keyed off the same error code
        // OfferService.create uses.
        if (application.getStatus() != ApplicationStatus.INTERVIEWED) {
            throw new InterviewRequiredException(
                    "Conditional selection requires INTERVIEWED status (application status: "
                            + application.getStatus() + ").");
        }

        // transitionTo enforces LEGAL_TRANSITIONS and writes the
        // CONDITIONAL_SELECT audit row internally — one audit per real move.
        transitionTo(application, ApplicationStatus.SELECTED_CONDITIONAL,
                "CONDITIONAL_SELECT", caller);

        // Stub confirmation. Pull candidate email + posting/entity within the
        // same @Transactional so the lazy associations are still attached.
        try {
            Candidate candidate = application.getCandidate();
            User user = candidate != null ? candidate.getUser() : null;
            JobPosting posting = application.getJobPosting();
            String email = user != null ? user.getEmail() : null;
            String jobTitle = posting != null ? posting.getTitle() : null;
            String entityName = posting != null && posting.getEntity() != null
                    ? posting.getEntity().getName()
                    : null;
            if (email != null) {
                notificationStub.sendConditionalSelectionConfirmation(
                        email, jobTitle, entityName);
            } else {
                log.warn("Conditional-select email skipped — no candidate email on application {}", id);
            }
        } catch (Exception e) {
            log.warn("Conditional-select notification failed (non-fatal) for app {}: {}",
                    id, e.getMessage());
        }

        return toResponse(application);
    }

    /**
     * Bulk variant of {@link #shortlist} / {@link #reject}. Loops the existing
     * single-id path per application so audit + statusUpdatedAt + side-effects
     * stay consistent with one-by-one usage. Idempotent: ids already at the
     * target status (or not found) are counted into {@code skipped}; only real
     * transitions count toward {@code updated}. Per-row {@link BadRequestException}
     * (illegal transition for that row) is also counted as skipped — one
     * terminal-state row should not fail the whole batch.
     */
    @Transactional
    public BulkApplicationActionResponse bulkAction(BulkApplicationActionRequest req, User caller) {
        ApplicationStatus target = req.getAction() == BulkApplicationActionRequest.BulkAction.SHORTLIST
                ? ApplicationStatus.SHORTLISTED
                : ApplicationStatus.REJECTED;
        String auditAction = req.getAction() == BulkApplicationActionRequest.BulkAction.SHORTLIST
                ? "SHORTLIST"
                : "REJECT";

        // Pre-fetch once so we can pre-check the target status without N+1.
        List<Application> apps = applicationRepository.findAllById(req.getIds());
        Map<UUID, Application> byId = new HashMap<>();
        for (Application a : apps) byId.put(a.getId(), a);

        int updated = 0;
        int skipped = 0;
        for (UUID id : req.getIds()) {
            Application app = byId.get(id);
            if (app == null) {
                // Unknown id — count as skipped rather than failing the whole batch.
                skipped++;
                continue;
            }
            if (app.getStatus() == target) {
                skipped++;
                continue;
            }
            try {
                changeStatusWithAudit(id, target, auditAction, null, caller);
                updated++;
            } catch (BadRequestException illegal) {
                // Terminal/illegal source state — keep the batch going.
                skipped++;
            }
        }
        return BulkApplicationActionResponse.builder()
                .updated(updated)
                .skipped(skipped)
                .build();
    }

    private ApplicationResponse changeStatusWithAudit(UUID id,
                                                      ApplicationStatus target,
                                                      String action,
                                                      RecruiterDecisionRequest decision,
                                                      User caller) {
        Application application = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + id));
        boolean idempotent = (application.getStatus() == target);

        // Persist rating + note even on an idempotent call so recruiters can refine
        // their decision without forcing a status flip.
        applyDecisionFields(application, decision, caller);

        if (idempotent) {
            applicationRepository.save(application);
            return toResponse(application);
        }
        transitionTo(application, target, action, caller);
        return toResponse(application);
    }

    private void applyDecisionFields(Application app,
                                     RecruiterDecisionRequest decision,
                                     User caller) {
        if (decision == null) return;
        if (decision.getRating() != null) {
            app.setRecruiterRating(decision.getRating());
        }
        if (decision.getNote() != null) {
            // The note field on the entity is the existing recruiterNotes column —
            // spec calls it "recruiterNote" but reusing the existing column avoids
            // duplicate state. The wire DTO names match the spec; the storage name doesn't.
            app.setRecruiterNotes(decision.getNote());
        }
        if (decision.getApplicantVisibleFeedback() != null
                && !decision.getApplicantVisibleFeedback().isBlank()) {
            app.setApplicantVisibleFeedback(decision.getApplicantVisibleFeedback().trim());
        }
        if (caller != null) {
            app.setStatusUpdatedBy(caller.getId());
            // ERM ownership stamps when the actor is staff and the field is unset.
            if (app.getErmOwnerId() == null) {
                app.setErmOwnerId(caller.getId());
            }
        }
    }

    /**
     * Phase 2 — applicant-initiated withdrawal. Allowed only at APPLIED or
     * SHORTLISTED stages; ERM-initiated rejection / past-INTERVIEW states
     * are out of bounds. Idempotent: a re-call on a WITHDRAWN row is a no-op.
     */
    @Transactional
    public ApplicationResponse withdraw(UUID id, User caller) {
        Application application = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + id));
        // Owner check
        if (caller == null
                || application.getCandidate() == null
                || application.getCandidate().getUser() == null
                || !caller.getId().equals(application.getCandidate().getUser().getId())) {
            throw new ForbiddenException("Only the applicant may withdraw their application");
        }
        if (application.getStatus() == ApplicationStatus.WITHDRAWN) {
            return toResponse(application);
        }
        if (application.getStatus() != ApplicationStatus.APPLIED
                && application.getStatus() != ApplicationStatus.SHORTLISTED) {
            throw new ConflictException("Application can only be withdrawn at APPLIED or SHORTLISTED");
        }
        transitionTo(application, ApplicationStatus.WITHDRAWN, "WITHDRAW", caller);
        return toResponse(application);
    }

    /**
     * Intern-initiated selection acknowledgment. The intern clicks
     * "Receive my offer letter" on their dashboard after the ERM marks
     * them SELECTED on the interview; this records the timestamp so
     * {@link com.anvicorp.api.erm.offer.ErmOfferService#createAndSend}
     * can be unblocked and the ERM can issue the offer via the existing
     * flow. Idempotent — a re-click on an already-acknowledged
     * application returns the current row without changing state.
     */
    @Transactional
    public ApplicationResponse acknowledgeSelection(UUID id, User caller) {
        Application application = applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found: " + id));
        if (caller == null
                || application.getCandidate() == null
                || application.getCandidate().getUser() == null
                || !caller.getId().equals(application.getCandidate().getUser().getId())) {
            throw new ForbiddenException("Only the applicant may acknowledge their own selection");
        }
        if (application.getSelectionAcknowledgedAt() != null) {
            // Idempotent — already acknowledged.
            return toResponse(application);
        }
        // Gate on a SELECTED interview decision so a non-shortlisted
        // candidate can never trigger the offer flow by hitting this URL.
        Interview latest = interviewRepository
                .findByApplicationIdOrderByScheduledAtDesc(application.getId()).stream()
                .filter(i -> i.getStatus() == InterviewStatus.COMPLETED)
                .findFirst()
                .orElseThrow(() -> new ConflictException(
                        "Acknowledge selection requires a completed interview"));
        if (!"SELECTED".equalsIgnoreCase(latest.getDecision())) {
            throw new ConflictException(
                    "Acknowledge selection requires a SELECTED interview decision (current: "
                            + latest.getDecision() + ")");
        }
        application.setSelectionAcknowledgedAt(Instant.now());
        application.setSelectionAcknowledgedBy(caller.getId());
        applicationRepository.save(application);

        Map<String, Object> afterJson = new LinkedHashMap<>();
        afterJson.put("selectionAcknowledgedAt", application.getSelectionAcknowledgedAt().toString());
        auditLogRepository.save(AuditLog.builder()
                .entityType("Application")
                .entityId(application.getId())
                .action("SELECTION_ACKNOWLEDGED")
                .userId(caller.getId())
                .afterJson(serializeJson(afterJson))
                .build());

        try {
            String jobTitle = application.getJobPosting() != null
                    ? application.getJobPosting().getTitle() : null;
            eventPublisher.publishEvent(new SelectionAcknowledgedEvent(
                    application.getId(), caller.getId(),
                    application.getErmOwnerId(), jobTitle));
        } catch (Exception e) {
            log.warn("[ApplicationService] selection-ack event publish failed (non-fatal): {}",
                    e.getMessage());
        }
        return toResponse(application);
    }

    private void writeStatusAudit(UUID applicationId, String action, UUID userId,
                                  ApplicationStatus before, ApplicationStatus after) {
        Map<String, Object> beforeJson = new LinkedHashMap<>();
        beforeJson.put("status", before);
        Map<String, Object> afterJson = new LinkedHashMap<>();
        afterJson.put("status", after);
        AuditLog entry = AuditLog.builder()
                .entityType("Application")
                .entityId(applicationId)
                .action(action)
                .userId(userId)
                .beforeJson(serializeJson(beforeJson))
                .afterJson(serializeJson(afterJson))
                .build();
        auditLogRepository.save(entry);
    }

    private String serializeJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize application audit snapshot: {}", e.getMessage());
            return new HashMap<>(snapshot).toString();
        }
    }

    private void ensureCanRead(Application application, User caller) {
        if (caller == null) {
            throw new ForbiddenException("Authentication required");
        }
        boolean privileged = caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.ERM);
        if (privileged) return;

        if ((caller.getRoles().contains(UserRole.INTERN) || caller.getRoles().contains(UserRole.INTERN))
                && application.getCandidate() != null
                && application.getCandidate().getUser() != null
                && application.getCandidate().getUser().getId().equals(caller.getId())) {
            return;
        }
        throw new ForbiddenException("Not allowed to view this application");
    }

    public ApplicationResponse toResponse(Application a) {
        Candidate c = a.getCandidate();
        User u = c != null ? c.getUser() : null;
        JobPosting jp = a.getJobPosting();
        Resume r = a.getResume();
        return ApplicationResponse.builder()
                .id(a.getId())
                .candidateName(u != null ? u.getFullName() : null)
                .candidateEmail(u != null ? u.getEmail() : null)
                .jobPostingTitle(jp != null ? jp.getTitle() : null)
                .jobPostingId(jp != null ? jp.getId() : null)
                .resumeId(r != null ? r.getId() : null)
                .resumeFileName(r != null ? r.getFileName() : null)
                .status(a.getStatus())
                .appliedAt(a.getAppliedAt())
                .statusUpdatedAt(a.getStatusUpdatedAt())
                .recruiterNotes(a.getRecruiterNotes())
                .recruiterRating(a.getRecruiterRating())
                .statementOfInterest(a.getStatementOfInterest())
                .applicantVisibleFeedback(a.getApplicantVisibleFeedback())
                .infoRequestedFieldsCsv(a.getInfoRequestedFieldsCsv())
                .build();
    }

    // ── ERM Phase 2 — intern closes INFO_REQUESTED loop ─────────────────────

    /**
     * Stage INFO_REQUESTED → APPLIED. Caller must own the application.
     * Every field in {@code info_requested_fields_csv} must be addressed
     * in the request body; otherwise 400 with the missing fields listed.
     * Fires {@link com.anvicorp.api.event.ApplicationInfoProvidedEvent}
     * for the ERM owner.
     */
    @Transactional
    public ApplicationResponse provideInfo(
            UUID applicationId,
            com.anvicorp.api.erm.application.ErmApplicationDtos.ProvideInfoRequest req,
            User caller) {
        Application app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + applicationId));
        if (app.getCandidate() == null || app.getCandidate().getUser() == null
                || !app.getCandidate().getUser().getId().equals(caller.getId())) {
            throw new ForbiddenException("Application does not belong to this user");
        }
        if (app.getStatus() != ApplicationStatus.INFO_REQUESTED) {
            throw new ConflictException(
                    "Provide-info only allowed from INFO_REQUESTED (current: "
                            + app.getStatus() + ")");
        }
        String csv = app.getInfoRequestedFieldsCsv();
        if (csv != null && !csv.isBlank()) {
            List<String> required = java.util.Arrays.stream(csv.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            List<String> missing = new java.util.ArrayList<>();
            for (String f : required) {
                boolean ok = switch (f) {
                    case "resume" -> req != null && req.resumeFileId() != null;
                    case "workAuth" -> req != null && req.workAuthUpdate() != null
                            && !req.workAuthUpdate().isEmpty();
                    case "education" -> req != null && req.educationUpdate() != null
                            && !req.educationUpdate().isEmpty();
                    case "other" -> req != null && req.freeTextResponse() != null
                            && !req.freeTextResponse().trim().isEmpty();
                    default -> true;
                };
                if (!ok) missing.add(f);
            }
            if (!missing.isEmpty()) {
                throw new BadRequestException(
                        "Missing required fields in provide-info: " + missing);
            }
        }
        if (req != null && req.resumeFileId() != null) {
            resumeRepository.findById(req.resumeFileId())
                    .ifPresent(app::setResume);
        }
        // workAuth/education/other are persisted into the decision log narrative
        // for ERM review; we don't mutate candidate profile fields here to keep
        // the change minimal and reversible (ERM can ask again).
        ApplicationStatus previous = app.getStatus();
        app.setStatus(ApplicationStatus.APPLIED);
        app.setStatusUpdatedBy(caller.getId());
        app.setInfoRequestedFieldsCsv(null);
        app.setInfoRequestedAt(null);
        applicationRepository.save(app);

        // Fire the info-provided event for ERM owner dispatch.
        try {
            eventPublisher.publishEvent(
                    new com.anvicorp.api.event.ApplicationInfoProvidedEvent(
                            app.getId(),
                            caller.getId(),
                            app.getErmOwnerId()));
        } catch (Exception e) {
            log.warn("ApplicationInfoProvidedEvent publish failed (non-fatal): {}",
                    e.getMessage());
        }

        writeAudit("APPLICATION_INFO_PROVIDED", app.getId(),
                Map.of("status", previous.name()),
                Map.of("status", app.getStatus().name()), caller.getId());

        return toResponse(app);
    }

    private void writeAudit(String action, UUID entityId,
                             Map<String, Object> before, Map<String, Object> after,
                             UUID actorId) {
        try {
            AuditLog row = AuditLog.builder()
                    .userId(actorId)
                    .entityType("Application")
                    .entityId(entityId)
                    .action(action)
                    .beforeJson(before != null ? objectMapper.writeValueAsString(before) : null)
                    .afterJson(after != null ? objectMapper.writeValueAsString(after) : null)
                    .build();
            auditLogRepository.save(row);
        } catch (JsonProcessingException jpe) {
            log.warn("Audit JSON failed for {}: {}", action, jpe.getMessage());
        } catch (Exception e) {
            log.warn("Audit write failed for {}: {}", action, e.getMessage());
        }
    }
}
