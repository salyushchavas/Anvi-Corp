package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.offer.CandidateOfferResponse;
import com.anvicorp.api.dto.offer.CreateOfferRequest;
import com.anvicorp.api.dto.offer.DeclineOfferRequest;
import com.anvicorp.api.dto.offer.OfferResponse;
import com.anvicorp.api.dto.offer.OfferSummaryResponse;
import com.anvicorp.api.dto.offer.UpdateOfferRequest;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.InterviewRequiredException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.notification.NotificationService;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lifecycle of an Offer:
 *   DRAFT  -> SENT  -> ACCEPTED | DECLINED | EXPIRED | REVOKED
 *   DRAFT  -> deleted
 *
 * Application status side effects:
 *   send()    -> if app is still in the funnel, flip to OFFERED
 *   accept()  -> always flip app to ACCEPTED
 *   decline() -> always flip app to REJECTED (no separate DECLINED status in the enum)
 *
 * Application enum mapping (spec naming -> real enum):
 *   OFFER_EXTENDED -> OFFERED
 *   OFFER_ACCEPTED / HIRED -> ACCEPTED
 *   OFFER_DECLINED -> REJECTED (existing convention; REJECTED is overloaded)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OfferService {

    /**
     * GAP_REPORT A3 — hard allow-list for offer creation. An offer may be
     * extended ONLY when the application has reached an interview decision
     * (INTERVIEWED), conditional selection (SELECTED_CONDITIONAL), or is
     * already OFFERED (revision / re-send path against an existing offer).
     * Every other source state is refused with 403 INTERVIEW_REQUIRED. There
     * is no admin / HR override — PED rule 3.
     */
    private static final Set<ApplicationStatus> OFFER_ALLOWED_FROM = EnumSet.of(
            ApplicationStatus.INTERVIEWED,
            ApplicationStatus.SELECTED_CONDITIONAL,
            // existing offer being revised / re-sent
            ApplicationStatus.OFFERED);

    /** Privileged staff roles that get the full OfferResponse view. */
    private static final Set<UserRole> STAFF_ROLES = EnumSet.of(UserRole.ERM, UserRole.ERM);

    private final OfferRepository offerRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final OfferLetterTemplate letterTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationService applicationService;
    private final EngagementService engagementService;
    private final ComplianceRoutingService complianceRoutingService;
    private final NotificationService notificationService;

    // ── Commands ────────────────────────────────────────────────────────────

    @Transactional
    public OfferResponse create(CreateOfferRequest req, User creator) {
        Application application = applicationRepository.findById(req.getApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + req.getApplicationId()));

        // GAP A3 hard gate — refuse any offer create from a pre-interview
        // state. The OFFERED branch covers an existing-offer revision path.
        // No admin/HR override.
        if (!OFFER_ALLOWED_FROM.contains(application.getStatus())) {
            throw new InterviewRequiredException(
                    "Offer requires an interview decision first (application status: "
                            + application.getStatus() + ").");
        }

        if (req.getExpectedEndDate() != null
                && !req.getExpectedEndDate().isAfter(req.getStartDate())) {
            throw new BadRequestException("expectedEndDate must be after startDate");
        }
        if (req.getStartDate().isBefore(LocalDate.now())) {
            throw new BadRequestException("startDate must be today or in the future");
        }

        int daysToRespond = req.getDaysToRespond() != null ? req.getDaysToRespond() : 7;
        Instant expiresAt = Instant.now().plus(daysToRespond, ChronoUnit.DAYS);
        String currency = req.getCompensationCurrency() != null
                ? req.getCompensationCurrency().toUpperCase()
                : "USD";

        Offer offer = Offer.builder()
                .application(application)
                .compensationAmount(req.getCompensationAmount())
                .compensationFrequency(req.getCompensationFrequency())
                .compensationCurrency(currency)
                .startDate(req.getStartDate())
                .expectedEndDate(req.getExpectedEndDate())
                .expiresAt(expiresAt)
                .status(OfferStatus.DRAFT)
                .additionalTerms(req.getAdditionalTerms())
                .createdBy(creator.getId())
                .build();

        Candidate candidate = application.getCandidate();
        JobPosting posting = application.getJobPosting();
        StaffingEntity entity = posting != null ? posting.getEntity() : null;
        offer.setLetterContent(letterTemplate.generate(candidate, posting, entity, offer));

        offer = offerRepository.save(offer);
        writeAudit("Offer", offer.getId(), "CREATE", creator.getId(),
                null, snapshot(offer));
        return toResponse(offer);
    }

    @Transactional
    public OfferResponse update(UUID offerId, UpdateOfferRequest req, User actor) {
        Offer offer = offerRepository.findByIdWithGraph(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Offer not found: " + offerId));

        if (offer.getStatus() != OfferStatus.DRAFT) {
            throw new BadRequestException(
                    "Cannot edit offer in status " + offer.getStatus()
                            + " — only DRAFT offers are editable");
        }

        Map<String, Object> before = snapshot(offer);

        boolean compChanged = false;
        if (req.getCompensationAmount() != null) {
            offer.setCompensationAmount(req.getCompensationAmount());
            compChanged = true;
        }
        if (req.getCompensationFrequency() != null) {
            offer.setCompensationFrequency(req.getCompensationFrequency());
            compChanged = true;
        }
        if (req.getCompensationCurrency() != null) {
            offer.setCompensationCurrency(req.getCompensationCurrency().toUpperCase());
            compChanged = true;
        }
        if (req.getStartDate() != null) {
            offer.setStartDate(req.getStartDate());
            compChanged = true;
        }
        if (req.getExpectedEndDate() != null) {
            offer.setExpectedEndDate(req.getExpectedEndDate());
            compChanged = true;
        }
        if (req.getDaysToRespond() != null) {
            offer.setExpiresAt(offer.getCreatedAt() != null
                    ? offer.getCreatedAt().plus(req.getDaysToRespond(), ChronoUnit.DAYS)
                    : Instant.now().plus(req.getDaysToRespond(), ChronoUnit.DAYS));
        }
        if (req.getAdditionalTerms() != null) {
            offer.setAdditionalTerms(req.getAdditionalTerms());
            compChanged = true;
        }

        if (offer.getExpectedEndDate() != null
                && !offer.getExpectedEndDate().isAfter(offer.getStartDate())) {
            throw new BadRequestException("expectedEndDate must be after startDate");
        }

        if (req.getLetterContent() != null) {
            offer.setLetterContent(req.getLetterContent());
        } else if (compChanged) {
            Application app = offer.getApplication();
            Candidate candidate = app != null ? app.getCandidate() : null;
            JobPosting posting = app != null ? app.getJobPosting() : null;
            StaffingEntity entity = posting != null ? posting.getEntity() : null;
            offer.setLetterContent(letterTemplate.generate(candidate, posting, entity, offer));
        }

        offer = offerRepository.save(offer);
        writeAudit("Offer", offer.getId(), "UPDATE", actor.getId(),
                before, snapshot(offer));
        return toResponse(offer);
    }

    @Transactional
    public OfferResponse send(UUID offerId, User sender) {
        Offer offer = offerRepository.findByIdWithGraph(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Offer not found: " + offerId));

        if (offer.getStatus() != OfferStatus.DRAFT) {
            throw new BadRequestException(
                    "Cannot send offer in status " + offer.getStatus() + " — only DRAFT can be sent");
        }
        if (offer.getExpiresAt() != null && offer.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException(
                    "Offer has expired before being sent; edit to extend response window");
        }

        Map<String, Object> before = snapshot(offer);
        Instant now = Instant.now();
        offer.setStatus(OfferStatus.SENT);
        offer.setSentAt(now);
        offer = offerRepository.save(offer);

        Application application = offer.getApplication();
        if (application != null && !isOfferTerminalForApp(application.getStatus())) {
            // GAP A3: only INTERVIEWED / SELECTED_CONDITIONAL are now legal
            // sources for OFFERED in LEGAL_TRANSITIONS. create() already
            // blocked any pre-interview application, so this transition can
            // only fire from one of the two allowed states (or from OFFERED
            // itself, which is a same-state no-op). transitionTo writes the
            // single STATUS_CHANGE audit row internally.
            applicationService.transitionTo(application, ApplicationStatus.OFFERED,
                    "STATUS_CHANGE", sender);
        }

        writeAudit("Offer", offer.getId(), "SEND", sender.getId(), before, snapshot(offer));

        // Batch-1 notification — applicant receives the offer with comp + start
        // + view link. Best-effort: a send failure must NOT block the SEND.
        try {
            notificationService.sendOfferExtended(offer);
        } catch (Exception e) {
            log.warn("OFFER_EXTENDED notify failed (non-fatal) for {}: {}",
                    offer.getId(), e.getMessage());
        }
        return toResponse(offer);
    }

    @Transactional
    public Offer acceptInternal(UUID offerId, User candidateUser) {
        Offer offer = offerRepository.findByIdWithGraph(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Offer not found: " + offerId));
        ensureCandidateOwns(offer, candidateUser);

        if (lazyExpireIfNeeded(offer)) {
            throw new BadRequestException(
                    "Offer has expired and can no longer be accepted");
        }
        if (offer.getStatus() != OfferStatus.SENT) {
            throw new BadRequestException(
                    "Cannot accept offer in status " + offer.getStatus());
        }

        Map<String, Object> before = snapshot(offer);
        Instant now = Instant.now();
        offer.setStatus(OfferStatus.ACCEPTED);
        offer.setRespondedAt(now);
        offer = offerRepository.save(offer);

        Application application = offer.getApplication();
        if (application != null) {
            // OFFERED → ACCEPTED is legal in LEGAL_TRANSITIONS; transitionTo
            // writes the single STATUS_CHANGE audit row.
            applicationService.transitionTo(application, ApplicationStatus.ACCEPTED,
                    "STATUS_CHANGE", candidateUser);
        }

        writeAudit("Offer", offer.getId(), "ACCEPT", candidateUser.getId(),
                before, snapshot(offer));

        // Phase 3 step 8 — engagement now precedes the onboarding seed so the
        // seeded tasks can carry an engagement_id. REQUIRES_NEW inside the
        // engagement create + best-effort try/catch out here means a
        // compliance-side bug still never blocks acceptance.
        com.anvicorp.api.entity.Engagement engagement = null;
        try {
            engagement = engagementService.createForAcceptedOffer(offer, candidateUser);
        } catch (Exception e) {
            log.warn("Failed to create engagement for accepted offer {}: {}",
                    offer.getId(), e.getMessage(), e);
        }

        // Phase 3 step 4 — track router. Set per-track onboarding requirements
        // and (when authorization is missing) flip to BLOCKED_NO_AUTHORIZATION.
        // The router catches its own exceptions internally; the outer try is
        // belt-and-braces against a broken bean wiring.
        if (engagement != null) {
            try {
                complianceRoutingService.routeNewEngagement(engagement, candidateUser);
            } catch (Exception e) {
                log.warn("Failed to route engagement {} for accepted offer {}: {}",
                        engagement.getId(), offer.getId(), e.getMessage(), e);
            }
        }

        // Batch-1 notification — TWO sends: applicant confirmation + ops
        // heads-up. The service handles both, idempotent per (event, offer).
        // Best-effort: a send failure must NOT roll back acceptance.
        try {
            notificationService.sendOfferAccepted(offer);
        } catch (Exception e) {
            log.warn("OFFER_ACCEPTED notify failed (non-fatal) for {}: {}",
                    offer.getId(), e.getMessage());
        }
        return offer;
    }

    @Transactional
    public Offer declineInternal(UUID offerId, DeclineOfferRequest req, User candidateUser) {
        Offer offer = offerRepository.findByIdWithGraph(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Offer not found: " + offerId));
        ensureCandidateOwns(offer, candidateUser);

        if (lazyExpireIfNeeded(offer)) {
            throw new BadRequestException(
                    "Offer has expired and can no longer be declined");
        }
        if (offer.getStatus() != OfferStatus.SENT) {
            throw new BadRequestException(
                    "Cannot decline offer in status " + offer.getStatus());
        }

        Map<String, Object> before = snapshot(offer);
        Instant now = Instant.now();
        offer.setStatus(OfferStatus.DECLINED);
        offer.setRespondedAt(now);
        offer.setDeclineReason(req != null ? req.getReason() : null);
        offer = offerRepository.save(offer);

        Application application = offer.getApplication();
        if (application != null) {
            // REJECTED is reachable from every non-terminal state in
            // LEGAL_TRANSITIONS. transitionTo writes the audit row.
            applicationService.transitionTo(application, ApplicationStatus.REJECTED,
                    "STATUS_CHANGE", candidateUser);
        }

        writeAudit("Offer", offer.getId(), "DECLINE", candidateUser.getId(),
                before, snapshot(offer));
        return offer;
    }

    @Transactional
    public OfferResponse revoke(UUID offerId, User actor) {
        Offer offer = offerRepository.findByIdWithGraph(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Offer not found: " + offerId));

        if (offer.getStatus() != OfferStatus.SENT) {
            throw new BadRequestException(
                    "Cannot revoke offer in status " + offer.getStatus()
                            + " — only SENT offers can be revoked");
        }

        Map<String, Object> before = snapshot(offer);
        Instant now = Instant.now();
        offer.setStatus(OfferStatus.REVOKED);
        offer.setRevokedAt(now);
        offer.setRevokedBy(actor.getId());
        offer = offerRepository.save(offer);
        writeAudit("Offer", offer.getId(), "REVOKE", actor.getId(),
                before, snapshot(offer));
        return toResponse(offer);
    }

    @Transactional
    public void delete(UUID offerId, User actor) {
        Offer offer = offerRepository.findByIdWithGraph(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Offer not found: " + offerId));
        if (offer.getStatus() != OfferStatus.DRAFT) {
            throw new BadRequestException(
                    "Cannot delete offer in status " + offer.getStatus()
                            + " — only DRAFT offers can be deleted");
        }
        writeAudit("Offer", offer.getId(), "DELETE", actor.getId(),
                snapshot(offer), null);
        offerRepository.delete(offer);
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    @Transactional
    public OfferResponse getDetailStaff(UUID offerId, User caller) {
        Offer offer = loadAndLazyExpire(offerId);
        return toResponse(offer);
    }

    @Transactional
    public CandidateOfferResponse getDetailCandidate(UUID offerId, User candidateUser) {
        Offer offer = offerRepository.findByIdWithGraph(offerId).orElse(null);
        if (offer == null
                || !candidateOwnsSilent(offer, candidateUser)
                || offer.getStatus() == OfferStatus.DRAFT) {
            // Don't leak existence of an offer the candidate doesn't own or that's still a draft.
            throw new ResourceNotFoundException("Offer not found: " + offerId);
        }
        lazyExpireIfNeeded(offer);
        return toCandidateResponse(offer);
    }

    @Transactional
    public Page<OfferSummaryResponse> list(OfferStatus status, UUID applicationId, Pageable pageable) {
        Page<Offer> page;
        if (applicationId != null) {
            List<Offer> all = offerRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId);
            if (status != null) {
                all = all.stream().filter(o -> o.getStatus() == status).toList();
            }
            int from = (int) Math.min(pageable.getOffset(), all.size());
            int to = Math.min(from + pageable.getPageSize(), all.size());
            page = new PageImpl<>(all.subList(from, to), pageable, all.size());
        } else if (status != null) {
            page = offerRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else {
            page = offerRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        // Lazy-expire any SENT-but-stale offers in the current page.
        List<Offer> resolved = new ArrayList<>(page.getNumberOfElements());
        for (Offer o : page.getContent()) {
            lazyExpireIfNeeded(o);
            resolved.add(o);
        }
        return new PageImpl<>(resolved, pageable, page.getTotalElements()).map(this::toSummary);
    }

    @Transactional
    public List<CandidateOfferResponse> listForCandidate(User candidateUser) {
        // Fetch with the full application → candidate → user + jobPosting → entity
        // graph so toCandidateResponse never lazy-loads after this method returns.
        List<Offer> offers = offerRepository
                .findByCandidateUserIdWithGraph(candidateUser.getId());

        List<CandidateOfferResponse> result = new ArrayList<>(offers.size());
        for (Offer o : offers) {
            lazyExpireIfNeeded(o);
            if (o.getStatus() == OfferStatus.DRAFT) continue;
            result.add(toCandidateResponse(o));
        }
        return result;
    }

    @Transactional
    public LetterDownload buildDownload(UUID offerId, User caller) {
        boolean isCandidateView = isCandidateOnly(caller);
        Offer offer;
        if (isCandidateView) {
            // Re-uses the candidate-side checks (404 on draft / not-owned).
            getDetailCandidate(offerId, caller);
            offer = offerRepository.findByIdWithGraph(offerId).orElseThrow();
        } else {
            offer = loadAndLazyExpire(offerId);
        }
        Candidate candidate = offer.getApplication() != null ? offer.getApplication().getCandidate() : null;
        User user = candidate != null ? candidate.getUser() : null;
        String fullName = user != null && user.getFullName() != null ? user.getFullName() : "candidate";
        String lastNamePiece = fullName;
        int sp = fullName.lastIndexOf(' ');
        if (sp >= 0 && sp + 1 < fullName.length()) {
            lastNamePiece = fullName.substring(sp + 1);
        }
        String safeLast = lastNamePiece.replaceAll("[^A-Za-z0-9_-]+", "");
        if (safeLast.isEmpty()) safeLast = "candidate";
        String shortId = offer.getId().toString().substring(0, 8);
        String filename = "Offer-" + safeLast + "-" + shortId + ".txt";
        // GAP E6 — sensitive PII event (compensation, signed letter contents).
        // Audit BEFORE returning the bytes so a downstream wire failure can't
        // hide the access. Reuses the existing writeAudit pattern; null before
        // + after since download mutates nothing.
        writeAudit("Offer", offer.getId(), "OFFER_DOWNLOADED",
                caller != null ? caller.getId() : null, null, null);
        return new LetterDownload(filename, offer.getLetterContent() != null ? offer.getLetterContent() : "");
    }

    public record LetterDownload(String filename, String body) {}

    // ── Helpers ─────────────────────────────────────────────────────────────

    private boolean isOfferTerminalForApp(ApplicationStatus s) {
        return s == ApplicationStatus.OFFERED
                || s == ApplicationStatus.ACCEPTED
                || s == ApplicationStatus.REJECTED
                || s == ApplicationStatus.WITHDRAWN;
    }

    private Offer loadAndLazyExpire(UUID offerId) {
        Offer offer = offerRepository.findByIdWithGraph(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Offer not found: " + offerId));
        lazyExpireIfNeeded(offer);
        return offer;
    }

    /** Returns true if the offer was just expired (or was already expired). */
    private boolean lazyExpireIfNeeded(Offer offer) {
        if (offer.getStatus() == OfferStatus.SENT
                && offer.getExpiresAt() != null
                && offer.getExpiresAt().isBefore(Instant.now())) {
            Map<String, Object> before = snapshot(offer);
            offer.setStatus(OfferStatus.EXPIRED);
            offerRepository.save(offer);
            writeAudit("Offer", offer.getId(), "EXPIRE", null, before, snapshot(offer));
            return true;
        }
        return offer.getStatus() == OfferStatus.EXPIRED;
    }

    private void ensureCandidateOwns(Offer offer, User candidateUser) {
        if (!candidateOwnsSilent(offer, candidateUser)) {
            throw new ForbiddenException("This offer does not belong to you");
        }
    }

    private boolean candidateOwnsSilent(Offer offer, User candidateUser) {
        if (offer == null || candidateUser == null) return false;
        Application app = offer.getApplication();
        if (app == null || app.getCandidate() == null || app.getCandidate().getUser() == null) {
            return false;
        }
        return app.getCandidate().getUser().getId().equals(candidateUser.getId());
    }

    private boolean isCandidateOnly(User user) {
        if (user == null) return false;
        Set<UserRole> roles = user.getRoles();
        if (roles == null) return false;
        boolean isCandidate = (roles.contains(UserRole.INTERN) || roles.contains(UserRole.INTERN));
        boolean isStaff = roles.stream().anyMatch(STAFF_ROLES::contains);
        return isCandidate && !isStaff;
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    public OfferResponse toResponse(Offer o) {
        Application app = o.getApplication();
        Candidate candidate = app != null ? app.getCandidate() : null;
        User candidateUser = candidate != null ? candidate.getUser() : null;
        JobPosting posting = app != null ? app.getJobPosting() : null;
        StaffingEntity entity = posting != null ? posting.getEntity() : null;

        return OfferResponse.builder()
                .id(o.getId())
                .applicationId(app != null ? app.getId() : null)
                .candidateName(candidateUser != null ? candidateUser.getFullName() : null)
                .candidateEmail(candidateUser != null ? candidateUser.getEmail() : null)
                .candidateId(candidate != null ? candidate.getId() : null)
                .jobPostingTitle(posting != null ? posting.getTitle() : null)
                .jobPostingId(posting != null ? posting.getId() : null)
                .entityName(entity != null ? entity.getName() : null)
                .entityId(entity != null ? entity.getId() : null)
                .compensationAmount(o.getCompensationAmount())
                .compensationFrequency(o.getCompensationFrequency())
                .compensationCurrency(o.getCompensationCurrency())
                .startDate(o.getStartDate())
                .expectedEndDate(o.getExpectedEndDate())
                .expiresAt(o.getExpiresAt())
                .status(o.getStatus())
                .additionalTerms(o.getAdditionalTerms())
                .letterContent(o.getLetterContent())
                .declineReason(o.getDeclineReason())
                .sentAt(o.getSentAt())
                .respondedAt(o.getRespondedAt())
                .revokedAt(o.getRevokedAt())
                .createdAt(o.getCreatedAt())
                .createdByName(lookupUserName(o.getCreatedBy()))
                .updatedAt(o.getUpdatedAt())
                .isExpired(o.getStatus() == OfferStatus.SENT
                        && o.getExpiresAt() != null
                        && o.getExpiresAt().isBefore(Instant.now()))
                .build();
    }

    public OfferSummaryResponse toSummary(Offer o) {
        Application app = o.getApplication();
        Candidate candidate = app != null ? app.getCandidate() : null;
        User candidateUser = candidate != null ? candidate.getUser() : null;
        JobPosting posting = app != null ? app.getJobPosting() : null;
        StaffingEntity entity = posting != null ? posting.getEntity() : null;
        return OfferSummaryResponse.builder()
                .id(o.getId())
                .candidateName(candidateUser != null ? candidateUser.getFullName() : null)
                .jobPostingTitle(posting != null ? posting.getTitle() : null)
                .entityName(entity != null ? entity.getName() : null)
                .compensationAmount(o.getCompensationAmount())
                .compensationFrequency(o.getCompensationFrequency())
                .startDate(o.getStartDate())
                .expiresAt(o.getExpiresAt())
                .status(o.getStatus())
                .createdAt(o.getCreatedAt())
                .build();
    }

    public CandidateOfferResponse toCandidateResponse(Offer o) {
        Application app = o.getApplication();
        JobPosting posting = app != null ? app.getJobPosting() : null;
        StaffingEntity entity = posting != null ? posting.getEntity() : null;
        User candidateUser = app != null && app.getCandidate() != null
                ? app.getCandidate().getUser() : null;
        // Applicant-safe: only surface voided_reason if THIS offer is VOIDED
        // and the caller is the owner (always true here — controller restricts).
        String voidedReason = o.getStatus() == OfferStatus.VOIDED
                ? o.getVoidedReason() : null;
        // Look up the ERM contact who created the offer for the footer card.
        String createdByName = null;
        String createdByEmail = null;
        if (o.getCreatedBy() != null) {
            var creator = userRepository.findById(o.getCreatedBy()).orElse(null);
            if (creator != null) {
                createdByName = creator.getFullName();
                createdByEmail = creator.getEmail();
            }
        }
        return CandidateOfferResponse.builder()
                .id(o.getId())
                .jobPostingTitle(posting != null ? posting.getTitle() : null)
                .entityName(entity != null ? entity.getName() : null)
                .compensationAmount(o.getCompensationAmount())
                .compensationFrequency(o.getCompensationFrequency())
                .compensationCurrency(o.getCompensationCurrency())
                .startDate(o.getStartDate())
                .expectedEndDate(o.getExpectedEndDate())
                .expiresAt(o.getExpiresAt())
                .status(o.getStatus())
                .additionalTerms(o.getAdditionalTerms())
                .letterContent(o.getLetterContent())
                .sentAt(o.getSentAt())
                .respondedAt(o.getRespondedAt())
                .isExpired(o.getStatus() == OfferStatus.SENT
                        && o.getExpiresAt() != null
                        && o.getExpiresAt().isBefore(Instant.now()))
                // Phase 3 doc-spec fields.
                .roleTitle(o.getRoleTitle())
                .compensationSummary(o.getCompensationSummary())
                .worksite(o.getWorksite())
                .expectedHoursPerWeek(o.getExpectedHoursPerWeek())
                .legacyEnvelopeId(o.getLegacyEnvelopeId())
                .signedAt(o.getSignedAt())
                .voidedAt(o.getVoidedAt())
                .voidedReason(voidedReason)
                .signedPdfDocumentId(o.getSignedPdfDocumentId())
                .employeeId(candidateUser != null ? candidateUser.getEmployeeId() : null)
                .createdByName(createdByName)
                .createdByEmail(createdByEmail)
                .build();
    }

    private String lookupUserName(UUID userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(User::getFullName).orElse(null);
    }

    // ── Audit log ───────────────────────────────────────────────────────────

    private Map<String, Object> snapshot(Offer o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("applicationId", o.getApplication() != null ? o.getApplication().getId() : null);
        m.put("compensationAmount", o.getCompensationAmount());
        m.put("compensationFrequency", o.getCompensationFrequency());
        m.put("compensationCurrency", o.getCompensationCurrency());
        m.put("startDate", o.getStartDate());
        m.put("expectedEndDate", o.getExpectedEndDate());
        m.put("expiresAt", o.getExpiresAt());
        m.put("status", o.getStatus());
        m.put("additionalTerms", o.getAdditionalTerms());
        m.put("declineReason", o.getDeclineReason());
        m.put("sentAt", o.getSentAt());
        m.put("respondedAt", o.getRespondedAt());
        m.put("revokedAt", o.getRevokedAt());
        m.put("revokedBy", o.getRevokedBy());
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
