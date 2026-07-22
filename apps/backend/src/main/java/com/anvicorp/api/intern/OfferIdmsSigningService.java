package com.anvicorp.api.intern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.offer.SendOfferRequest;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.event.OfferSentEvent;
import com.anvicorp.api.event.OfferSignedEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Offer signing orchestrated through IDMS — Skyzen's in-house signing
 * tool. Renamed from the legacy {@code OfferDocuSignService}; the
 * dead third-party API methods (envelope create / void / status
 * refresh / webhook handler / archived-PDF download) have been removed
 * since they haven't been reachable since Phase 8.6.2 disabled the
 * external integration.
 *
 * <p>Endpoints / responsibilities:</p>
 * <ul>
 *   <li>{@link #sendOffer} — atomic create + dispatch the OFFER_LETTER
 *       email with a link to the IDMS signing page; advance the
 *       application stage + applicant lifecycle.</li>
 *   <li>{@link #voidOffer} — VOID a pre-signed offer; refuse if SIGNED.</li>
 *   <li>{@link #resendOffer} — re-render + re-send the OFFER_LETTER
 *       email so the applicant can reach the IDMS signing page again.</li>
 *   <li>{@link #recordIdmsSignature} — applicant signs at
 *       {@code /careers/intern/offer/sign/{id}}; image + typed name go
 *       into the offer row, lifecycle advances to EMPLOYEE_ID_CREATED.</li>
 *   <li>{@link #loadForApplicant} — ownership-checked offer load for the
 *       intern signing page.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OfferIdmsSigningService {

    private final OfferRepository offerRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final InternLifecycleRepository internLifecycleRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final InternLifecycleService internLifecycleService;
    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;
    private final com.anvicorp.api.erm.CommunicationTemplateService templateService;
    private final com.anvicorp.api.notification.EmailProvider emailProvider;
    private final ReportingStructureAutoLinker reportingStructureAutoLinker;
    private final com.anvicorp.api.service.EngagementService engagementService;
    private final com.anvicorp.api.config.BrandConfig brand;

    @Value("${app.frontend.base-url:https://www.anvicorp.com}")
    private String frontendBaseUrl;

    /** Phase 8.6.4 — single Trainer + Evaluator used org-wide. Blank means
     *  auto-link is disabled; ERM still has the legacy assignReporting endpoint
     *  for manual correction. */
    @Value("${app.default-trainer-email:}")
    private String defaultTrainerEmail;

    @Value("${app.default-evaluator-email:}")
    private String defaultEvaluatorEmail;

    // ── ERM commands ────────────────────────────────────────────────────────

    @Transactional
    public Offer sendOffer(SendOfferRequest req, User actor) {
        Application application = applicationRepository.findById(req.getApplicationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + req.getApplicationId()));

        // Stage guard: INTERVIEWED is the post-completion state.
        if (application.getStatus() != ApplicationStatus.INTERVIEWED) {
            throw new ConflictException(
                    "Offer requires application stage INTERVIEWED (current: "
                            + application.getStatus() + ")");
        }
        if (offerRepository.existsByApplicationId(application.getId())) {
            throw new ConflictException("Application already has an offer");
        }

        Candidate candidate = application.getCandidate();
        User applicantUser = candidate != null ? candidate.getUser() : null;
        if (applicantUser == null) {
            throw new BadRequestException("Application has no resolvable applicant user");
        }
        JobPosting jp = application.getJobPosting();

        int expiryDays = req.getExpiryDays() != null ? req.getExpiryDays() : 7;
        Instant expiresAt = Instant.now().plus(expiryDays, ChronoUnit.DAYS);

        String compSummary = req.getCompensationSummary() != null
                ? req.getCompensationSummary().trim() : "TBD";

        Offer offer = Offer.builder()
                .application(application)
                .compensationAmount(java.math.BigDecimal.ZERO)
                .compensationCurrency("USD")
                .compensationFrequency(safeFrequency())
                .startDate(req.getTentativeStartDate())
                .expiresAt(expiresAt)
                .status(OfferStatus.SENT)
                .letterContent(buildPlainLetter(req, applicantUser))
                .roleTitle(req.getRoleTitle())
                .compensationSummary(compSummary)
                .worksite(req.getWorksite())
                .expectedHoursPerWeek(req.getExpectedHoursPerWeek())
                .sentAt(Instant.now())
                .createdBy(actor != null ? actor.getId() : null)
                .build();
        offer = offerRepository.save(offer);

        log.info("[Offer] persisted via IDMS — offer={} status=SENT; applicant signs at "
                        + "/careers/intern/offer/sign/{}",
                offer.getId(), offer.getId());

        // Application stage + applicant lifecycle.
        application.setStatus(ApplicationStatus.OFFERED);
        application.setStatusUpdatedAt(Instant.now());
        application.setStatusUpdatedBy(actor != null ? actor.getId() : null);
        applicationRepository.save(application);

        internLifecycleService.advance(applicantUser,
                InternLifecycleStatus.OFFER_SENT,
                actor != null ? actor.getId() : null);

        writeAudit("Offer", offer.getId(), "SENT",
                actor != null ? actor.getId() : null, null, snapshot(offer));

        try {
            eventPublisher.publishEvent(new OfferSentEvent(
                    offer.getId(), application.getId(), applicantUser.getId()));
        } catch (Exception e) {
            log.warn("OfferSentEvent publish failed (non-fatal) for {}: {}",
                    offer.getId(), e.getMessage());
        }

        sendOfferLetterEmail(offer, req, applicantUser, actor, compSummary, expiryDays);
        return offer;
    }

    /**
     * Render + dispatch the OFFER_LETTER template email pointing at the
     * IDMS signing page. Used by both initial send and {@link #resendOffer}.
     * Non-fatal — failure is logged so the offer row still lands SENT
     * and ERM can retry.
     */
    private void sendOfferLetterEmail(Offer offer, SendOfferRequest req,
                                       User applicantUser, User actor,
                                       String compSummary, int expiryDays) {
        try {
            String signingLink = frontendBaseUrl.replaceAll("/$", "")
                    + "/careers/intern/offer/sign/" + offer.getId();
            Map<String, Object> vars = new LinkedHashMap<>();
            String firstName = applicantUser.getFullName() != null
                    ? applicantUser.getFullName().split("\\s+", 2)[0] : "there";
            vars.put("firstName", firstName);
            vars.put("roleTitle", req.getRoleTitle() != null ? req.getRoleTitle() : "");
            vars.put("tentativeStartDate", req.getTentativeStartDate() != null
                    ? req.getTentativeStartDate().toString() : "TBD");
            vars.put("compensationSummary", compSummary);
            vars.put("worksite", req.getWorksite() != null ? req.getWorksite() : "");
            vars.put("expectedHoursPerWeek", req.getExpectedHoursPerWeek() != null
                    ? req.getExpectedHoursPerWeek().toString() : "TBD");
            vars.put("contingencies",
                    "Subject to verification of work authorization.");
            vars.put("expiryDays", String.valueOf(expiryDays));
            vars.put("ermName", actor != null && actor.getFullName() != null
                    ? actor.getFullName() : brand.getName() + " ERM");
            vars.put("signingLink", signingLink);
            templateService.render("OFFER_LETTER", "EMAIL", vars).ifPresent(r ->
                    emailProvider.sendRendered(applicantUser.getEmail(),
                            r.subject() != null ? r.subject() : "Your " + brand.getName() + " offer",
                            r.body() != null ? r.body() : signingLink));
        } catch (Exception e) {
            log.warn("[Offer] applicant email render/send failed for offer={}: {}",
                    offer.getId(), e.getMessage());
        }
    }

    @Transactional
    public Offer voidOffer(UUID offerId, String reason, User actor) {
        if (reason == null || reason.trim().length() < 10) {
            throw new BadRequestException("voided_reason must be at least 10 characters");
        }
        Offer offer = offerRepository.findByIdWithGraph(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Offer not found: " + offerId));
        if (offer.getStatus() == OfferStatus.SIGNED) {
            throw new ConflictException(
                    "Cannot void a signed offer — process retraction manually.");
        }
        if (offer.getStatus() == OfferStatus.VOIDED) {
            return offer; // idempotent
        }
        Map<String, Object> before = snapshot(offer);
        offer.setStatus(OfferStatus.VOIDED);
        offer.setVoidedAt(Instant.now());
        offer.setVoidedReason(reason.trim());
        Offer saved = offerRepository.save(offer);
        writeAudit("Offer", saved.getId(), "VOID",
                actor != null ? actor.getId() : null, before, snapshot(saved));
        return saved;
    }

    /**
     * Re-send the OFFER_LETTER email so the applicant can reach the
     * IDMS signing page again. Original third-party "resend envelope"
     * call is gone — signing has always been local; the email was only
     * ever a transport for the signing link.
     */
    @Transactional
    public void resendOffer(UUID offerId, User actor) {
        Offer offer = offerRepository.findByIdWithGraph(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Offer not found: " + offerId));
        if (offer.getStatus() != OfferStatus.SENT) {
            throw new ConflictException(
                    "Only SENT offers can be resent (current: " + offer.getStatus() + ")");
        }
        Application application = offer.getApplication();
        Candidate cand = application != null ? application.getCandidate() : null;
        User applicantUser = cand != null ? cand.getUser() : null;
        if (applicantUser == null) {
            throw new BadRequestException("Offer has no resolvable applicant user");
        }
        long daysLeft = offer.getExpiresAt() != null
                ? Math.max(1, ChronoUnit.DAYS.between(Instant.now(), offer.getExpiresAt()))
                : 7;
        SendOfferRequest synth = new SendOfferRequest();
        synth.setApplicationId(application.getId());
        synth.setTentativeStartDate(offer.getStartDate());
        synth.setRoleTitle(offer.getRoleTitle());
        synth.setCompensationSummary(offer.getCompensationSummary());
        synth.setWorksite(offer.getWorksite());
        synth.setExpectedHoursPerWeek(offer.getExpectedHoursPerWeek());
        synth.setExpiryDays((int) Math.min(60, daysLeft));
        sendOfferLetterEmail(offer, synth, applicantUser, actor,
                offer.getCompensationSummary() != null ? offer.getCompensationSummary() : "TBD",
                (int) daysLeft);
        writeAudit("Offer", offer.getId(), "RESEND",
                actor != null ? actor.getId() : null, null, snapshot(offer));
    }

    // ── Intern signing entry points ────────────────────────────────────────

    /** Ownership-checked offer load for the IDMS applicant signing page. */
    @Transactional(readOnly = true)
    public Offer loadForApplicant(UUID offerId, User caller) {
        return mustOwnAsApplicant(offerId, caller);
    }

    /**
     * Applicant signs via the IDMS in-house flow at
     * {@code /careers/intern/offer/sign/{id}}. The signature image is
     * required; {@code typedName} falls back to the applicant's full
     * name when blank so the audit row always has a printed-name value.
     */
    @Transactional
    public Offer recordIdmsSignature(UUID offerId, String typedName,
                                      String signatureImage, User caller) {
        Offer offer = mustOwnAsApplicant(offerId, caller);
        if (signatureImage == null || signatureImage.isBlank()) {
            throw new BadRequestException("signatureImage is required");
        }
        String img = signatureImage.trim();
        if (!img.startsWith("data:image/")) {
            throw new BadRequestException(
                    "signatureImage must be a data URL (data:image/...;base64,...)");
        }
        // Soft cap to keep audit / DB payloads sane.
        if (img.length() > 512_000) {
            throw new BadRequestException(
                    "signatureImage too large (max ~500 KB data URL)");
        }
        String printed = typedName != null && !typedName.trim().isEmpty()
                ? typedName.trim()
                : (caller != null && caller.getFullName() != null
                        ? caller.getFullName() : null);
        if (printed != null && printed.length() > 200) {
            printed = printed.substring(0, 200);
        }
        return finalizeIdmsSigning(offer.getId(), printed, img, caller);
    }

    /**
     * Idempotent finalize of an IDMS signature:
     * <ul>
     *   <li>offer.status = SIGNED, signed_at = now, signed_by_typed_name = typed</li>
     *   <li>Mint employee_id and stamp it on the applicant user</li>
     *   <li>Advance user.lifecycle_status to EMPLOYEE_ID_CREATED</li>
     *   <li>Create InternLifecycle row (once per user)</li>
     *   <li>Advance application.status to ACCEPTED</li>
     *   <li>Publish OfferSignedEvent for the downstream ERM notification fan-out</li>
     * </ul>
     */
    @Transactional
    public Offer finalizeIdmsSigning(UUID offerId, String typedName,
                                      String signatureImage, User actor) {
        Offer offer = offerRepository.findByIdWithGraph(offerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Offer not found: " + offerId));
        if (offer.getStatus() == OfferStatus.SIGNED) {
            return offer;
        }
        if (offer.getStatus() != OfferStatus.SENT) {
            throw new ConflictException(
                    "Offer not in a signable state (status=" + offer.getStatus() + ")");
        }
        // Phase 8.9 — a SIGNED offer with no startDate strands the intern at
        // ONBOARDING_ACCEPTED forever (the activation gate filters out null
        // startDates). Reject before flipping status so the intern can never
        // sign their way into an un-activatable state.
        if (offer.getStartDate() == null) {
            throw new BadRequestException(
                    "Offer cannot be signed without a start date. "
                            + "Ask ERM to amend the offer with a start date first.");
        }
        Instant now = Instant.now();
        if (offer.getExpiresAt() != null && now.isAfter(offer.getExpiresAt())) {
            // Auto-flip to EXPIRED so the queue reflects reality, then refuse.
            offer.setStatus(OfferStatus.EXPIRED);
            offerRepository.save(offer);
            throw new ConflictException("Offer expired on " + offer.getExpiresAt());
        }

        Map<String, Object> before = snapshot(offer);
        UUID actorId = actor != null ? actor.getId() : null;
        offer.setStatus(OfferStatus.SIGNED);
        offer.setSignedAt(now);
        offer.setRespondedAt(now);
        offer.setSignedByTypedName(typedName != null ? typedName.trim() : null);
        offer.setSignedSignatureImage(signatureImage);
        Offer saved = offerRepository.save(offer);

        // Mint Employee ID + advance applicant lifecycle.
        Application application = saved.getApplication();
        User applicant = application != null && application.getCandidate() != null
                ? application.getCandidate().getUser() : null;
        String employeeId = null;
        if (applicant != null) {
            employeeId = nextEmployeeId();
            applicant.setEmployeeId(employeeId);
            userRepository.save(applicant);

            internLifecycleService.advance(applicant,
                    InternLifecycleStatus.EMPLOYEE_ID_CREATED, actorId);

            if (!internLifecycleRepository.existsByUserId(applicant.getId())) {
                InternLifecycle lc = InternLifecycle.builder()
                        .userId(applicant.getId())
                        .employeeId(employeeId)
                        .ermId(saved.getCreatedBy())
                        .activeStatus("PROSPECTIVE")
                        .hiredAt(now)
                        .build();
                // Phase 8.6.4 — auto-link the single org-wide Trainer +
                // Evaluator from DEFAULT_*_EMAIL env vars. Manager stays
                // null and is assigned inline later from the New Hire
                // detail page (it varies per intern).
                reportingStructureAutoLinker.apply(lc, actorId, now);
                internLifecycleRepository.save(lc);
            }
        }

        if (application != null) {
            application.setStatus(ApplicationStatus.ACCEPTED);
            application.setStatusUpdatedAt(now);
            if (actorId != null) application.setStatusUpdatedBy(actorId);
            applicationRepository.save(application);
        }

        // Phase 3 step 3 parity — IDMS signing is the in-house equivalent of
        // OfferService.acceptInternal:301. That sibling path already calls
        // createForAcceptedOffer here; the IDMS path historically didn't,
        // which left every IDMS-signed intern with no Engagement row and
        // silently broke every downstream feature keyed on engagement_id
        // (projects, timesheets, compliance, evaluations). The call is
        // idempotent (early findByOfferId check in createForAcceptedOffer)
        // and runs in REQUIRES_NEW, so a creation blip never rolls back the
        // signing/employee-id work above. Same belt-and-braces try/catch
        // shape as acceptInternal.
        try {
            engagementService.createForAcceptedOffer(saved, actor);
        } catch (Exception e) {
            log.warn("Failed to create engagement for IDMS-signed offer {}: {}",
                    saved.getId(), e.getMessage(), e);
        }

        writeAudit("Offer", saved.getId(), "SIGNED_VIA_IDMS",
                actorId, before, snapshot(saved));

        final UUID offerIdF = saved.getId();
        final UUID appIdF = application != null ? application.getId() : null;
        final UUID applicantIdF = applicant != null ? applicant.getId() : null;
        final String employeeIdF = employeeId;
        try {
            eventPublisher.publishEvent(new OfferSignedEvent(
                    offerIdF, appIdF, applicantIdF, employeeIdF));
        } catch (Exception e) {
            log.warn("OfferSignedEvent publish failed (non-fatal): {}", e.getMessage());
        }
        log.info("[Offer] IDMS-signed offer={} employee_id={} applicant={}",
                saved.getId(), employeeId, applicantIdF);
        return saved;
    }

    @jakarta.annotation.PostConstruct
    void logAutoLinkConfig() {
        log.info("[Config] DEFAULT_TRAINER_EMAIL = {} | DEFAULT_EVALUATOR_EMAIL = {} "
                        + "(both optional; document workflow runs regardless)",
                defaultTrainerEmail != null && !defaultTrainerEmail.isBlank()
                        ? defaultTrainerEmail : "(unset)",
                defaultEvaluatorEmail != null && !defaultEvaluatorEmail.isBlank()
                        ? defaultEvaluatorEmail : "(unset)");
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private String nextEmployeeId() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT nextval('skyzen_employee_seq')", Long.class);
        long val = n == null ? 1000 : n;
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        return String.format("ANVI-EMP-%d-%06d", year, val);
    }

    private Offer mustOwnAsApplicant(UUID offerId, User caller) {
        Offer offer = offerRepository.findByIdWithGraph(offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Offer not found: " + offerId));
        if (!isOwner(offer, caller)) {
            throw new ForbiddenException("Not allowed to view this offer");
        }
        return offer;
    }

    private boolean isOwner(Offer offer, User caller) {
        if (caller == null || offer.getApplication() == null) return false;
        Candidate cand = offer.getApplication().getCandidate();
        if (cand == null || cand.getUser() == null) return false;
        return caller.getId().equals(cand.getUser().getId());
    }

    @SuppressWarnings("unused")
    private boolean isStaff(User caller) {
        if (caller == null) return false;
        return caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.MANAGER)
                || caller.getRoles().contains(UserRole.SUPER_ADMIN);
    }

    private String buildPlainLetter(SendOfferRequest req, User applicant) {
        // Letter content is required NOT NULL on the legacy Offer entity.
        // Populate a short plaintext summary; the real letter the
        // applicant signs is rendered by the OFFER_LETTER email template
        // + the IDMS signing page.
        return "Dear " + safe(applicant.getFullName()) + ",\n\n"
                + "We are pleased to offer you the role of " + safe(req.getRoleTitle())
                + " starting on " + req.getTentativeStartDate()
                + ". " + (req.getCompensationSummary() != null ? req.getCompensationSummary() : "")
                + "\n\nThis offer is delivered via the " + brand.getName() + " IDMS signing flow.";
    }

    private com.anvicorp.api.enums.CompensationFrequency safeFrequency() {
        com.anvicorp.api.enums.CompensationFrequency[] vals =
                com.anvicorp.api.enums.CompensationFrequency.values();
        return vals.length > 0 ? vals[0] : null;
    }

    private void writeAudit(String entityType, UUID entityId, String action, UUID userId,
                            Map<String, Object> before, Map<String, Object> after) {
        try {
            AuditLog entry = AuditLog.builder()
                    .entityType(entityType)
                    .entityId(entityId)
                    .action(action)
                    .userId(userId)
                    .beforeJson(before == null ? null : objectMapper.writeValueAsString(before))
                    .afterJson(after == null ? null : objectMapper.writeValueAsString(after))
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("[OfferIdms] audit write failed: {}", e.getMessage());
        }
    }

    private Map<String, Object> snapshot(Offer offer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", offer.getId());
        m.put("status", offer.getStatus());
        m.put("signedAt", offer.getSignedAt());
        m.put("voidedAt", offer.getVoidedAt());
        return m;
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
