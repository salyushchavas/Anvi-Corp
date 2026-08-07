package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.i983.CreateI983Request;
import com.anvicorp.api.dto.i983.DsoResponseRequest;
import com.anvicorp.api.dto.i983.I983HistoryEntryResponse;
import com.anvicorp.api.dto.i983.I983PlanResponse;
import com.anvicorp.api.dto.i983.I983SummaryResponse;
import com.anvicorp.api.dto.i983.SubmitToDsoRequest;
import com.anvicorp.api.dto.i983.UpdateI983Request;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.I983Plan;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.DsoApprovalStatus;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.I983Status;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.enums.WorkAuthTrack;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.exception.StemOptRequiredException;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.I983PlanRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Form I-983 (STEM OPT Training Plan) service.
 *
 * Lifecycle:
 *   DRAFT -> COMPLETE (when both signatures land) -> SUBMITTED_TO_DSO -> DSO_APPROVED | DSO_REJECTED
 *   DSO can also return AMENDMENT_REQUESTED → signatures cleared, back to DRAFT-like editing
 *   DSO_REJECTED is terminal (a new plan must be created)
 *
 * Permission model:
 *   ERM / HR / ADMIN: full read + write, employer signature, DSO ops
 *   CANDIDATE: read their own + student signature only
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class I983Service {

    /** Privileged roles for I-983 access. */
    private static final Set<UserRole> STAFF_ROLES = EnumSet.of(UserRole.ERM, UserRole.ERM);

    /** Statuses where field edits are allowed. */
    private static final Set<I983Status> EDITABLE_STATUSES = EnumSet.of(I983Status.DRAFT, I983Status.AMENDMENT_REQUESTED);

    /** Federal STEM OPT rule: training must be at least 20 hours/week. */
    private static final int MIN_HOURS_PER_WEEK = 20;

    private final I983PlanRepository planRepository;
    private final CandidateRepository candidateRepository;
    private final ApplicationRepository applicationRepository;
    private final OfferRepository offerRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final EngagementRepository engagementRepository;
    private final InternLifecycleRepository internLifecycleRepository;
    private final com.anvicorp.api.notification.NotificationService notificationService;
    private final ObjectMapper objectMapper;

    // ── Commands ────────────────────────────────────────────────────────────

    @Transactional
    public I983Plan createPlan(CreateI983Request req, User creator) {
        // Either candidateId or applicationId must be provided. If only the
        // applicationId is given, derive candidateId from the application's FK.
        if (req.getCandidateId() == null && req.getApplicationId() == null) {
            throw new BadRequestException(
                    "Either candidateId or applicationId is required");
        }

        Application application = null;
        Candidate candidate;

        if (req.getApplicationId() != null) {
            application = applicationRepository.findById(req.getApplicationId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Application not found: " + req.getApplicationId()));
            // If both IDs were sent, the explicit candidateId wins; otherwise
            // derive from the application's candidate FK.
            UUID resolvedCandidateId = req.getCandidateId() != null
                    ? req.getCandidateId()
                    : (application.getCandidate() != null
                            ? application.getCandidate().getId()
                            : null);
            if (resolvedCandidateId == null) {
                throw new BadRequestException(
                        "Application has no candidate — cannot create I-983 plan");
            }
            candidate = candidateRepository.findById(resolvedCandidateId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Candidate not found: " + resolvedCandidateId));
        } else {
            candidate = candidateRepository.findById(req.getCandidateId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Candidate not found: " + req.getCandidateId()));
            application = applicationRepository.findByCandidateId(candidate.getId())
                    .stream()
                    .filter(a -> a.getStatus() == ApplicationStatus.ACCEPTED)
                    .max(Comparator.comparing(Application::getStatusUpdatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElseThrow(() -> new BadRequestException(
                            "Cannot create I-983 — candidate has no accepted application"));
        }

        // Resolve offer: explicit ID, or the application's ACCEPTED offer.
        Offer offer = null;
        if (req.getOfferId() != null) {
            offer = offerRepository.findById(req.getOfferId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Offer not found: " + req.getOfferId()));
        } else {
            Application app = application;
            offer = offerRepository.findByApplicationIdOrderByCreatedAtDesc(app.getId())
                    .stream()
                    .filter(o -> o.getStatus() == OfferStatus.ACCEPTED)
                    .findFirst()
                    .orElse(null);
        }

        JobPosting posting = application.getJobPosting();
        StaffingEntity entity = posting != null ? posting.getEntity() : null;
        if (entity == null) {
            throw new BadRequestException(
                    "Cannot create I-983 — application's job posting has no staffing entity");
        }

        // Phase 3 step 6 — resolve the candidate's engagement and gate the
        // create on STEM_OPT. Engagement.track is the snapshot taken at offer
        // acceptance (the source of truth); fall back to candidate.expectedTrack
        // for legacy candidates that pre-date Engagement.
        Engagement engagement = engagementRepository.findByApplicationId(application.getId())
                .orElseGet(() -> resolveCandidateEngagement(candidate.getId()));
        WorkAuthTrack track = engagement != null && engagement.getTrack() != null
                ? engagement.getTrack()
                : candidate.getExpectedTrack();
        if (track != WorkAuthTrack.STEM_OPT) {
            throw new BadRequestException(
                    "I-983 is required only for STEM OPT engagements.");
        }

        // Split candidate's fullName for first/last student name auto-fill.
        User candidateUser = candidate.getUser();
        String[] nameParts = splitFullName(
                candidateUser != null ? candidateUser.getFullName() : null);

        I983Plan plan = I983Plan.builder()
                .candidate(candidate)
                .application(application)
                .offer(offer)
                .engagement(engagement)
                .entity(entity)
                .status(I983Status.DRAFT)
                .dsoApprovalStatus(DsoApprovalStatus.NOT_SUBMITTED)
                // Section 1 — student auto-fill
                .studentFirstName(nameParts[0])
                .studentLastName(nameParts[1])
                .studentEmail(candidateUser != null ? candidateUser.getEmail() : null)
                // Section 2 — employer auto-fill
                .employerName(entity.getName())
                // Section 3 — training program auto-fill from offer
                .jobTitle(posting != null ? posting.getTitle() : null)
                .trainingStartDate(offer != null ? offer.getStartDate() : null)
                .trainingEndDate(offer != null ? offer.getExpectedEndDate() : null)
                .compensationAmount(offer != null ? offer.getCompensationAmount() : null)
                .compensationFrequency(offer != null ? offer.getCompensationFrequency() : null)
                .compensationCurrency(offer != null ? offer.getCompensationCurrency() : "USD")
                .createdBy(creator.getId())
                .build();

        plan = planRepository.save(plan);
        writeAudit(plan.getId(), "CREATE", creator.getId(), null, snapshot(plan));
        // Re-read through the fetch-join so toResponse can render candidate +
        // entity without lazy-loading after this @Transactional returns.
        return planRepository.findByIdWithGraph(plan.getId())
                .orElseThrow(() -> new IllegalStateException("Just-created I-983 plan vanished"));
    }

    @Transactional
    public I983Plan updateFields(UUID planId, UpdateI983Request req, User actor) {
        I983Plan plan = planRepository.findByIdWithGraph(planId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "I-983 plan not found: " + planId));

        if (!EDITABLE_STATUSES.contains(plan.getStatus())) {
            throw new BadRequestException(
                    "Cannot edit plan in status " + plan.getStatus()
                            + " — only DRAFT or AMENDMENT_REQUESTED allow edits");
        }

        Map<String, Object> before = snapshot(plan);
        applyUpdate(plan, req);
        plan = planRepository.save(plan);
        writeAudit(plan.getId(), "UPDATE_FIELDS", actor.getId(), before, snapshot(plan));
        return plan;
    }

    private void applyUpdate(I983Plan p, UpdateI983Request r) {
        // Null = no change. To explicitly clear a field, callers should re-issue
        // a draft (rare) — there's no UI flow to clear filled fields in v1.
        if (r.getStudentLastName() != null) p.setStudentLastName(r.getStudentLastName());
        if (r.getStudentFirstName() != null) p.setStudentFirstName(r.getStudentFirstName());
        if (r.getStudentMiddleName() != null) p.setStudentMiddleName(r.getStudentMiddleName());
        if (r.getSevisId() != null) p.setSevisId(r.getSevisId());
        if (r.getUscisNumber() != null) p.setUscisNumber(r.getUscisNumber());
        if (r.getStudentEmail() != null) p.setStudentEmail(r.getStudentEmail());
        if (r.getDegreeAwarded() != null) p.setDegreeAwarded(r.getDegreeAwarded());
        if (r.getDegreeLevel() != null) p.setDegreeLevel(r.getDegreeLevel());
        if (r.getUniversityName() != null) p.setUniversityName(r.getUniversityName());
        if (r.getUniversityCipCode() != null) p.setUniversityCipCode(r.getUniversityCipCode());
        if (r.getDateOfDegreeAward() != null) p.setDateOfDegreeAward(r.getDateOfDegreeAward());
        if (r.getOptStartDate() != null) p.setOptStartDate(r.getOptStartDate());
        if (r.getOptEndDate() != null) p.setOptEndDate(r.getOptEndDate());

        if (r.getEmployerName() != null) p.setEmployerName(r.getEmployerName());
        if (r.getEmployerEin() != null) p.setEmployerEin(r.getEmployerEin());
        if (r.getEmployerAddress() != null) p.setEmployerAddress(r.getEmployerAddress());
        if (r.getEmployerWebsite() != null) p.setEmployerWebsite(r.getEmployerWebsite());
        if (r.getEmployerNaicsCode() != null) p.setEmployerNaicsCode(r.getEmployerNaicsCode());
        if (r.getEmployerNumberOfFullTimeEmployees() != null)
            p.setEmployerNumberOfFullTimeEmployees(r.getEmployerNumberOfFullTimeEmployees());
        if (r.getEmployerOfficialName() != null) p.setEmployerOfficialName(r.getEmployerOfficialName());
        if (r.getEmployerOfficialTitle() != null) p.setEmployerOfficialTitle(r.getEmployerOfficialTitle());
        if (r.getEmployerOfficialEmail() != null) p.setEmployerOfficialEmail(r.getEmployerOfficialEmail());
        if (r.getEmployerOfficialPhone() != null) p.setEmployerOfficialPhone(r.getEmployerOfficialPhone());

        if (r.getJobTitle() != null) p.setJobTitle(r.getJobTitle());
        if (r.getTrainingStartDate() != null) p.setTrainingStartDate(r.getTrainingStartDate());
        if (r.getTrainingEndDate() != null) p.setTrainingEndDate(r.getTrainingEndDate());
        if (r.getHoursPerWeek() != null) p.setHoursPerWeek(r.getHoursPerWeek());
        if (r.getCompensationAmount() != null) p.setCompensationAmount(r.getCompensationAmount());
        if (r.getCompensationFrequency() != null) p.setCompensationFrequency(r.getCompensationFrequency());
        if (r.getCompensationCurrency() != null) p.setCompensationCurrency(r.getCompensationCurrency());
        if (r.getSupervisorName() != null) p.setSupervisorName(r.getSupervisorName());
        if (r.getSupervisorTitle() != null) p.setSupervisorTitle(r.getSupervisorTitle());
        if (r.getSupervisorEmail() != null) p.setSupervisorEmail(r.getSupervisorEmail());
        if (r.getSupervisorPhone() != null) p.setSupervisorPhone(r.getSupervisorPhone());

        if (r.getTrainingProgramDescription() != null)
            p.setTrainingProgramDescription(r.getTrainingProgramDescription());
        if (r.getHowTrainingRelatesToDegree() != null)
            p.setHowTrainingRelatesToDegree(r.getHowTrainingRelatesToDegree());
        if (r.getTrainingGoalsAndObjectives() != null)
            p.setTrainingGoalsAndObjectives(r.getTrainingGoalsAndObjectives());
        if (r.getPerformanceEvaluationMethod() != null)
            p.setPerformanceEvaluationMethod(r.getPerformanceEvaluationMethod());
        if (r.getReportingRequirements() != null)
            p.setReportingRequirements(r.getReportingRequirements());
        if (r.getSkillsKnowledgeLearned() != null)
            p.setSkillsKnowledgeLearned(r.getSkillsKnowledgeLearned());
        if (r.getResourcesEquipmentMaterials() != null)
            p.setResourcesEquipmentMaterials(r.getResourcesEquipmentMaterials());
        if (r.getSupervisorCommitments() != null)
            p.setSupervisorCommitments(r.getSupervisorCommitments());
    }

    @Transactional
    public I983Plan signEmployer(UUID planId, User signer) {
        I983Plan plan = planRepository.findByIdWithGraph(planId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "I-983 plan not found: " + planId));

        if (!EDITABLE_STATUSES.contains(plan.getStatus())) {
            throw new BadRequestException(
                    "Cannot sign in status " + plan.getStatus()
                            + " — only DRAFT or AMENDMENT_REQUESTED allow signing");
        }

        validateForEmployerSignature(plan);

        Map<String, Object> before = snapshot(plan);
        Instant now = Instant.now();
        plan.setEmployerSignedAt(now);
        plan.setEmployerSignedByUserId(signer.getId());
        plan.setEmployerSignedName(signer.getFullName());
        if (plan.getStudentSignedAt() != null) {
            plan.setStatus(I983Status.COMPLETE);
        }
        plan = planRepository.save(plan);
        writeAudit(plan.getId(), "SIGN_EMPLOYER", signer.getId(), before, snapshot(plan));
        return plan;
    }

    private void validateForEmployerSignature(I983Plan p) {
        List<String> errors = new ArrayList<>();

        // Section 2
        if (isBlank(p.getEmployerName())) errors.add("employerName is required");
        if (isBlank(p.getEmployerEin())) errors.add("employerEin is required");
        if (isBlank(p.getEmployerAddress())) errors.add("employerAddress is required");
        if (isBlank(p.getEmployerOfficialName())) errors.add("employerOfficialName is required");
        if (isBlank(p.getEmployerOfficialTitle())) errors.add("employerOfficialTitle is required");
        if (isBlank(p.getEmployerOfficialEmail())) errors.add("employerOfficialEmail is required");

        // Section 3
        if (isBlank(p.getJobTitle())) errors.add("jobTitle is required");
        if (p.getTrainingStartDate() == null) errors.add("trainingStartDate is required");
        if (p.getTrainingEndDate() == null) errors.add("trainingEndDate is required");
        if (p.getHoursPerWeek() == null) {
            errors.add("hoursPerWeek is required");
        } else if (p.getHoursPerWeek() < MIN_HOURS_PER_WEEK) {
            errors.add("hoursPerWeek must be at least " + MIN_HOURS_PER_WEEK
                    + " (federal STEM OPT rule)");
        }
        if (isBlank(p.getSupervisorName())) errors.add("supervisorName is required");
        if (isBlank(p.getSupervisorTitle())) errors.add("supervisorTitle is required");
        if (isBlank(p.getSupervisorEmail())) errors.add("supervisorEmail is required");

        // OPT window check — only if student has filled in their OPT dates.
        if (p.getOptStartDate() != null && p.getTrainingStartDate() != null
                && p.getTrainingStartDate().isBefore(p.getOptStartDate())) {
            errors.add("trainingStartDate must be on or after the OPT EAD start date");
        }
        if (p.getOptEndDate() != null && p.getTrainingEndDate() != null
                && p.getTrainingEndDate().isAfter(p.getOptEndDate())) {
            errors.add("trainingEndDate must be on or before the OPT EAD end date");
        }

        // Section 4 — narrative
        if (isBlank(p.getTrainingProgramDescription()))
            errors.add("trainingProgramDescription is required");
        if (isBlank(p.getHowTrainingRelatesToDegree()))
            errors.add("howTrainingRelatesToDegree is required");
        if (isBlank(p.getTrainingGoalsAndObjectives()))
            errors.add("trainingGoalsAndObjectives is required");
        if (isBlank(p.getPerformanceEvaluationMethod()))
            errors.add("performanceEvaluationMethod is required");
        if (isBlank(p.getReportingRequirements()))
            errors.add("reportingRequirements is required");

        if (!errors.isEmpty()) {
            throw new BadRequestException(String.join("; ", errors));
        }
    }

    @Transactional
    public I983Plan signStudent(UUID planId, User student) {
        I983Plan plan = planRepository.findByIdWithGraph(planId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "I-983 plan not found: " + planId));

        // Candidates can only sign their own plan.
        if (plan.getCandidate() == null
                || plan.getCandidate().getUser() == null
                || !plan.getCandidate().getUser().getId().equals(student.getId())) {
            throw new AccessDeniedException("This I-983 plan does not belong to you");
        }
        // GAP A5 — STEM_OPT gate also on the candidate-initiated student sign.
        // The plan create gate (line ~174) already blocks creation for non-STEM
        // tracks, but a track flip post-create + a stale frontend could route
        // here. Block deterministically and audit.
        requireCandidateStemOptEligibility(plan.getCandidate(), student);
        if (!EDITABLE_STATUSES.contains(plan.getStatus())) {
            throw new BadRequestException(
                    "Cannot sign in status " + plan.getStatus()
                            + " — only DRAFT or AMENDMENT_REQUESTED allow signing");
        }

        validateForStudentSignature(plan);

        Map<String, Object> before = snapshot(plan);
        Instant now = Instant.now();
        String[] nameParts = splitFullName(student.getFullName());
        plan.setStudentSignedAt(now);
        plan.setStudentSignedName(student.getFullName() != null
                ? student.getFullName()
                : (nameParts[0] + " " + nameParts[1]).trim());
        if (plan.getEmployerSignedAt() != null) {
            plan.setStatus(I983Status.COMPLETE);
        }
        plan = planRepository.save(plan);
        writeAudit(plan.getId(), "SIGN_STUDENT", student.getId(), before, snapshot(plan));

        // Batch-2 — HR gets a "plan ready for employer signature" heads-up.
        // Fires on every student-sign; idempotent per (event, plan_id) so a
        // re-sign after an amendment only emails once. Best-effort.
        try {
            notificationService.sendI983PlanReady(plan);
        } catch (Exception e) {
            log.warn("I983_PLAN_READY notify failed (non-fatal) for plan {}: {}",
                    plan.getId(), e.getMessage());
        }
        return plan;
    }

    private void validateForStudentSignature(I983Plan p) {
        List<String> errors = new ArrayList<>();
        if (isBlank(p.getStudentLastName())) errors.add("studentLastName is required");
        if (isBlank(p.getStudentFirstName())) errors.add("studentFirstName is required");
        if (isBlank(p.getSevisId())) errors.add("sevisId is required");
        if (isBlank(p.getStudentEmail())) errors.add("studentEmail is required");
        if (isBlank(p.getDegreeAwarded())) errors.add("degreeAwarded is required");
        if (p.getDegreeLevel() == null) errors.add("degreeLevel is required");
        if (isBlank(p.getUniversityName())) errors.add("universityName is required");
        if (p.getDateOfDegreeAward() == null) errors.add("dateOfDegreeAward is required");
        if (p.getOptStartDate() == null) errors.add("optStartDate is required");
        if (p.getOptEndDate() == null) errors.add("optEndDate is required");
        if (!errors.isEmpty()) {
            throw new BadRequestException(String.join("; ", errors));
        }
    }

    @Transactional
    public I983Plan submitToDso(UUID planId, SubmitToDsoRequest req, User actor) {
        I983Plan plan = planRepository.findByIdWithGraph(planId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "I-983 plan not found: " + planId));
        // IDOR guard: restrict to the intern's assigned ERM (or
        // SUPER_ADMIN). Unowned callers see 404 — cross-ERM probing can't
        // confirm the plan exists.
        ensureAssignedErmOr404(plan, actor, planId, "i983.submitToDso");

        if (plan.getStatus() != I983Status.COMPLETE) {
            throw new BadRequestException(
                    "Plan must be COMPLETE (both signatures present) before submitting to DSO");
        }
        if (plan.getDsoApprovalStatus() != DsoApprovalStatus.NOT_SUBMITTED) {
            throw new BadRequestException(
                    "Plan has already been submitted to DSO (status: "
                            + plan.getDsoApprovalStatus() + ")");
        }

        Map<String, Object> before = snapshot(plan);
        Instant now = Instant.now();
        plan.setDsoSubmittedAt(now);
        plan.setDsoSubmittedByUserId(actor.getId());
        plan.setDsoApprovalStatus(DsoApprovalStatus.SUBMITTED);
        plan.setStatus(I983Status.SUBMITTED_TO_DSO);
        plan = planRepository.save(plan);

        Map<String, Object> after = snapshot(plan);
        if (req != null && req.getSubmissionNotes() != null
                && !req.getSubmissionNotes().isBlank()) {
            after.put("submissionNotes", req.getSubmissionNotes());
        }
        writeAudit(plan.getId(), "SUBMIT_TO_DSO", actor.getId(), before, after);
        return plan;
    }

    @Transactional
    public I983Plan recordDsoResponse(UUID planId, DsoResponseRequest req, User actor) {
        I983Plan plan = planRepository.findByIdWithGraph(planId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "I-983 plan not found: " + planId));
        // IDOR guard mirror of submitToDso.
        ensureAssignedErmOr404(plan, actor, planId, "i983.recordDsoResponse");

        Set<I983Status> respondableStatuses = EnumSet.of(I983Status.SUBMITTED_TO_DSO, I983Status.DSO_APPROVED, I983Status.DSO_REJECTED, I983Status.AMENDMENT_REQUESTED);
        if (!respondableStatuses.contains(plan.getStatus())) {
            throw new BadRequestException(
                    "Cannot record DSO response in status " + plan.getStatus());
        }

        DsoApprovalStatus next = req.getApprovalStatus();
        if (next != DsoApprovalStatus.APPROVED
                && next != DsoApprovalStatus.REJECTED
                && next != DsoApprovalStatus.AMENDMENT_REQUESTED) {
            throw new BadRequestException(
                    "DSO response must be APPROVED, REJECTED, or AMENDMENT_REQUESTED");
        }

        Map<String, Object> before = snapshot(plan);
        Instant now = Instant.now();
        plan.setDsoApprovalStatus(next);
        plan.setDsoApprovalNotes(req.getNotes());
        plan.setDsoRespondedAt(now);

        switch (next) {
            case APPROVED -> plan.setStatus(I983Status.DSO_APPROVED);
            case REJECTED -> plan.setStatus(I983Status.DSO_REJECTED);
            case AMENDMENT_REQUESTED -> {
                plan.setStatus(I983Status.AMENDMENT_REQUESTED);
                // Clear signatures so both parties re-sign after corrections.
                plan.setEmployerSignedAt(null);
                plan.setEmployerSignedByUserId(null);
                plan.setEmployerSignedName(null);
                plan.setStudentSignedAt(null);
                plan.setStudentSignedName(null);
            }
            default -> {
                // Unreachable — guarded above.
            }
        }

        plan = planRepository.save(plan);
        writeAudit(plan.getId(), "DSO_RESPONSE", actor.getId(), before, snapshot(plan));
        return plan;
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    @Transactional
    public List<I983PlanResponse> getMyPlans(User candidateUser) {
        Candidate candidate = candidateRepository.findByUserId(candidateUser.getId())
                .orElse(null);
        if (candidate == null) return List.of();
        // GAP A5 — server-side STEM_OPT gate (defense in depth on top of the
        // frontend `user.expectedTrack` hide). Audits the denied attempt.
        // Note: read-only annotation widened to read-write because the audit
        // write happens inside the same transaction on the deny path.
        requireCandidateStemOptEligibility(candidate, candidateUser);
        return planRepository.findByCandidateIdOrderByCreatedAtDesc(candidate.getId())
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<I983PlanResponse> getForCandidate(UUID candidateId) {
        return planRepository.findByCandidateIdOrderByCreatedAtDesc(candidateId)
                .stream().map(this::toResponse).toList();
    }

    // Not readOnly: requireReadAccess may now write an audit row on the
    // candidate deny path (GAP A5 STEM gate). Staff reads still pay a trivial
    // r/w-transaction overhead but no writes happen on the success path.
    @Transactional
    public I983Plan getById(UUID planId, User caller) {
        I983Plan plan = planRepository.findByIdWithGraph(planId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "I-983 plan not found: " + planId));
        requireReadAccess(plan, caller);
        return plan;
    }

    @Transactional(readOnly = true)
    public Page<I983SummaryResponse> list(I983Status status,
                                          UUID candidateId,
                                          UUID entityId,
                                          Pageable pageable) {
        if (candidateId != null) {
            List<I983Plan> all = planRepository
                    .findByCandidateIdOrderByCreatedAtDesc(candidateId);
            if (status != null) {
                all = all.stream().filter(p -> p.getStatus() == status).toList();
            }
            int from = (int) Math.min(pageable.getOffset(), all.size());
            int to = Math.min(from + pageable.getPageSize(), all.size());
            return new PageImpl<>(all.subList(from, to), pageable, all.size())
                    .map(this::toSummary);
        }
        // Phase-3 sweep — graph-fetching list queries so toSummary's
        // candidate.user / entity reads don't 500 under open-in-view=false.
        if (entityId != null) {
            return planRepository.findByEntityIdWithGraph(entityId, pageable)
                    .map(this::toSummary);
        }
        if (status != null) {
            return planRepository.findByStatusWithGraph(status, pageable)
                    .map(this::toSummary);
        }
        return planRepository.findAllWithGraph(pageable)
                .map(this::toSummary);
    }

    // Not readOnly: requireReadAccess may now write a gate-audit row on the
    // candidate deny path (GAP A5).
    @Transactional
    public List<I983HistoryEntryResponse> getHistory(UUID planId, User caller) {
        I983Plan plan = planRepository.findByIdWithGraph(planId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "I-983 plan not found: " + planId));
        requireReadAccess(plan, caller);

        return auditLogRepository
                .findByEntityTypeAndEntityIdOrderByTimestampDesc("I983Plan", planId)
                .stream()
                .map(this::toHistoryEntry)
                .toList();
    }

    private I983HistoryEntryResponse toHistoryEntry(AuditLog entry) {
        User performer = entry.getUserId() != null
                ? userRepository.findById(entry.getUserId()).orElse(null)
                : null;
        String performerName = performer != null ? performer.getFullName() : "System";
        String performerRole = performer != null && performer.getRoles() != null
                && !performer.getRoles().isEmpty()
                ? performer.getRoles().iterator().next().name()
                : null;
        String summary = switch (entry.getAction()) {
            case "CREATE" -> "Plan created by " + performerName;
            case "UPDATE_FIELDS" -> "Fields updated by " + performerName;
            case "SIGN_EMPLOYER" -> "Signed by employer (" + performerName + ")";
            case "SIGN_STUDENT" -> "Signed by student (" + performerName + ")";
            case "SUBMIT_TO_DSO" -> "Submitted to DSO by " + performerName;
            case "DSO_RESPONSE" -> "DSO response recorded by " + performerName;
            default -> entry.getAction();
        };
        return I983HistoryEntryResponse.builder()
                .auditId(entry.getId())
                .timestamp(entry.getTimestamp())
                .action(entry.getAction())
                .performedByName(performerName)
                .performedByRole(performerRole)
                .summary(summary)
                .build();
    }

    // ── Permissions ─────────────────────────────────────────────────────────

    /**
     * IDOR guard for the DSO write paths (submitToDso / recordDsoResponse).
     * Restricts to the intern's assigned ERM on the lifecycle row. Returns
     * a 404 on mismatch — no 403 — so cross-ERM enumeration of pending DSO
     * plans is blocked.
     *
     * <ul>
     *   <li>SUPER_ADMIN bypasses.</li>
     *   <li>Null lifecycle / null {@code erm_id} → single-org fallback:
     *       any ERM caller is treated as the de-facto owner (parallels
     *       TrainerScopeGuard / EvaluatorScopeGuard null-FK behaviour so
     *       pre-Phase-3 legacy rows aren't locked out).</li>
     * </ul>
     */
    private void ensureAssignedErmOr404(I983Plan plan, User actor, UUID planId,
                                         String endpointName) {
        if (actor == null) {
            throw new AccessDeniedException("Authentication required");
        }
        if (actor.getRoles() != null
                && actor.getRoles().contains(UserRole.SUPER_ADMIN)) return;
        Candidate candidate = plan.getCandidate();
        UUID internUserId = candidate != null && candidate.getUser() != null
                ? candidate.getUser().getId() : null;
        InternLifecycle lc = internUserId != null
                ? internLifecycleRepository.findByUserId(internUserId).orElse(null)
                : null;
        if (lc == null || lc.getErmId() == null) {
            log.debug("[I983Service] null lifecycle/erm_id for plan={} — "
                    + "allowing {} caller {} as de-facto owner",
                    planId, endpointName, actor.getId());
            return;
        }
        if (lc.getErmId().equals(actor.getId())) return;
        log.warn("[IDOR-guard] {} caller={} resource={} lifecycle={} reason=not-assigned-erm",
                endpointName, actor.getId(), planId, lc.getId());
        throw new ResourceNotFoundException("I-983 plan not found: " + planId);
    }

    public void requireReadAccess(I983Plan plan, User caller) {
        if (caller == null) {
            throw new AccessDeniedException("Authentication required");
        }
        if (caller.getRoles() != null
                && caller.getRoles().stream().anyMatch(STAFF_ROLES::contains)) {
            return;
        }
        if (plan.getCandidate() != null
                && plan.getCandidate().getUser() != null
                && plan.getCandidate().getUser().getId().equals(caller.getId())) {
            // GAP A5 — candidate read of their own plan still requires the
            // STEM_OPT track. Blocks the "track was flipped after plan create"
            // edge case from leaking I-983 data to a non-STEM candidate.
            requireCandidateStemOptEligibility(plan.getCandidate(), caller);
            return;
        }
        // Don't leak existence of plans the candidate doesn't own.
        throw new ResourceNotFoundException("I-983 plan not found: " + plan.getId());
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    public I983PlanResponse toResponse(I983Plan p) {
        Candidate candidate = p.getCandidate();
        User candidateUser = candidate != null ? candidate.getUser() : null;
        StaffingEntity entity = p.getEntity();
        boolean fullySigned =
                p.getEmployerSignedAt() != null && p.getStudentSignedAt() != null;
        Long daysSince = p.getCreatedAt() != null
                ? ChronoUnit.DAYS.between(
                        p.getCreatedAt().atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate(),
                        LocalDate.now())
                : null;

        return I983PlanResponse.builder()
                .id(p.getId())
                .candidateId(candidate != null ? candidate.getId() : null)
                .candidateName(candidateUser != null ? candidateUser.getFullName() : null)
                .candidateEmail(candidateUser != null ? candidateUser.getEmail() : null)
                .applicationId(p.getApplication() != null ? p.getApplication().getId() : null)
                .offerId(p.getOffer() != null ? p.getOffer().getId() : null)
                .entityId(entity != null ? entity.getId() : null)
                .entityName(entity != null ? entity.getName() : null)
                .status(p.getStatus())
                // Section 1
                .studentLastName(p.getStudentLastName())
                .studentFirstName(p.getStudentFirstName())
                .studentMiddleName(p.getStudentMiddleName())
                .sevisId(p.getSevisId())
                .uscisNumber(p.getUscisNumber())
                .studentEmail(p.getStudentEmail())
                .degreeAwarded(p.getDegreeAwarded())
                .degreeLevel(p.getDegreeLevel())
                .universityName(p.getUniversityName())
                .universityCipCode(p.getUniversityCipCode())
                .dateOfDegreeAward(p.getDateOfDegreeAward())
                .optStartDate(p.getOptStartDate())
                .optEndDate(p.getOptEndDate())
                // Section 2
                .employerName(p.getEmployerName())
                .employerEin(p.getEmployerEin())
                .employerAddress(p.getEmployerAddress())
                .employerWebsite(p.getEmployerWebsite())
                .employerNaicsCode(p.getEmployerNaicsCode())
                .employerNumberOfFullTimeEmployees(p.getEmployerNumberOfFullTimeEmployees())
                .employerOfficialName(p.getEmployerOfficialName())
                .employerOfficialTitle(p.getEmployerOfficialTitle())
                .employerOfficialEmail(p.getEmployerOfficialEmail())
                .employerOfficialPhone(p.getEmployerOfficialPhone())
                // Section 3
                .jobTitle(p.getJobTitle())
                .trainingStartDate(p.getTrainingStartDate())
                .trainingEndDate(p.getTrainingEndDate())
                .hoursPerWeek(p.getHoursPerWeek())
                .compensationAmount(p.getCompensationAmount())
                .compensationFrequency(p.getCompensationFrequency())
                .compensationCurrency(p.getCompensationCurrency())
                .supervisorName(p.getSupervisorName())
                .supervisorTitle(p.getSupervisorTitle())
                .supervisorEmail(p.getSupervisorEmail())
                .supervisorPhone(p.getSupervisorPhone())
                // Section 4
                .trainingProgramDescription(p.getTrainingProgramDescription())
                .howTrainingRelatesToDegree(p.getHowTrainingRelatesToDegree())
                .trainingGoalsAndObjectives(p.getTrainingGoalsAndObjectives())
                .performanceEvaluationMethod(p.getPerformanceEvaluationMethod())
                .reportingRequirements(p.getReportingRequirements())
                .skillsKnowledgeLearned(p.getSkillsKnowledgeLearned())
                .resourcesEquipmentMaterials(p.getResourcesEquipmentMaterials())
                .supervisorCommitments(p.getSupervisorCommitments())
                // Signatures
                .employerSignedAt(p.getEmployerSignedAt())
                .employerSignedByName(lookupUserName(p.getEmployerSignedByUserId()))
                .employerSignedName(p.getEmployerSignedName())
                .studentSignedAt(p.getStudentSignedAt())
                .studentSignedName(p.getStudentSignedName())
                // DSO
                .dsoSubmittedAt(p.getDsoSubmittedAt())
                .dsoSubmittedByName(lookupUserName(p.getDsoSubmittedByUserId()))
                .dsoApprovalStatus(p.getDsoApprovalStatus())
                .dsoApprovalNotes(p.getDsoApprovalNotes())
                .dsoRespondedAt(p.getDsoRespondedAt())
                // Computed
                .fullySigned(fullySigned)
                .daysSinceCreation(daysSince)
                // daysOverdue would require an onboarding-task lookup per response;
                // left null for v1 — frontend can compute from onboarding endpoint if needed.
                .daysOverdue(null)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .createdByName(lookupUserName(p.getCreatedBy()))
                .build();
    }

    public I983SummaryResponse toSummary(I983Plan p) {
        Candidate candidate = p.getCandidate();
        User candidateUser = candidate != null ? candidate.getUser() : null;
        StaffingEntity entity = p.getEntity();
        return I983SummaryResponse.builder()
                .id(p.getId())
                .candidateId(candidate != null ? candidate.getId() : null)
                .candidateName(candidateUser != null ? candidateUser.getFullName() : null)
                .entityName(entity != null ? entity.getName() : null)
                .jobTitle(p.getJobTitle())
                .status(p.getStatus())
                .dsoApprovalStatus(p.getDsoApprovalStatus())
                .employerSigned(p.getEmployerSignedAt() != null)
                .studentSigned(p.getStudentSignedAt() != null)
                .trainingStartDate(p.getTrainingStartDate())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private String lookupUserName(UUID userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(User::getFullName).orElse(null);
    }

    // ── Audit log ───────────────────────────────────────────────────────────

    private Map<String, Object> snapshot(I983Plan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("candidateId", p.getCandidate() != null ? p.getCandidate().getId() : null);
        m.put("entityId", p.getEntity() != null ? p.getEntity().getId() : null);
        m.put("status", p.getStatus());
        m.put("studentFirstName", p.getStudentFirstName());
        m.put("studentLastName", p.getStudentLastName());
        m.put("sevisId", p.getSevisId());
        m.put("studentEmail", p.getStudentEmail());
        m.put("degreeAwarded", p.getDegreeAwarded());
        m.put("degreeLevel", p.getDegreeLevel());
        m.put("universityName", p.getUniversityName());
        m.put("universityCipCode", p.getUniversityCipCode());
        m.put("dateOfDegreeAward", p.getDateOfDegreeAward());
        m.put("optStartDate", p.getOptStartDate());
        m.put("optEndDate", p.getOptEndDate());
        m.put("employerName", p.getEmployerName());
        m.put("employerEin", p.getEmployerEin());
        m.put("employerOfficialName", p.getEmployerOfficialName());
        m.put("employerOfficialTitle", p.getEmployerOfficialTitle());
        m.put("employerOfficialEmail", p.getEmployerOfficialEmail());
        m.put("jobTitle", p.getJobTitle());
        m.put("trainingStartDate", p.getTrainingStartDate());
        m.put("trainingEndDate", p.getTrainingEndDate());
        m.put("hoursPerWeek", p.getHoursPerWeek());
        m.put("compensationAmount", p.getCompensationAmount());
        m.put("compensationFrequency", p.getCompensationFrequency());
        m.put("compensationCurrency", p.getCompensationCurrency());
        m.put("supervisorName", p.getSupervisorName());
        m.put("supervisorTitle", p.getSupervisorTitle());
        m.put("supervisorEmail", p.getSupervisorEmail());
        m.put("trainingProgramDescription", p.getTrainingProgramDescription());
        m.put("howTrainingRelatesToDegree", p.getHowTrainingRelatesToDegree());
        m.put("trainingGoalsAndObjectives", p.getTrainingGoalsAndObjectives());
        m.put("performanceEvaluationMethod", p.getPerformanceEvaluationMethod());
        m.put("reportingRequirements", p.getReportingRequirements());
        m.put("employerSignedAt", p.getEmployerSignedAt());
        m.put("employerSignedName", p.getEmployerSignedName());
        m.put("studentSignedAt", p.getStudentSignedAt());
        m.put("studentSignedName", p.getStudentSignedName());
        m.put("dsoSubmittedAt", p.getDsoSubmittedAt());
        m.put("dsoApprovalStatus", p.getDsoApprovalStatus());
        m.put("dsoApprovalNotes", p.getDsoApprovalNotes());
        m.put("dsoRespondedAt", p.getDsoRespondedAt());
        return m;
    }

    private void writeAudit(UUID planId, String action, UUID userId,
                            Map<String, Object> before, Map<String, Object> after) {
        AuditLog entry = AuditLog.builder()
                .entityType("I983Plan")
                .entityId(planId)
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
            log.warn("Failed to serialize I-983 audit snapshot: {}", e.getMessage());
            return new HashMap<>(snapshot).toString();
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────────

    /**
     * Phase 3 step 6 fallback when the application has no engagement (legacy
     * acceptances pre-Phase-3 or staff calling create with a candidateId-only
     * payload). Picks the candidate's most-recent in-funnel engagement, the
     * same way {@code I9FormService.resolveActiveEngagement} does.
     */
    private Engagement resolveCandidateEngagement(UUID candidateId) {
        return engagementRepository.findByCandidateId(candidateId).stream()
                .filter(e -> e.getStatus() != EngagementStatus.BLOCKED_NO_AUTHORIZATION
                        && e.getStatus() != EngagementStatus.TERMINATED)
                .max(Comparator.comparing(Engagement::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    // ── STEM_OPT gate (GAP A5) ──────────────────────────────────────────────

    /**
     * Hard 403 gate for candidate-initiated I-983 paths. Resolves the track of
     * record from {@link Engagement#getTrack()} (the snapshot taken at offer
     * acceptance — source of truth) and falls back to
     * {@link Candidate#getExpectedTrack()} for legacy candidates that
     * pre-date Engagement. Throws {@link StemOptRequiredException} (mapped to
     * 403 + code {@code STEM_OPT_REQUIRED}) when neither resolves to STEM_OPT.
     *
     * Staff paths (ERM/HR/ADMIN) never call this — they go through
     * the plain repository reads.
     */
    private void requireCandidateStemOptEligibility(Candidate candidate, User actor) {
        UUID actorId = actor != null ? actor.getId() : null;
        UUID candidateId = candidate != null ? candidate.getId() : null;
        WorkAuthTrack track = null;
        if (candidate != null) {
            Engagement engagement = resolveCandidateEngagement(candidate.getId());
            track = engagement != null && engagement.getTrack() != null
                    ? engagement.getTrack()
                    : candidate.getExpectedTrack();
        }
        if (track == WorkAuthTrack.STEM_OPT) {
            return;
        }
        writeGateAudit(candidateId, "I983_BLOCKED_NON_STEM", actorId,
                "track=" + (track != null ? track.name() : "null"));
        throw new StemOptRequiredException(
                "Form I-983 is only required for STEM OPT engagements.");
    }

    /**
     * Audit row for a denied candidate-initiated I-983 attempt. Keyed on
     * Candidate (not I983Plan) — the deny might happen before any plan exists,
     * and the candidate is the durable subject either way.
     */
    private void writeGateAudit(UUID candidateId, String action, UUID actorId, String reason) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reason", reason);
        AuditLog entry = AuditLog.builder()
                .entityType("Candidate")
                .entityId(candidateId)
                .action(action)
                .userId(actorId)
                .afterJson(serialize(after))
                .build();
        auditLogRepository.save(entry);
    }

    /** Splits a fullName into [firstName, lastName]. If a single word, [word, ""]. */
    private static String[] splitFullName(String fullName) {
        if (fullName == null || fullName.isBlank()) return new String[] { "", "" };
        String trimmed = fullName.trim();
        int sp = trimmed.indexOf(' ');
        if (sp <= 0) return new String[] { trimmed, "" };
        return new String[] {
                trimmed.substring(0, sp),
                trimmed.substring(sp + 1).trim()
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
