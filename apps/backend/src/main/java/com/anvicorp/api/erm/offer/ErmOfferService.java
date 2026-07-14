package com.anvicorp.api.erm.offer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.offer.SendOfferRequest;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.OfferEventLog;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.erm.CommunicationTemplateService;
import com.anvicorp.api.erm.ReasonCode;
import com.anvicorp.api.event.OfferReminderEvent;
import com.anvicorp.api.event.OfferVoidedEvent;
import com.anvicorp.api.event.TentativeStartDateUpdatedEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.intern.OfferIdmsSigningService;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.OfferEventLogRepository;
import com.anvicorp.api.repository.OfferRepository;
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
 * ERM Phase 4 — offer control. Wraps the existing
 * {@link OfferIdmsSigningService} with ERM-level gates (SELECTED interview
 * decision required), reason-code-driven void + reminder + re-offer flow,
 * and an immutable {@link OfferEventLog} history.
 *
 * <p>Void is the SECOND documented allowed lifecycle reversal (the first
 * was interview cancel) — OFFER_SENT → INTERVIEW_COMPLETED. Logged at
 * INFO with reason.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmOfferService {

    private static final int CLEAR_REOFFER_COOLDOWN_HOURS = 24;
    private static final int INTERNAL_NOTE_MIN = 5;
    private static final int INTERNAL_NOTE_MAX = 5000;

    private final OfferRepository offerRepository;
    private final OfferEventLogRepository eventLogRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final OfferIdmsSigningService offerIdmsSigningService;
    private final CommunicationTemplateService templateService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final com.anvicorp.api.intern.InternLifecycleService internLifecycleService;
    private final SelectionAckPolicy selectionAckPolicy;
    private final com.anvicorp.api.config.BrandConfig brand;

    // ── List ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ErmOfferDtos.OfferListPage list(String statusFilter, String search,
                                            UUID applicationId,
                                            int page, int pageSize) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page), Math.min(Math.max(1, pageSize), 100));
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (statusFilter != null && !statusFilter.isBlank()) {
            where.append(" AND o.status = ?");
            params.add(statusFilter.toUpperCase());
        }
        if (applicationId != null) {
            where.append(" AND o.application_id = ?");
            params.add(applicationId);
        }
        if (search != null && !search.isBlank()) {
            String q = "%" + search.trim().toLowerCase() + "%";
            if (q.length() > 102) q = q.substring(0, 102);
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ?)");
            params.add(q); params.add(q);
        }
        String base = "FROM offers o "
                + "JOIN applications a ON a.id = o.application_id "
                + "JOIN candidates c ON c.id = a.candidate_id "
                + "JOIN users u ON u.id = c.user_id "
                + "JOIN job_postings jp ON jp.id = a.job_posting_id ";
        long total;
        try {
            Long v = jdbc.queryForObject("SELECT COUNT(*) " + base + where,
                    Long.class, params.toArray());
            total = v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ErmOffers] count failed: {}", e.getMessage());
            total = 0L;
        }
        String select = "SELECT o.id, o.application_id, o.status, o.role_title, "
                + "o.compensation_summary, o.start_date, o.sent_at, o.expires_at, "
                + "o.signed_at, o.voided_at, o.void_reason_code, o.reminder_count, "
                + "o.docusign_envelope_id, o.archived_at, "
                + "u.full_name AS applicant_name, u.applicant_id, u.email AS applicant_email, "
                + "jp.title AS job_title, jp.job_type "
                + base + where
                + " ORDER BY o.created_at DESC "
                + " LIMIT " + pageable.getPageSize()
                + " OFFSET " + (pageable.getPageNumber() * pageable.getPageSize());
        List<ErmOfferDtos.OfferRow> rows = new ArrayList<>();
        try {
            rows = jdbc.query(select, params.toArray(), (rs, n) -> new ErmOfferDtos.OfferRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("application_id")),
                    rs.getString("applicant_name"),
                    rs.getString("applicant_id"),
                    rs.getString("applicant_email"),
                    rs.getString("job_title"),
                    rs.getString("job_type"),
                    OfferStatus.valueOf(rs.getString("status")),
                    rs.getString("role_title"),
                    rs.getString("compensation_summary"),
                    rs.getDate("start_date") != null ? rs.getDate("start_date").toLocalDate() : null,
                    rs.getTimestamp("sent_at") != null ? rs.getTimestamp("sent_at").toInstant() : null,
                    rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null,
                    rs.getTimestamp("signed_at") != null ? rs.getTimestamp("signed_at").toInstant() : null,
                    rs.getTimestamp("voided_at") != null ? rs.getTimestamp("voided_at").toInstant() : null,
                    rs.getString("void_reason_code"),
                    rs.getInt("reminder_count"),
                    rs.getString("docusign_envelope_id"),
                    rs.getTimestamp("archived_at") != null));
        } catch (Exception e) {
            log.warn("[ErmOffers] list query failed: {}", e.getMessage());
        }
        Page<ErmOfferDtos.OfferRow> p = new PageImpl<>(rows, pageable, total);
        return new ErmOfferDtos.OfferListPage(p.getContent(), p.getNumber(),
                p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    // ── Detail ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ErmOfferDtos.OfferDetail getDetail(UUID offerId) {
        Offer o = offerRepository.findById(offerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Offer not found: " + offerId));
        return toDetail(o);
    }

    // ── Preview ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ErmOfferDtos.PreviewResponse preview(
            ErmOfferDtos.CreateOfferRequest req, User caller) {
        Map<String, Object> vars = templateVars(req, caller);
        String key = req.templateOverrideKey() != null && !req.templateOverrideKey().isBlank()
                ? req.templateOverrideKey() : "OFFER_LETTER";
        try {
            var rendered = templateService.render(key, "EMAIL", vars).orElse(null);
            if (rendered != null) {
                return new ErmOfferDtos.PreviewResponse(rendered.subject(), rendered.body());
            }
        } catch (Exception e) {
            log.warn("[ErmOffers] preview render failed for {}: {}", key, e.getMessage());
        }
        return new ErmOfferDtos.PreviewResponse(
                "Your offer from " + brand.getName() + " — " + nz(req.roleTitle()),
                "Preview unavailable — template " + key + " missing or render failed.");
    }

    // ── Create + send (with SELECTED interview gate) ───────────────────────

    @Transactional
    public ErmOfferDtos.OfferDetail createAndSend(
            ErmOfferDtos.CreateOfferRequest req, User caller) {
        if (req == null || req.applicationId() == null) {
            throw new BadRequestException("applicationId is required");
        }
        if (req.tentativeStartDate() == null
                || req.tentativeStartDate().isBefore(LocalDate.now().plusDays(1))) {
            throw new BadRequestException("tentativeStartDate must be at least tomorrow");
        }
        if (req.tentativeStartDate().isAfter(LocalDate.now().plusDays(180))) {
            throw new BadRequestException("tentativeStartDate cannot be more than 180 days out");
        }
        if (req.roleTitle() == null || req.roleTitle().trim().isEmpty()) {
            throw new BadRequestException("roleTitle is required");
        }
        if (req.compensationSummary() == null
                || req.compensationSummary().trim().isEmpty()) {
            throw new BadRequestException("compensationSummary is required");
        }
        if (req.worksite() == null || req.worksite().trim().isEmpty()) {
            throw new BadRequestException("worksite is required");
        }
        if (req.expectedHoursPerWeek() == null
                || req.expectedHoursPerWeek() < 1
                || req.expectedHoursPerWeek() > 40) {
            throw new BadRequestException("expectedHoursPerWeek must be 1-40");
        }
        int expiryDays = req.expiryDays() != null ? req.expiryDays() : 7;
        if (expiryDays < 1 || expiryDays > 30) {
            throw new BadRequestException("expiryDays must be 1-30");
        }
        Application app = applicationRepository.findById(req.applicationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + req.applicationId()));

        // ERM Phase 4 gates — both keyed off the shared SelectionAckPolicy
        // so the intern dashboard's SelectionAckCard never disagrees with
        // the 409s thrown here. Two-tier check preserved for distinct
        // operator-facing error text.
        if (!selectionAckPolicy.isSelected(app)) {
            throw new ConflictException(
                    "Send Offer requires SELECTED interview decision.");
        }
        // Selection-ack gate: the intern must have clicked "Receive my
        // offer letter" on their dashboard before an offer can be issued.
        // Holds offers until the intern actively requests one — they
        // shouldn't get an unexpected signing email cold. The card's
        // visibility is bound to selectionAckPolicy.needsAck(app), so if
        // we throw this 409 the card is guaranteed to be on screen.
        if (selectionAckPolicy.needsAck(app)) {
            throw new ConflictException(
                    "Send Offer requires the intern's selection acknowledgment. "
                            + "They haven't clicked 'Receive my offer letter' on their dashboard yet.");
        }

        // Delegate to OfferIdmsSigningService for the heavy lifting
        // (entity creation, OFFER_LETTER email dispatch with the IDMS
        // signing link, audit). It already enforces
        // application.status=INTERVIEWED + no existing offer.
        SendOfferRequest dsReq = new SendOfferRequest();
        dsReq.setApplicationId(req.applicationId());
        dsReq.setTentativeStartDate(req.tentativeStartDate());
        dsReq.setRoleTitle(req.roleTitle().trim());
        dsReq.setCompensationSummary(req.compensationSummary().trim());
        dsReq.setWorksite(req.worksite().trim());
        dsReq.setExpectedHoursPerWeek(req.expectedHoursPerWeek());
        dsReq.setExpiryDays(expiryDays);
        Offer offer = offerIdmsSigningService.sendOffer(dsReq, caller);

        // ERM Phase 4 — additional event-log + parallel summary email.
        writeEventLog(offer.getId(), caller.getId(), "CREATED", null, null,
                Map.of("roleTitle", req.roleTitle(),
                        "tentativeStartDate", req.tentativeStartDate().toString(),
                        "expiryDays", expiryDays));
        writeEventLog(offer.getId(), caller.getId(), "SENT", null, null,
                Map.of("signingFlow", "IDMS"));
        return toDetail(offer);
    }

    // ── Resend / reminder ──────────────────────────────────────────────────

    @Transactional
    public ErmOfferDtos.OfferDetail resend(UUID offerId,
                                            ErmOfferDtos.ResendRequest req, User caller) {
        ReasonCode rc = requireReason(req != null ? req.reasonCode() : null,
                req != null ? req.reasonText() : null,
                ReasonCode.Category.OFFER_RESEND);
        Offer offer = mustGet(offerId);
        if (offer.getStatus() != OfferStatus.SENT) {
            throw new ConflictException(
                    "Resend allowed only from SENT (current: " + offer.getStatus() + ")");
        }
        offer.setReminderCount((offer.getReminderCount() != null
                ? offer.getReminderCount() : 0) + 1);
        offer.setLastReminderAt(Instant.now());
        if (req != null && req.newExpiryDays() != null) {
            if (req.newExpiryDays() < 1 || req.newExpiryDays() > 30) {
                throw new BadRequestException("newExpiryDays must be 1-30");
            }
            offer.setExpiresAt(Instant.now().plus(req.newExpiryDays(), java.time.temporal.ChronoUnit.DAYS));
        }
        offerRepository.save(offer);

        try {
            offerIdmsSigningService.resendOffer(offerId, caller);
        } catch (Exception e) {
            log.warn("[ErmOffers] IDMS resend failed (non-fatal): {}", e.getMessage());
        }

        String eventType = req != null && req.newExpiryDays() != null ? "RESENT" : "REMINDER_SENT";
        writeEventLog(offerId, caller.getId(), eventType,
                rc.name(), trimOrNull(req != null ? req.reasonText() : null),
                Map.of("reminderCount", offer.getReminderCount(),
                        "newExpiresAt", offer.getExpiresAt().toString()));
        writeAudit(caller.getId(), applicantUserId(offer),
                "OFFER_" + eventType, "Offer", offerId,
                null, Map.of("reasonCode", rc.name()));
        try {
            eventPublisher.publishEvent(new OfferReminderEvent(
                    offerId, applicantUserId(offer), caller.getId(),
                    eventType, rc.name()));
        } catch (Exception e) {
            log.warn("[ErmOffers] reminder event publish failed: {}", e.getMessage());
        }
        return toDetail(offer);
    }

    @Transactional
    public ErmOfferDtos.OfferDetail sendReminder(UUID offerId, User caller) {
        ErmOfferDtos.ResendRequest synthetic = new ErmOfferDtos.ResendRequest(
                ReasonCode.OFFER_RESEND_NO_RESPONSE.name(), null, null);
        return resend(offerId, synthetic, caller);
    }

    // ── Void (with lifecycle reversal) ─────────────────────────────────────

    @Transactional
    public ErmOfferDtos.OfferDetail voidOffer(UUID offerId,
                                               ErmOfferDtos.VoidRequest req, User caller) {
        ReasonCode rc = requireReason(req != null ? req.reasonCode() : null,
                req != null ? req.reasonText() : null,
                ReasonCode.Category.OFFER_VOID);
        Offer offer = mustGet(offerId);
        if (offer.getStatus() != OfferStatus.SENT) {
            throw new ConflictException(
                    "Void allowed only from SENT (current: " + offer.getStatus() + ")");
        }
        // Delegate to the existing void path for audit + status flip.
        offerIdmsSigningService.voidOffer(offerId, rc.humanLabel(), caller);
        // Refresh + add ERM-specific fields.
        offer = mustGet(offerId);
        offer.setVoidReasonCode(rc.name());
        offer.setVoidReasonText(trimOrNull(req != null ? req.reasonText() : null));
        offerRepository.save(offer);

        // ── ERM Phase 4 — documented lifecycle reversal #2 ────────────────
        // Cancel-interview was the first (Phase 3); offer-void is the second.
        // OFFER_SENT → INTERVIEW_COMPLETED. Logged at INFO with reason so the
        // audit trail captures the regression.
        Application app = offer.getApplication();
        if (app != null && app.getCandidate() != null
                && app.getCandidate().getUser() != null) {
            User u = app.getCandidate().getUser();
            if (u.getLifecycleStatus() == InternLifecycleStatus.OFFER_SENT) {
                log.info("[ErmOffers] void-induced lifecycle reversal "
                                + "OFFER_SENT → INTERVIEW_COMPLETED for user={} reason={}",
                        u.getId(), rc.name());
                u.setLifecycleStatus(InternLifecycleStatus.INTERVIEW_COMPLETED);
                userRepository.save(u);
            }
        }

        writeEventLog(offerId, caller.getId(), "VOIDED",
                rc.name(), trimOrNull(req != null ? req.reasonText() : null),
                Map.of("reasonCode", rc.name()));
        writeAudit(caller.getId(), applicantUserId(offer),
                "OFFER_VOIDED", "Offer", offerId,
                Map.of("status", "SENT"),
                Map.of("status", "VOIDED", "reasonCode", rc.name()));

        try {
            eventPublisher.publishEvent(new OfferVoidedEvent(
                    offerId, applicantUserId(offer), caller.getId(),
                    rc.name(), trimOrNull(req != null ? req.reasonText() : null),
                    req == null || req.notifyApplicant() == null || req.notifyApplicant()));
        } catch (Exception e) {
            log.warn("[ErmOffers] void event publish failed: {}", e.getMessage());
        }
        return toDetail(offer);
    }

    // ── Clear-for-reoffer (24h gate) ──────────────────────────────────────

    @Transactional
    public ErmOfferDtos.OfferDetail clearForReoffer(UUID offerId, User caller) {
        Offer offer = mustGet(offerId);
        if (offer.getStatus() != OfferStatus.VOIDED) {
            throw new ConflictException(
                    "clear-for-reoffer allowed only from VOIDED (current: " + offer.getStatus() + ")");
        }
        if (offer.getVoidedAt() == null
                || Duration.between(offer.getVoidedAt(), Instant.now()).toHours()
                        < CLEAR_REOFFER_COOLDOWN_HOURS) {
            throw new ConflictException(
                    "clear-for-reoffer requires " + CLEAR_REOFFER_COOLDOWN_HOURS
                            + "h cooldown after void");
        }
        if (offer.getArchivedAt() != null) {
            throw new ConflictException("Offer already archived");
        }
        offer.setArchivedAt(Instant.now());
        offerRepository.save(offer);
        writeEventLog(offerId, caller.getId(), "CLEARED_FOR_REOFFER", null, null,
                Map.of("archivedAt", offer.getArchivedAt().toString()));
        writeAudit(caller.getId(), applicantUserId(offer),
                "OFFER_CLEARED_FOR_REOFFER", "Offer", offerId,
                null, Map.of("archivedAt", offer.getArchivedAt().toString()));
        return toDetail(offer);
    }

    // ── Internal note ─────────────────────────────────────────────────────

    @Transactional
    public void appendInternalNote(UUID offerId, String note, User caller) {
        if (note == null || note.trim().length() < INTERNAL_NOTE_MIN) {
            throw new BadRequestException(
                    "note must be at least " + INTERNAL_NOTE_MIN + " characters");
        }
        if (note.length() > INTERNAL_NOTE_MAX) {
            throw new BadRequestException(
                    "note cannot exceed " + INTERNAL_NOTE_MAX + " characters");
        }
        Offer offer = mustGet(offerId);
        String stamp = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                .toString().substring(0, 19) + "Z";
        String author = caller.getFullName() != null ? caller.getFullName() : "ERM";
        String entry = "[" + stamp + " · " + author + "]\n" + note.trim();
        offer.setInternalNotes(offer.getInternalNotes() == null
                || offer.getInternalNotes().isBlank()
                ? entry : offer.getInternalNotes() + "\n\n" + entry);
        offerRepository.save(offer);
        writeEventLog(offerId, caller.getId(), "NOTES_UPDATED", null, null,
                Map.of("length", note.length()));
    }

    // ── Update tentative start date ───────────────────────────────────────

    @Transactional
    public ErmOfferDtos.OfferDetail updateStartDate(UUID offerId,
                                                     LocalDate newDate, User caller) {
        if (newDate == null || !newDate.isAfter(LocalDate.now())) {
            throw new BadRequestException("newDate must be at least tomorrow");
        }
        Offer offer = mustGet(offerId);
        LocalDate previous = offer.getStartDate();
        offer.setStartDate(newDate);
        offerRepository.save(offer);

        // Mirror to intern_lifecycles.tentative_start_date if a lifecycle row exists.
        UUID applicantId = applicantUserId(offer);
        UUID lifecycleId = null;
        if (applicantId != null) {
            try {
                jdbc.update("UPDATE intern_lifecycles SET tentative_start_date = ? "
                                + "WHERE user_id = ?",
                        java.sql.Date.valueOf(newDate), applicantId);
                lifecycleId = jdbc.queryForObject(
                        "SELECT id FROM intern_lifecycles WHERE user_id = ?",
                        UUID.class, applicantId);
            } catch (Exception e) {
                log.debug("[ErmOffers] tentative date mirror failed (non-fatal): {}", e.getMessage());
            }
        }

        Map<String, Object> evtPayload = new LinkedHashMap<>();
        evtPayload.put("previous", previous != null ? previous.toString() : null);
        evtPayload.put("newDate", newDate.toString());
        writeEventLog(offerId, caller.getId(), "START_DATE_UPDATED", null, null, evtPayload);
        Map<String, Object> auditBefore = new LinkedHashMap<>();
        auditBefore.put("startDate", previous != null ? previous.toString() : null);
        writeAudit(caller.getId(), applicantId,
                "OFFER_START_DATE_UPDATED", "Offer", offerId,
                auditBefore,
                Map.of("startDate", newDate.toString()));
        try {
            eventPublisher.publishEvent(new TentativeStartDateUpdatedEvent(
                    lifecycleId, applicantId, caller.getId(), previous, newDate));
        } catch (Exception e) {
            log.warn("[ErmOffers] start-date event publish failed: {}", e.getMessage());
        }
        return toDetail(offer);
    }

    // ── Reason codes ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ErmOfferDtos.ReasonCodeGroup> listReasonCodes(String family) {
        Set<ReasonCode.Category> cats;
        if (family == null || family.isBlank()) {
            cats = EnumSet.of(ReasonCode.Category.OFFER_VOID,
                    ReasonCode.Category.OFFER_RESEND);
        } else {
            cats = switch (family.toUpperCase()) {
                case "VOID" -> EnumSet.of(ReasonCode.Category.OFFER_VOID);
                case "RESEND" -> EnumSet.of(ReasonCode.Category.OFFER_RESEND);
                default -> EnumSet.noneOf(ReasonCode.Category.class);
            };
        }
        Map<ReasonCode.Category, List<ErmOfferDtos.ReasonCodeOption>> bucket =
                new EnumMap<>(ReasonCode.Category.class);
        for (ReasonCode rc : ReasonCode.values()) {
            if (!cats.contains(rc.category())) continue;
            bucket.computeIfAbsent(rc.category(), k -> new ArrayList<>())
                    .add(new ErmOfferDtos.ReasonCodeOption(
                            rc.name(), rc.humanLabel(), rc.requiresFreeText()));
        }
        List<ErmOfferDtos.ReasonCodeGroup> out = new ArrayList<>();
        for (var e : bucket.entrySet()) {
            out.add(new ErmOfferDtos.ReasonCodeGroup(e.getKey().name(), e.getValue()));
        }
        return out;
    }

    // ── Mapping ───────────────────────────────────────────────────────────

    private ErmOfferDtos.OfferDetail toDetail(Offer o) {
        Application app = o.getApplication();
        Candidate c = app != null ? app.getCandidate() : null;
        User applicant = c != null ? c.getUser() : null;
        JobPosting jp = app != null ? app.getJobPosting() : null;
        List<ErmOfferDtos.EventLogEntry> history = new ArrayList<>();
        try {
            for (OfferEventLog l : eventLogRepository
                    .findByOfferIdOrderByCreatedAtDesc(o.getId())) {
                User actor = l.getActorUserId() != null
                        ? userRepository.findById(l.getActorUserId()).orElse(null) : null;
                history.add(new ErmOfferDtos.EventLogEntry(
                        l.getId(), l.getEventType(), l.getReasonCode(),
                        l.getReasonText(), l.getPayloadJson(),
                        l.getActorUserId(),
                        actor != null ? actor.getFullName() : null,
                        l.getCreatedAt()));
            }
        } catch (Exception ignored) {}
        return new ErmOfferDtos.OfferDetail(
                o.getId(), o.getStatus(),
                app != null ? app.getId() : null,
                applicant != null ? applicant.getFullName() : null,
                applicant != null ? applicant.getEmail() : null,
                applicant != null ? applicant.getApplicantId() : null,
                jp != null ? jp.getTitle() : null,
                jp != null && jp.getEmploymentType() != null
                        ? jp.getEmploymentType().name() : null,
                o.getRoleTitle(),
                o.getCompensationSummary(),
                o.getWorksite(),
                o.getExpectedHoursPerWeek(),
                o.getStartDate(),
                o.getSentAt(),
                o.getExpiresAt(),
                o.getSignedAt(),
                o.getVoidedAt(),
                o.getVoidReasonCode(),
                o.getVoidReasonText(),
                o.getReminderCount(),
                o.getLastReminderAt(),
                o.getLegacyEnvelopeId(),
                o.getSignedPdfDocumentId(),
                o.getInternalNotes(),
                o.getArchivedAt(),
                o.getCreatedBy(),
                o.getCreatedAt(),
                o.getUpdatedAt(),
                history);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Offer mustGet(UUID id) {
        return offerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Offer not found: " + id));
    }

    private static UUID applicantUserId(Offer o) {
        if (o == null || o.getApplication() == null
                || o.getApplication().getCandidate() == null
                || o.getApplication().getCandidate().getUser() == null) return null;
        return o.getApplication().getCandidate().getUser().getId();
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String nz(String s) {
        return s != null ? s : "";
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
        if (rc.requiresFreeText() && (text == null || text.trim().length() < 10)) {
            throw new BadRequestException(
                    "reasonText must be at least 10 characters when reasonCode is " + rc.name());
        }
        return rc;
    }

    private Map<String, Object> templateVars(ErmOfferDtos.CreateOfferRequest req, User caller) {
        Map<String, Object> vars = new LinkedHashMap<>();
        Application app = req.applicationId() != null
                ? applicationRepository.findById(req.applicationId()).orElse(null) : null;
        User applicant = app != null && app.getCandidate() != null
                ? app.getCandidate().getUser() : null;
        String firstName = applicant != null && applicant.getFullName() != null
                ? applicant.getFullName().split("\\s+", 2)[0] : "Applicant";
        vars.put("firstName", firstName);
        vars.put("roleTitle", nz(req.roleTitle()));
        vars.put("tentativeStartDate",
                req.tentativeStartDate() != null ? req.tentativeStartDate().toString() : "TBD");
        vars.put("compensationSummary", nz(req.compensationSummary()));
        vars.put("worksite", nz(req.worksite()));
        vars.put("expectedHoursPerWeek",
                req.expectedHoursPerWeek() != null ? req.expectedHoursPerWeek().toString() : "TBD");
        vars.put("contingencies", req.contingencies() != null && !req.contingencies().isBlank()
                ? req.contingencies() : "Subject to verification of work authorization.");
        vars.put("expiryDays",
                req.expiryDays() != null ? req.expiryDays().toString() : "7");
        vars.put("ermName", caller != null && caller.getFullName() != null
                ? caller.getFullName() : brand.getName() + " ERM");
        // {{signingLink}} is built per-offer at SEND time by
        // OfferIdmsSigningService.sendOfferLetterEmail (offerId doesn't
        // exist yet at preview). Supply a human-friendly placeholder so
        // the OFFER_LETTER template renders cleanly in the ERM preview
        // pane instead of failing with "missing variable 'signingLink'".
        vars.put("signingLink",
                "(a secure signing link is generated when the offer is sent)");
        return vars;
    }

    private void writeEventLog(UUID offerId, UUID actorId, String eventType,
                                String reasonCode, String reasonText,
                                Map<String, Object> payload) {
        try {
            OfferEventLog row = OfferEventLog.builder()
                    .offerId(offerId)
                    .actorUserId(actorId)
                    .eventType(eventType)
                    .reasonCode(reasonCode)
                    .reasonText(reasonText)
                    .payloadJson(payload != null && !payload.isEmpty()
                            ? objectMapper.writeValueAsString(payload) : null)
                    .build();
            eventLogRepository.save(row);
        } catch (JsonProcessingException jpe) {
            log.warn("[ErmOffers] event log JSON failed: {}", jpe.getMessage());
        } catch (Exception e) {
            log.warn("[ErmOffers] event log write failed: {}", e.getMessage());
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
            log.warn("[ErmOffers] audit write failed: {}", e.getMessage());
        }
    }

    // ── Phase 8.6 — Awaiting Offer queue ──────────────────────────────────
    /** Applications in INTERVIEWED state whose latest COMPLETED interview
     *  recorded decision=SELECTED, and which have no active outstanding
     *  offer (status NOT IN SENT, SIGNED). DRAFT/VOIDED/EXPIRED/DECLINED
     *  rows do not count as "active" because the applicant can be reoffered. */
    @Transactional(readOnly = true)
    public ErmOfferDtos.AwaitingOfferListPage listAwaitingOffer(
            String search, int page, int pageSize) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page), Math.min(Math.max(1, pageSize), 100));

        StringBuilder where = new StringBuilder(
                " WHERE a.status = 'INTERVIEWED' "
                        + " AND UPPER(COALESCE(iv.decision,'')) = 'SELECTED' "
                        + " AND iv.status = 'COMPLETED' "
                        + " AND NOT EXISTS ( "
                        + "   SELECT 1 FROM offers o2 "
                        + "    WHERE o2.application_id = a.id "
                        + "      AND o2.status IN ('SENT','SIGNED') "
                        + " ) ");
        List<Object> params = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            String q = "%" + search.trim().toLowerCase() + "%";
            if (q.length() > 102) q = q.substring(0, 102);
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ? "
                    + "OR LOWER(COALESCE(u.applicant_id,'')) LIKE ?) ");
            params.add(q); params.add(q); params.add(q);
        }

        // Sub-select picks the most recent completed interview per application;
        // the outer query joins to that row for decision/score columns. No
        // completed_at column exists on interviews — updated_at is the proxy
        // (UpdateTimestamp fires when status flips to COMPLETED).
        String base = "FROM applications a "
                + "JOIN candidates c ON c.id = a.candidate_id "
                + "JOIN users u ON u.id = c.user_id "
                + "JOIN job_postings jp ON jp.id = a.job_posting_id "
                + "JOIN interviews iv ON iv.id = ( "
                + "    SELECT iv2.id FROM interviews iv2 "
                + "     WHERE iv2.application_id = a.id "
                + "       AND iv2.status = 'COMPLETED' "
                + "     ORDER BY iv2.updated_at DESC NULLS LAST, iv2.scheduled_at DESC "
                + "     LIMIT 1 "
                + ") ";

        long total;
        try {
            Long v = jdbc.queryForObject(
                    "SELECT COUNT(*) " + base + where, Long.class, params.toArray());
            total = v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ErmOffers] awaiting count failed: {}", e.getMessage());
            total = 0L;
        }

        String select = "SELECT a.id AS application_id, iv.id AS interview_id, "
                + "u.full_name, u.applicant_id, u.email, "
                + "jp.title AS job_title, jp.job_type, "
                + "iv.updated_at AS completed_at, iv.overall_recommendation, "
                + "iv.technical_score, iv.communication_score, "
                + "iv.applicant_visible_notes "
                + base + where
                + " ORDER BY iv.updated_at DESC NULLS LAST, a.id DESC "
                + " LIMIT " + pageable.getPageSize()
                + " OFFSET " + (pageable.getPageNumber() * pageable.getPageSize());

        List<ErmOfferDtos.AwaitingOfferRow> rows = new ArrayList<>();
        try {
            rows = jdbc.query(select, params.toArray(), (rs, n) ->
                    new ErmOfferDtos.AwaitingOfferRow(
                            UUID.fromString(rs.getString("application_id")),
                            UUID.fromString(rs.getString("interview_id")),
                            rs.getString("full_name"),
                            rs.getString("applicant_id"),
                            rs.getString("email"),
                            rs.getString("job_title"),
                            rs.getString("job_type"),
                            null,  // technology_area not yet on JobPosting
                            rs.getTimestamp("completed_at") != null
                                    ? rs.getTimestamp("completed_at").toInstant() : null,
                            rs.getString("overall_recommendation"),
                            (Integer) rs.getObject("technical_score"),
                            (Integer) rs.getObject("communication_score"),
                            rs.getString("applicant_visible_notes")));
        } catch (Exception e) {
            log.warn("[ErmOffers] awaiting query failed: {}", e.getMessage());
        }

        int totalPages = pageable.getPageSize() == 0 ? 0
                : (int) Math.ceil(total / (double) pageable.getPageSize());
        return new ErmOfferDtos.AwaitingOfferListPage(
                rows, pageable.getPageNumber(), pageable.getPageSize(),
                total, totalPages);
    }
}
