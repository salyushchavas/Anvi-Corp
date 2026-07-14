package com.anvicorp.api.listener;

import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.erm.CommunicationTemplateService;
import com.anvicorp.api.erm.ReasonCode;
import com.anvicorp.api.event.OfferReminderEvent;
import com.anvicorp.api.event.OfferVoidedEvent;
import com.anvicorp.api.event.ReportingStructureAssignedEvent;
import com.anvicorp.api.event.TentativeStartDateUpdatedEvent;
import com.anvicorp.api.notification.EmailProvider;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ERM Phase 4 — fans out per-event side effects for the 4 new offer +
 * new-hire events: OfferReminderEvent, OfferVoidedEvent,
 * ReportingStructureAssignedEvent, TentativeStartDateUpdatedEvent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OfferLifecycleListener {

    private final OfferRepository offerRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final CommunicationTemplateService templateService;
    private final EmailProvider emailProvider;
    private final UserNotificationDispatcher dispatcher;
    private final com.anvicorp.api.config.BrandConfig brand;

    // ── Reminder ──────────────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReminder(OfferReminderEvent e) {
        if (e == null || e.getOfferId() == null) return;
        try {
            Offer o = offerRepository.findById(e.getOfferId()).orElse(null);
            if (o == null) return;
            User applicant = applicantUser(o);
            if (applicant == null) return;
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("firstName", firstName(applicant));
            vars.put("roleTitle", nz(o.getRoleTitle()));
            vars.put("expiryDate", o.getExpiresAt() != null ? o.getExpiresAt().toString() : "soon");
            vars.put("ermName", ermName(e.getActorUserId()));
            renderAndSend("OFFER_REMINDER", vars, applicant,
                    "Reminder: your Anvi Corp offer is awaiting signature",
                    "Hello " + vars.get("firstName") + ",\n\nThis is a reminder that your "
                            + "offer for " + vars.get("roleTitle") + " is awaiting your "
                            + "signature.\n\n" + brand.signoffErm(),
                    "OFFER_REMINDER",
                    "/careers/intern/offer");
        } catch (Exception ex) {
            log.warn("[OfferLifecycle] reminder handler failed: {}", ex.getMessage());
        }
    }

    // ── Voided ────────────────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVoided(OfferVoidedEvent e) {
        if (e == null || e.getOfferId() == null || !e.isNotifyApplicant()) return;
        try {
            Offer o = offerRepository.findById(e.getOfferId()).orElse(null);
            if (o == null) return;
            User applicant = applicantUser(o);
            if (applicant == null) return;
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("firstName", firstName(applicant));
            vars.put("roleTitle", nz(o.getRoleTitle()));
            vars.put("voidReasonHuman", humanReason(e.getReasonCode()));
            vars.put("ermName", ermName(e.getActorUserId()));
            renderAndSend("OFFER_VOIDED", vars, applicant,
                    "Your Anvi Corp offer has been withdrawn",
                    "Hello " + vars.get("firstName") + ",\n\nWe regret to inform you that "
                            + "the offer extended to you for " + vars.get("roleTitle")
                            + " has been withdrawn.\n\n" + vars.get("voidReasonHuman")
                            + "\n\n" + brand.signoffErm(),
                    "OFFER_VOIDED",
                    "/careers/intern/applications");
        } catch (Exception ex) {
            log.warn("[OfferLifecycle] voided handler failed: {}", ex.getMessage());
        }
    }

    // ── Reporting structure assigned ──────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReportingStructureAssigned(ReportingStructureAssignedEvent e) {
        if (e == null) return;
        try {
            InternLifecycle lc = lifecycleRepository.findById(e.getInternLifecycleId())
                    .orElse(null);
            if (lc == null) return;
            User intern = userRepository.findById(e.getInternUserId()).orElse(null);
            String internName = intern != null ? intern.getFullName() : "intern";
            String employeeId = lc.getEmployeeId();
            String startDate = lc.getTentativeStartDate() != null
                    ? lc.getTentativeStartDate().toString() : "TBD";
            dispatchRole(e.getTrainerUserId(), "Trainer", internName, employeeId, startDate, e);
            dispatchRole(e.getEvaluatorUserId(), "Evaluator", internName, employeeId, startDate, e);
            dispatchRole(e.getManagerUserId(), "Manager", internName, employeeId, startDate, e);
        } catch (Exception ex) {
            log.warn("[OfferLifecycle] reporting-structure handler failed: {}", ex.getMessage());
        }
    }

    private void dispatchRole(java.util.UUID userId, String role,
                               String internName, String employeeId,
                               String startDate, ReportingStructureAssignedEvent e) {
        if (userId == null) return;
        try {
            User u = userRepository.findById(userId).orElse(null);
            if (u == null || u.getEmail() == null) return;
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("recipientFirstName", firstName(u));
            vars.put("role", role);
            vars.put("internName", internName);
            vars.put("employeeId", employeeId != null ? employeeId : "(pending)");
            vars.put("tentativeStartDate", startDate);
            renderAndSend("REPORTING_STRUCTURE_ASSIGNED", vars, u,
                    "You've been assigned to a new intern at Skyzen",
                    "Hello " + vars.get("recipientFirstName") + ",\n\nYou've been assigned "
                            + "as the " + role + " for " + internName + ".",
                    "REPORTING_STRUCTURE_ASSIGNED",
                    "/careers/" + role.toLowerCase());
        } catch (Exception ex) {
            log.debug("[OfferLifecycle] role dispatch failed for {}: {}", role, ex.getMessage());
        }
    }

    // ── Tentative start date updated ─────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStartDateUpdated(TentativeStartDateUpdatedEvent e) {
        if (e == null || e.getInternUserId() == null) return;
        try {
            User applicant = userRepository.findById(e.getInternUserId()).orElse(null);
            if (applicant == null || applicant.getEmail() == null) return;
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("firstName", firstName(applicant));
            vars.put("newDate", e.getNewDate() != null ? e.getNewDate().toString() : "TBD");
            vars.put("ermName", ermName(e.getUpdatedByUserId()));
            renderAndSend("START_DATE_UPDATED", vars, applicant,
                    "Your Anvi Corp start date has been updated",
                    "Hello " + vars.get("firstName") + ",\n\nYour tentative start date is "
                            + "now " + vars.get("newDate") + ".\n\n" + brand.signoffErm(),
                    "START_DATE_UPDATED",
                    "/careers/intern");
        } catch (Exception ex) {
            log.warn("[OfferLifecycle] start-date handler failed: {}", ex.getMessage());
        }
    }

    // ── Shared render+send+dispatch ───────────────────────────────────────

    private void renderAndSend(String templateKey, Map<String, Object> vars,
                                User recipient, String fallbackSubject,
                                String fallbackBody, String eventType,
                                String actionUrl) {
        String subject = fallbackSubject;
        String body = fallbackBody;
        try {
            var rendered = templateService.render(templateKey, "EMAIL", vars).orElse(null);
            if (rendered != null) {
                subject = rendered.subject() != null ? rendered.subject() : fallbackSubject;
                body = rendered.body() != null ? rendered.body() : fallbackBody;
            } else {
                log.info("[OfferLifecycle] template {} missing — using hard-coded copy",
                        templateKey);
            }
        } catch (Exception e) {
            log.warn("[OfferLifecycle] render failed for {}: {}", templateKey, e.getMessage());
        }
        try {
            emailProvider.sendRendered(recipient.getEmail(), subject, body);
        } catch (Exception e) {
            log.warn("[OfferLifecycle] email send failed for {}: {}",
                    recipient.getEmail(), e.getMessage());
        }
        try {
            dispatcher.dispatch(recipient.getId(), eventType,
                    recipient.getId(),
                    cap(subject, 200), cap(body, 400),
                    actionUrl, true);
        } catch (Exception e) {
            log.debug("[OfferLifecycle] dispatch failed: {}", e.getMessage());
        }
    }

    private String ermName(java.util.UUID actorId) {
        if (actorId == null) return "Skyzen ERM";
        try {
            return userRepository.findById(actorId)
                    .map(u -> u.getFullName() != null ? u.getFullName() : "Skyzen ERM")
                    .orElse("Skyzen ERM");
        } catch (Exception e) {
            return "Skyzen ERM";
        }
    }

    private static User applicantUser(Offer o) {
        if (o == null || o.getApplication() == null
                || o.getApplication().getCandidate() == null
                || o.getApplication().getCandidate().getUser() == null) return null;
        return o.getApplication().getCandidate().getUser();
    }

    private static String firstName(User u) {
        if (u == null) return "there";
        String full = u.getFullName();
        if (full == null || full.isBlank()) return "there";
        return full.trim().split("\\s+", 2)[0];
    }

    private static String humanReason(String code) {
        if (code == null) return "Please reach out to ERM with any questions.";
        try { return ReasonCode.valueOf(code).humanLabel(); }
        catch (Exception e) { return code.replace('_', ' ').toLowerCase(); }
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    private static String cap(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
