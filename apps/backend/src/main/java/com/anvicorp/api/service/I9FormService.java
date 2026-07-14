package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.i9.I9FormResponse;
import com.anvicorp.api.dto.i9.I9HistoryEntryResponse;
import com.anvicorp.api.dto.i9.I9SummaryResponse;
import com.anvicorp.api.dto.i9.Section1Request;
import com.anvicorp.api.dto.i9.Section2Request;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.I9Form;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.CitizenshipStatus;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.I9Status;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.OfferRequiredException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.I9FormRepository;
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

import java.time.DayOfWeek;
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
import java.util.regex.Pattern;

/**
 * Audit-grade I-9 form service. Every mutation writes a full before/after JSON
 * snapshot to the AuditLog table — that table IS the version history.
 *
 * Permission model:
 *   - Candidate: Section 1 of their own form only
 *   - HR (HR): Section 2 + read all
 *   - ERM / RECRUITER / TECHNICAL_EVALUATOR: read all (no write)
 *   - ADMIN: everything, including reopening completed forms
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class I9FormService {

    private static final Pattern SSN_PATTERN = Pattern.compile("^\\d{3}-\\d{2}-\\d{4}$");
    private static final int SECTION_2_DEADLINE_BUSINESS_DAYS = 3;

    /** Privileged roles that bypass the candidate ownership check on reads. */
    private static final Set<UserRole> READ_PRIVILEGED = EnumSet.of(UserRole.ERM, UserRole.ERM, UserRole.TRAINER);

    private final I9FormRepository formRepository;
    private final CandidateRepository candidateRepository;
    private final OfferRepository offerRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final EngagementRepository engagementRepository;
    private final ObjectMapper objectMapper;
    private final com.anvicorp.api.notification.NotificationService notificationService;

    // ── Lazy-create + lookups ───────────────────────────────────────────────

    @Transactional
    public I9Form getOrCreateForCandidate(UUID candidateId, User creator) {
        // Use the fetch-join variant so the returned form already has its
        // candidate/user graph populated for the controller-level toResponse.
        return formRepository.findByCandidateIdWithGraph(candidateId)
                .orElseGet(() -> createForCandidate(candidateId, creator));
    }

    private I9Form createForCandidate(UUID candidateId, User creator) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate not found: " + candidateId));

        // Phase 3 step 5 — link to an engagement when one exists. Legacy
        // candidates pre-Engagement keep the candidate-keyed path with a null
        // engagement FK; the rest of the flow is identical.
        Engagement engagement = resolveActiveEngagement(candidateId);

        I9Form form = I9Form.builder()
                .candidate(candidate)
                .engagement(engagement)
                .status(I9Status.NOT_STARTED)
                .preparerTranslatorUsed(false)
                .createdBy(creator != null ? creator.getId() : candidate.getUser().getId())
                .build();

        // Pre-fill firstDayOfEmployment from the engagement's planned start
        // when available, otherwise fall back to the most-recent accepted
        // offer's startDate. Either source feeds the same due-date math.
        LocalDate firstDay = engagement != null
                ? engagement.getPlannedStartDate()
                : null;
        if (firstDay == null) {
            firstDay = mostRecentAcceptedOfferStartDate(candidate);
        }
        if (firstDay != null) {
            form.setFirstDayOfEmployment(firstDay);
        }
        // Section 1 must be done by first day; Section 2 within 3 business
        // days. Both fields stay null when we don't yet know the start date.
        form.setSection1DueDate(firstDay);
        form.setSection2DueDate(firstDay != null
                ? plusBusinessDays(firstDay, SECTION_2_DEADLINE_BUSINESS_DAYS)
                : null);

        form = formRepository.save(form);
        writeAudit(form.getId(), "CREATE",
                creator != null ? creator.getId() : candidate.getUser().getId(),
                null, snapshot(form));
        // Re-read through the fetch-join so the returned entity has candidate
        // + user already initialized for the controller's toResponse call.
        return formRepository.findByIdWithGraph(form.getId())
                .orElseThrow(() -> new IllegalStateException("Just-created I-9 form vanished"));
    }

    /**
     * Phase 3 step 5 — find the candidate's most-recent in-funnel engagement.
     * Excludes blocked/terminated since those don't get an I-9. Returns null
     * for legacy candidates with no engagement (pre-Phase-3); the I-9 flow
     * stays candidate-keyed for those.
     */
    private Engagement resolveActiveEngagement(UUID candidateId) {
        return engagementRepository.findByCandidateId(candidateId).stream()
                .filter(e -> e.getStatus() != EngagementStatus.BLOCKED_NO_AUTHORIZATION
                        && e.getStatus() != EngagementStatus.TERMINATED)
                .max(Comparator.comparing(Engagement::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    @Transactional
    public I9Form getMyForm(User candidateUser) {
        Candidate candidate = candidateRepository.findByUserId(candidateUser.getId())
                .orElseThrow(() -> new BadRequestException(
                        "No candidate profile found for the current user"));
        // GAP A1 — hard post-offer gate for the candidate-initiated path. If an
        // I-9 row already exists (e.g. seeded by HR pre-Phase-3) we still
        // surface it; the gate only blocks new-row creation. Staff path
        // (`/api/v1/i9/candidate/{id}`) goes through getOrCreateForCandidate
        // directly and is NOT gated.
        if (formRepository.findByCandidateIdWithGraph(candidate.getId()).isEmpty()) {
            requireCandidateOfferEligibility(candidate, candidateUser);
        }
        return getOrCreateForCandidate(candidate.getId(), candidateUser);
    }

    @Transactional(readOnly = true)
    public I9Form getById(UUID formId, User caller) {
        I9Form form = formRepository.findByIdWithGraph(formId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "I-9 form not found: " + formId));
        requireReadAccess(form, caller);
        return form;
    }

    // ── Section 1 (candidate) ───────────────────────────────────────────────

    @Transactional
    public I9Form saveSection1(UUID formId, Section1Request req, User actor) {
        I9Form form = formRepository.findByIdWithGraph(formId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "I-9 form not found: " + formId));
        requireSection1WriteAccess(form, actor);

        // GAP A1 — candidate path is post-offer-gated. ADMINs (the only other
        // role allowed on this endpoint by @PreAuthorize) keep the corrective
        // write path; they need to edit Section 1 to fix bad data even when
        // the candidate's offer history is unusual.
        if (!isAdmin(actor) && form.getCandidate() != null) {
            requireCandidateOfferEligibility(form.getCandidate(), actor);
        }

        if (form.getStatus() == I9Status.COMPLETED) {
            throw new BadRequestException(
                    "I-9 is COMPLETED and read-only. An admin must reopen the form before editing Section 1.");
        }

        Map<String, Object> before = snapshot(form);

        // Full Section-1 replacement — copy every field, even nulls. We want a
        // consistent record of exactly what the candidate attested to.
        form.setLastName(req.getLastName());
        form.setFirstName(req.getFirstName());
        form.setMiddleInitial(req.getMiddleInitial());
        form.setOtherLastNamesUsed(req.getOtherLastNamesUsed());
        form.setAddressStreet(req.getAddressStreet());
        form.setAddressAptNumber(req.getAddressAptNumber());
        form.setAddressCity(req.getAddressCity());
        form.setAddressState(req.getAddressState());
        form.setAddressZipCode(req.getAddressZipCode());
        form.setDateOfBirth(req.getDateOfBirth());
        form.setSsn(req.getSsn());
        form.setEmail(req.getEmail());
        form.setPhoneNumber(req.getPhoneNumber());
        form.setCitizenshipStatus(req.getCitizenshipStatus());
        form.setAlienRegistrationNumber(req.getAlienRegistrationNumber());
        form.setForeignPassportNumber(req.getForeignPassportNumber());
        form.setForeignPassportCountry(req.getForeignPassportCountry());
        form.setWorkAuthExpirationDate(req.getWorkAuthExpirationDate());
        form.setPreparerTranslatorUsed(
                Boolean.TRUE.equals(req.getPreparerTranslatorUsed()));

        if (!req.isDraft()) {
            validateSection1ForSubmit(form);
            form.setSection1SignedAt(Instant.now());
            form.setSection1SignedByName(
                    (safe(form.getFirstName()) + " " + safe(form.getLastName())).trim());
            // Phase 3 step 5: NOT_STARTED → SECTION_2_PENDING (new explicit phase).
            // REOPENED stays REOPENED until Section 2 is signed.
            if (form.getStatus() == I9Status.NOT_STARTED) {
                form.setStatus(I9Status.SECTION_2_PENDING);
            }
        }

        form = formRepository.save(form);
        writeAudit(form.getId(),
                req.isDraft() ? "SECTION_1_DRAFT_SAVE" : "SECTION_1_SUBMIT",
                actor.getId(), before, snapshot(form));
        // Batch-2 — HR gets a §2-pending heads-up exactly when the form
        // transitions into SECTION_2_PENDING (i.e. on the real submit, not on
        // re-saves or drafts). Idempotent per (event, form_id); best-effort.
        if (!req.isDraft() && form.getStatus() == I9Status.SECTION_2_PENDING) {
            try {
                notificationService.sendI9Section2Pending(form);
            } catch (Exception e) {
                log.warn("I9_SECTION2_PENDING notify failed (non-fatal) for form {}: {}",
                        form.getId(), e.getMessage());
            }
        }
        return form;
    }

    private void validateSection1ForSubmit(I9Form form) {
        List<String> errors = new ArrayList<>();
        if (isBlank(form.getLastName())) errors.add("lastName is required");
        if (isBlank(form.getFirstName())) errors.add("firstName is required");
        if (isBlank(form.getAddressStreet())) errors.add("addressStreet is required");
        if (isBlank(form.getAddressCity())) errors.add("addressCity is required");
        if (isBlank(form.getAddressState())) errors.add("addressState is required");
        if (isBlank(form.getAddressZipCode())) errors.add("addressZipCode is required");
        if (form.getDateOfBirth() == null) errors.add("dateOfBirth is required");
        else if (!form.getDateOfBirth().isBefore(LocalDate.now()))
            errors.add("dateOfBirth must be in the past");
        if (form.getCitizenshipStatus() == null) errors.add("citizenshipStatus is required");

        if (form.getCitizenshipStatus() == CitizenshipStatus.ALIEN_AUTHORIZED_TO_WORK
                && form.getWorkAuthExpirationDate() == null) {
            errors.add("Work authorization expiration date is required for ALIEN_AUTHORIZED_TO_WORK");
        }
        if (form.getCitizenshipStatus() == CitizenshipStatus.LAWFUL_PERMANENT_RESIDENT
                && isBlank(form.getAlienRegistrationNumber())) {
            errors.add("Alien Registration Number / USCIS Number is required for LAWFUL_PERMANENT_RESIDENT");
        }
        if (!isBlank(form.getSsn()) && !SSN_PATTERN.matcher(form.getSsn()).matches()) {
            errors.add("SSN must be in XXX-XX-XXXX format");
        }
        if (!errors.isEmpty()) {
            throw new BadRequestException(String.join("; ", errors));
        }
    }

    // ── Section 2 (HR) ──────────────────────────────────────────────────────

    @Transactional
    public I9Form saveSection2(UUID formId, Section2Request req, User actor) {
        I9Form form = formRepository.findByIdWithGraph(formId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "I-9 form not found: " + formId));

        // SECTION_2_PENDING is the canonical pre-state from step 5; the
        // SECTION_1_COMPLETE / COMPLETED / REOPENED checks stay for legacy
        // rows and for HR-edit-after-completion cases.
        if (form.getStatus() != I9Status.SECTION_2_PENDING
                && form.getStatus() != I9Status.SECTION_1_COMPLETE
                && form.getStatus() != I9Status.COMPLETED
                && form.getStatus() != I9Status.REOPENED) {
            throw new BadRequestException(
                    "Section 2 requires Section 1 to be complete first (current status: "
                            + form.getStatus() + ")");
        }

        Map<String, Object> before = snapshot(form);

        form.setFirstDayOfEmployment(req.getFirstDayOfEmployment());
        // Recompute the deadlines whenever the start date is set/changed
        // during Section 2 entry — keeps the audit story aligned with reality.
        if (req.getFirstDayOfEmployment() != null) {
            form.setSection1DueDate(req.getFirstDayOfEmployment());
            form.setSection2DueDate(plusBusinessDays(
                    req.getFirstDayOfEmployment(), SECTION_2_DEADLINE_BUSINESS_DAYS));
        }
        form.setListATitle(req.getListATitle());
        form.setListAIssuingAuthority(req.getListAIssuingAuthority());
        form.setListADocumentNumber(req.getListADocumentNumber());
        form.setListAExpirationDate(req.getListAExpirationDate());
        form.setListBTitle(req.getListBTitle());
        form.setListBIssuingAuthority(req.getListBIssuingAuthority());
        form.setListBDocumentNumber(req.getListBDocumentNumber());
        form.setListBExpirationDate(req.getListBExpirationDate());
        form.setListCTitle(req.getListCTitle());
        form.setListCIssuingAuthority(req.getListCIssuingAuthority());
        form.setListCDocumentNumber(req.getListCDocumentNumber());
        form.setAdditionalInformation(req.getAdditionalInformation());
        form.setEmployerName(req.getEmployerName());
        form.setEmployerTitle(req.getEmployerTitle());
        form.setBusinessOrganizationName(req.getBusinessOrganizationName());
        form.setBusinessAddress(req.getBusinessAddress());

        if (!req.isDraft()) {
            validateSection2ForSubmit(form);
            form.setSection2SignedAt(Instant.now());
            form.setSection2SignedByUserId(actor.getId());
            form.setStatus(I9Status.COMPLETED);
        }

        form = formRepository.save(form);
        writeAudit(form.getId(),
                req.isDraft() ? "SECTION_2_DRAFT_SAVE" : "SECTION_2_SUBMIT",
                actor.getId(), before, snapshot(form));
        return form;
    }

    private void validateSection2ForSubmit(I9Form form) {
        List<String> errors = new ArrayList<>();
        if (form.getFirstDayOfEmployment() == null) {
            errors.add("firstDayOfEmployment is required");
        } else if (form.getFirstDayOfEmployment().isAfter(LocalDate.now())) {
            errors.add("firstDayOfEmployment cannot be in the future");
        }
        if (isBlank(form.getEmployerName())) errors.add("employerName is required");
        if (isBlank(form.getEmployerTitle())) errors.add("employerTitle is required");
        if (isBlank(form.getBusinessOrganizationName()))
            errors.add("businessOrganizationName is required");
        if (isBlank(form.getBusinessAddress())) errors.add("businessAddress is required");

        boolean listAComplete = !isBlank(form.getListATitle())
                && !isBlank(form.getListADocumentNumber())
                && !isBlank(form.getListAIssuingAuthority());
        boolean listBComplete = !isBlank(form.getListBTitle())
                && !isBlank(form.getListBDocumentNumber())
                && !isBlank(form.getListBIssuingAuthority());
        boolean listCComplete = !isBlank(form.getListCTitle())
                && !isBlank(form.getListCDocumentNumber())
                && !isBlank(form.getListCIssuingAuthority());

        if (!listAComplete && !(listBComplete && listCComplete)) {
            errors.add("Must provide either List A documents OR both List B and List C documents");
        }
        if (listAComplete && (listBComplete || listCComplete)) {
            errors.add("Provide List A documents OR (List B + List C), not both");
        }
        if (!errors.isEmpty()) {
            throw new BadRequestException(String.join("; ", errors));
        }
    }

    // ── Admin reopen ────────────────────────────────────────────────────────

    @Transactional
    public I9Form reopenForm(UUID formId, String reason, User adminActor) {
        I9Form form = formRepository.findByIdWithGraph(formId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "I-9 form not found: " + formId));

        if (form.getStatus() != I9Status.COMPLETED) {
            throw new BadRequestException(
                    "Only COMPLETED forms can be reopened (current status: " + form.getStatus() + ")");
        }

        Map<String, Object> before = snapshot(form);
        form.setStatus(I9Status.REOPENED);
        form.setSection2SignedAt(null);
        form.setSection2SignedByUserId(null);
        form = formRepository.save(form);

        // Embed reopen reason in the snapshot so it lives in the audit history.
        Map<String, Object> after = snapshot(form);
        after.put("reopenReason", reason);
        writeAudit(form.getId(), "REOPEN", adminActor.getId(), before, after);
        return form;
    }

    // ── List + history ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<I9SummaryResponse> list(I9Status status, boolean overdueOnly, Pageable pageable) {
        // Phase-3 sweep — fetch the toSummary graph (candidate + user) eagerly
        // so this list doesn't 500 under spring.jpa.open-in-view=false.
        Page<I9Form> page = status != null
                ? formRepository.findByStatusWithGraph(status, pageable)
                : formRepository.findAllWithGraph(pageable);

        List<I9SummaryResponse> rows = page.getContent().stream()
                .map(this::toSummary)
                .filter(r -> !overdueOnly || r.isOverdue())
                .toList();

        // If we filtered for overdue, totalElements no longer reflects the filtered count.
        // Acceptable for v1 — the dashboard sees current page counts only.
        return new PageImpl<>(rows, pageable,
                overdueOnly ? rows.size() : page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<I9HistoryEntryResponse> getHistory(UUID formId, User caller) {
        I9Form form = formRepository.findByIdWithGraph(formId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "I-9 form not found: " + formId));
        requireReadAccess(form, caller);

        return auditLogRepository
                .findByEntityTypeAndEntityIdOrderByTimestampDesc("I9Form", formId)
                .stream()
                .map(this::toHistoryEntry)
                .toList();
    }

    private I9HistoryEntryResponse toHistoryEntry(AuditLog entry) {
        User performer = entry.getUserId() != null
                ? userRepository.findById(entry.getUserId()).orElse(null)
                : null;
        String performerName = performer != null ? performer.getFullName() : "System";
        String performerRole = performer != null && performer.getRoles() != null
                && !performer.getRoles().isEmpty()
                ? performer.getRoles().iterator().next().name()
                : null;

        String summary = switch (entry.getAction()) {
            case "CREATE" -> "I-9 form created";
            case "SECTION_1_DRAFT_SAVE" -> "Section 1 draft saved";
            case "SECTION_1_SUBMIT" -> "Section 1 submitted by " + performerName;
            case "SECTION_2_DRAFT_SAVE" -> "Section 2 draft saved";
            case "SECTION_2_SUBMIT" -> "Section 2 completed and signed by " + performerName;
            case "REOPEN" -> "Form reopened by " + performerName + " (administrative action)";
            default -> entry.getAction();
        };

        return I9HistoryEntryResponse.builder()
                .auditId(entry.getId())
                .timestamp(entry.getTimestamp())
                .action(entry.getAction())
                .performedByName(performerName)
                .performedByRole(performerRole)
                .summary(summary)
                .build();
    }

    // ── Permission helpers ──────────────────────────────────────────────────

    public void requireReadAccess(I9Form form, User caller) {
        if (caller == null) {
            throw new AccessDeniedException("Authentication required");
        }
        if (caller.getRoles() != null
                && caller.getRoles().stream().anyMatch(READ_PRIVILEGED::contains)) {
            return;
        }
        if (form.getCandidate() != null
                && form.getCandidate().getUser() != null
                && form.getCandidate().getUser().getId().equals(caller.getId())) {
            return;
        }
        throw new AccessDeniedException("Not allowed to view this I-9 form");
    }

    private void requireSection1WriteAccess(I9Form form, User caller) {
        if (caller == null) {
            throw new AccessDeniedException("Authentication required");
        }
        // SUPER_ADMIN corrective bypass — see I9Controller.saveSection1.
        if (caller.getRoles() != null && caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            return;
        }
        // Candidates can only edit their own form's Section 1.
        if (caller.getRoles() != null && (caller.getRoles().contains(UserRole.INTERN) || caller.getRoles().contains(UserRole.INTERN))
                && form.getCandidate() != null
                && form.getCandidate().getUser() != null
                && form.getCandidate().getUser().getId().equals(caller.getId())) {
            return;
        }
        throw new AccessDeniedException("Not allowed to edit Section 1 of this I-9 form");
    }

    // ── Post-offer gate (GAP A1) ────────────────────────────────────────────

    /**
     * Hard 403 gate for candidate-initiated I-9 paths. Eligibility predicate:
     *   - candidate has at least one Offer in OfferStatus.ACCEPTED, AND
     *   - candidate has NO Engagement in EngagementStatus.BLOCKED_NO_AUTHORIZATION.
     *
     * Both checks are required: an accepted offer that was later flipped to
     * BLOCKED by the track router must NOT unlock I-9 — that candidate is
     * referred to HR/legal, not to the I-9 form.
     *
     * Writes a single AuditLog row (action=I9_BLOCKED_NO_OFFER, entityType=
     * Candidate) on block before throwing, so denied attempts are forensically
     * visible. Staff-side reads/edits never reach this method — the staff
     * controller path uses getOrCreateForCandidate directly.
     */
    private void requireCandidateOfferEligibility(Candidate candidate, User actor) {
        UUID actorId = actor != null ? actor.getId() : null;
        UUID candidateId = candidate != null ? candidate.getId() : null;
        if (candidate == null) {
            // Defensive — shouldn't happen because callers resolve the candidate
            // first, but a null here would silently bypass the gate otherwise.
            writeGateAudit(candidateId, "I9_BLOCKED_NO_OFFER", actorId,
                    "candidate=null");
            throw new OfferRequiredException(
                    "I-9 is available after your offer is accepted.");
        }
        boolean blocked = engagementRepository.findByCandidateId(candidate.getId()).stream()
                .anyMatch(e -> e.getStatus() == EngagementStatus.BLOCKED_NO_AUTHORIZATION);
        if (blocked) {
            writeGateAudit(candidateId, "I9_BLOCKED_NO_OFFER", actorId,
                    "engagement=BLOCKED_NO_AUTHORIZATION");
            throw new OfferRequiredException(
                    "Your engagement is on hold pending HR review. I-9 isn't available yet.");
        }
        boolean hasAccepted = offerRepository
                .findByApplication_Candidate_IdOrderByCreatedAtDesc(candidate.getId())
                .stream()
                .anyMatch(o -> o.getStatus() == OfferStatus.ACCEPTED);
        if (!hasAccepted) {
            writeGateAudit(candidateId, "I9_BLOCKED_NO_OFFER", actorId,
                    "noAcceptedOffer");
            throw new OfferRequiredException(
                    "I-9 is available after your offer is accepted.");
        }
    }

    private boolean isAdmin(User actor) {
        // A1-gate bypass: was the old ADMIN-only corrective path. After the §7
        // fold ADMIN→OPERATIONS this checked OPERATIONS; with SUPER_ADMIN split
        // back out, god-mode lives on SUPER_ADMIN only — OPERATIONS must run
        // the gate like everyone else.
        return actor != null
                && actor.getRoles() != null
                && actor.getRoles().contains(UserRole.SUPER_ADMIN);
    }

    private void writeGateAudit(UUID candidateId, String action, UUID actorId, String reason) {
        // Lean AuditLog row — afterJson holds the structured "why we blocked".
        // Mirrors the existing AuditLog.builder() pattern used by writeAudit
        // but keyed on Candidate (not I9Form) because the block happens
        // before any form id exists.
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

    // ── Computed helpers ────────────────────────────────────────────────────

    /** Naive business-day skip — weekends only, no federal holidays for v1. */
    public static LocalDate plusBusinessDays(LocalDate start, int days) {
        if (start == null) return null;
        LocalDate d = start;
        int added = 0;
        while (added < days) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY
                    && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return d;
    }

    private LocalDate mostRecentAcceptedOfferStartDate(Candidate candidate) {
        return offerRepository
                .findByApplication_Candidate_IdOrderByCreatedAtDesc(candidate.getId())
                .stream()
                .filter(o -> o.getStatus() == OfferStatus.ACCEPTED)
                .map(Offer::getStartDate)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation") // populating the legacy `overdue` alias intentionally
    public I9FormResponse toResponse(I9Form f) {
        Candidate candidate = f.getCandidate();
        User candidateUser = candidate != null ? candidate.getUser() : null;
        // Phase 3 step 5 — prefer the persisted due-date columns when set.
        // Fall back to deriving from firstDayOfEmployment so legacy I-9 rows
        // (which lack the columns until ddl-auto adds them) still surface a
        // due date.
        LocalDate section1Due = f.getSection1DueDate() != null
                ? f.getSection1DueDate()
                : f.getFirstDayOfEmployment();
        LocalDate section2Due = f.getSection2DueDate() != null
                ? f.getSection2DueDate()
                : (f.getFirstDayOfEmployment() != null
                        ? plusBusinessDays(f.getFirstDayOfEmployment(), SECTION_2_DEADLINE_BUSINESS_DAYS)
                        : null);
        LocalDate today = LocalDate.now();
        boolean section1Overdue = section1Due != null
                && f.getSection1SignedAt() == null
                && section1Due.isBefore(today);
        boolean section2Overdue = section2Due != null
                && f.getStatus() != I9Status.COMPLETED
                && f.getSection2SignedAt() == null
                && section2Due.isBefore(today);
        Long daysUntilDue = section2Due != null
                ? ChronoUnit.DAYS.between(today, section2Due)
                : null;

        String section2By = f.getSection2SignedByUserId() != null
                ? userRepository.findById(f.getSection2SignedByUserId())
                        .map(User::getFullName).orElse(null)
                : null;

        return I9FormResponse.builder()
                .id(f.getId())
                .candidateId(candidate != null ? candidate.getId() : null)
                .candidateName(candidateUser != null ? candidateUser.getFullName() : null)
                .candidateEmail(candidateUser != null ? candidateUser.getEmail() : null)
                .status(f.getStatus())
                // Section 1
                .lastName(f.getLastName())
                .firstName(f.getFirstName())
                .middleInitial(f.getMiddleInitial())
                .otherLastNamesUsed(f.getOtherLastNamesUsed())
                .addressStreet(f.getAddressStreet())
                .addressAptNumber(f.getAddressAptNumber())
                .addressCity(f.getAddressCity())
                .addressState(f.getAddressState())
                .addressZipCode(f.getAddressZipCode())
                .dateOfBirth(f.getDateOfBirth())
                .ssn(f.getSsn())
                .email(f.getEmail())
                .phoneNumber(f.getPhoneNumber())
                .citizenshipStatus(f.getCitizenshipStatus())
                .alienRegistrationNumber(f.getAlienRegistrationNumber())
                .foreignPassportNumber(f.getForeignPassportNumber())
                .foreignPassportCountry(f.getForeignPassportCountry())
                .workAuthExpirationDate(f.getWorkAuthExpirationDate())
                .preparerTranslatorUsed(f.getPreparerTranslatorUsed())
                .section1SignedAt(f.getSection1SignedAt())
                .section1SignedByName(f.getSection1SignedByName())
                // Section 2
                .firstDayOfEmployment(f.getFirstDayOfEmployment())
                .listATitle(f.getListATitle())
                .listAIssuingAuthority(f.getListAIssuingAuthority())
                .listADocumentNumber(f.getListADocumentNumber())
                .listAExpirationDate(f.getListAExpirationDate())
                .listBTitle(f.getListBTitle())
                .listBIssuingAuthority(f.getListBIssuingAuthority())
                .listBDocumentNumber(f.getListBDocumentNumber())
                .listBExpirationDate(f.getListBExpirationDate())
                .listCTitle(f.getListCTitle())
                .listCIssuingAuthority(f.getListCIssuingAuthority())
                .listCDocumentNumber(f.getListCDocumentNumber())
                .additionalInformation(f.getAdditionalInformation())
                .employerName(f.getEmployerName())
                .employerTitle(f.getEmployerTitle())
                .businessOrganizationName(f.getBusinessOrganizationName())
                .businessAddress(f.getBusinessAddress())
                .section2SignedAt(f.getSection2SignedAt())
                .section2SignedByName(section2By)
                // Computed (Phase 3 step 5 split)
                .section1DueDate(section1Due)
                .section2DueDate(section2Due)
                .section1Overdue(section1Overdue)
                .section2Overdue(section2Overdue)
                .overdue(section2Overdue) // legacy alias
                .daysUntilDue(daysUntilDue)
                .createdAt(f.getCreatedAt())
                .updatedAt(f.getUpdatedAt())
                .build();
    }

    @SuppressWarnings("deprecation") // populating the legacy `overdue` alias intentionally
    public I9SummaryResponse toSummary(I9Form f) {
        Candidate candidate = f.getCandidate();
        User candidateUser = candidate != null ? candidate.getUser() : null;
        // Same due-date resolution as toResponse — prefer persisted columns,
        // fall back to firstDayOfEmployment derivation for legacy rows.
        LocalDate section1Due = f.getSection1DueDate() != null
                ? f.getSection1DueDate()
                : f.getFirstDayOfEmployment();
        LocalDate section2Due = f.getSection2DueDate() != null
                ? f.getSection2DueDate()
                : (f.getFirstDayOfEmployment() != null
                        ? plusBusinessDays(f.getFirstDayOfEmployment(), SECTION_2_DEADLINE_BUSINESS_DAYS)
                        : null);
        LocalDate today = LocalDate.now();
        boolean section1Overdue = section1Due != null
                && f.getSection1SignedAt() == null
                && section1Due.isBefore(today);
        boolean section2Overdue = section2Due != null
                && f.getStatus() != I9Status.COMPLETED
                && f.getSection2SignedAt() == null
                && section2Due.isBefore(today);
        Long daysUntilDue = section2Due != null
                ? ChronoUnit.DAYS.between(today, section2Due)
                : null;

        String jobPostingTitle = null;
        if (candidate != null) {
            jobPostingTitle = offerRepository
                    .findByApplication_Candidate_IdOrderByCreatedAtDesc(candidate.getId())
                    .stream()
                    .filter(o -> o.getStatus() == OfferStatus.ACCEPTED)
                    .findFirst()
                    .map(o -> {
                        JobPosting jp = o.getApplication() != null
                                ? o.getApplication().getJobPosting() : null;
                        return jp != null ? jp.getTitle() : null;
                    })
                    .orElse(null);
        }

        return I9SummaryResponse.builder()
                .id(f.getId())
                .candidateId(candidate != null ? candidate.getId() : null)
                .candidateName(candidateUser != null ? candidateUser.getFullName() : null)
                .candidateEmail(candidateUser != null ? candidateUser.getEmail() : null)
                .jobPostingTitle(jobPostingTitle)
                .status(f.getStatus())
                .firstDayOfEmployment(f.getFirstDayOfEmployment())
                .section1DueDate(section1Due)
                .section2DueDate(section2Due)
                .section1Overdue(section1Overdue)
                .section2Overdue(section2Overdue)
                .overdue(section2Overdue) // legacy alias
                .daysUntilDue(daysUntilDue)
                .build();
    }

    // ── Audit log ───────────────────────────────────────────────────────────

    /**
     * Snapshot every persisted field. Note: SSN is intentionally included
     * because the audit log IS the legal version history for I-9 changes.
     * If/when audit log encryption ships in Sprint 4, this snapshot benefits
     * automatically.
     */
    private Map<String, Object> snapshot(I9Form f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("candidateId", f.getCandidate() != null ? f.getCandidate().getId() : null);
        m.put("engagementId", f.getEngagement() != null ? f.getEngagement().getId() : null);
        m.put("status", f.getStatus());
        m.put("section1DueDate", f.getSection1DueDate());
        m.put("section2DueDate", f.getSection2DueDate());
        // Section 1
        m.put("lastName", f.getLastName());
        m.put("firstName", f.getFirstName());
        m.put("middleInitial", f.getMiddleInitial());
        m.put("otherLastNamesUsed", f.getOtherLastNamesUsed());
        m.put("addressStreet", f.getAddressStreet());
        m.put("addressAptNumber", f.getAddressAptNumber());
        m.put("addressCity", f.getAddressCity());
        m.put("addressState", f.getAddressState());
        m.put("addressZipCode", f.getAddressZipCode());
        m.put("dateOfBirth", f.getDateOfBirth());
        m.put("ssn", f.getSsn());
        m.put("email", f.getEmail());
        m.put("phoneNumber", f.getPhoneNumber());
        m.put("citizenshipStatus", f.getCitizenshipStatus());
        m.put("alienRegistrationNumber", f.getAlienRegistrationNumber());
        m.put("foreignPassportNumber", f.getForeignPassportNumber());
        m.put("foreignPassportCountry", f.getForeignPassportCountry());
        m.put("workAuthExpirationDate", f.getWorkAuthExpirationDate());
        m.put("preparerTranslatorUsed", f.getPreparerTranslatorUsed());
        m.put("section1SignedAt", f.getSection1SignedAt());
        m.put("section1SignedByName", f.getSection1SignedByName());
        // Section 2
        m.put("firstDayOfEmployment", f.getFirstDayOfEmployment());
        m.put("listATitle", f.getListATitle());
        m.put("listAIssuingAuthority", f.getListAIssuingAuthority());
        m.put("listADocumentNumber", f.getListADocumentNumber());
        m.put("listAExpirationDate", f.getListAExpirationDate());
        m.put("listBTitle", f.getListBTitle());
        m.put("listBIssuingAuthority", f.getListBIssuingAuthority());
        m.put("listBDocumentNumber", f.getListBDocumentNumber());
        m.put("listBExpirationDate", f.getListBExpirationDate());
        m.put("listCTitle", f.getListCTitle());
        m.put("listCIssuingAuthority", f.getListCIssuingAuthority());
        m.put("listCDocumentNumber", f.getListCDocumentNumber());
        m.put("additionalInformation", f.getAdditionalInformation());
        m.put("employerName", f.getEmployerName());
        m.put("employerTitle", f.getEmployerTitle());
        m.put("businessOrganizationName", f.getBusinessOrganizationName());
        m.put("businessAddress", f.getBusinessAddress());
        m.put("section2SignedAt", f.getSection2SignedAt());
        m.put("section2SignedByUserId", f.getSection2SignedByUserId());
        return m;
    }

    private void writeAudit(UUID formId, String action, UUID userId,
                            Map<String, Object> before, Map<String, Object> after) {
        AuditLog entry = AuditLog.builder()
                .entityType("I9Form")
                .entityId(formId)
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
            log.warn("Failed to serialize I-9 audit snapshot: {}", e.getMessage());
            return new HashMap<>(snapshot).toString();
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────────

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
