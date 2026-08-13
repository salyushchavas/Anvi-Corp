package com.anvicorp.api.listener;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.erm.CommunicationTemplateService;
import com.anvicorp.api.event.EverifyCaseOpenedEvent;
import com.anvicorp.api.event.EverifyStatusChangedEvent;
import com.anvicorp.api.event.WorkAuthExpiringEvent;
import com.anvicorp.api.notification.EmailProvider;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ERM Phase 5 — fans out per-event side effects for compliance lifecycle
 * events: E-Verify case opened / status changed, and the daily
 * work-auth-expiring tick. All AFTER_COMMIT + best-effort.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ComplianceLifecycleListener {

    private static final String INTERN_DASH = "/careers/intern";
    private static final String ERM_COMPLIANCE = "/careers/erm/compliance";

    private final UserRepository userRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final CommunicationTemplateService templateService;
    private final EmailProvider emailProvider;
    private final UserNotificationDispatcher dispatcher;
    private final com.anvicorp.api.config.BrandConfig brand;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCaseOpened(EverifyCaseOpenedEvent e) {
        if (e == null || e.getApplicantUserId() == null) return;
        try {
            User applicant = userRepository.findById(e.getApplicantUserId()).orElse(null);
            if (applicant == null) return;
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("firstName", firstName(applicant));
            renderAndSend("EVERIFY_CASE_OPENED", vars, applicant);
            dispatcher.dispatch(applicant.getId(), "EVERIFY_CASE_OPENED",
                    applicant.getId(),
                    "E-Verify case opened",
                    "Your E-Verify case has been opened. No action is required from you "
                            + "right now — we'll contact you if anything is needed.",
                    INTERN_DASH, true);
            dispatchToErm(e.getApplicantUserId(), "EVERIFY_CASE_OPENED",
                    "E-Verify case opened",
                    "E-Verify case opened for this intern.");
        } catch (Exception ex) {
            log.warn("[Compliance] EverifyCaseOpened handler failed: {}", ex.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(EverifyStatusChangedEvent e) {
        if (e == null || e.getApplicantUserId() == null || e.getNewStatus() == null) return;
        try {
            User applicant = userRepository.findById(e.getApplicantUserId()).orElse(null);
            if (applicant == null) return;
            String templateKey = switch (e.getNewStatus()) {
                case "TENTATIVE_NONCONFIRMATION" -> "EVERIFY_TENTATIVE_NONCONFIRMATION";
                case "EMPLOYMENT_AUTHORIZED" -> "EVERIFY_AUTHORIZED";
                default -> null;
            };
            if (templateKey == null) {
                // No template for this transition — still nudge the in-app bell.
                dispatchToErm(e.getApplicantUserId(), "EVERIFY_STATUS_CHANGED",
                        "E-Verify status: " + e.getNewStatus(),
                        "E-Verify case moved to " + e.getNewStatus());
                return;
            }
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("firstName", firstName(applicant));
            vars.put("ermName", ermNameFor(e.getApplicantUserId()));
            renderAndSend(templateKey, vars, applicant);
            dispatcher.dispatch(applicant.getId(), "EVERIFY_STATUS_CHANGED",
                    applicant.getId(),
                    "E-Verify update",
                    "Your E-Verify case status is now " + e.getNewStatus() + ".",
                    INTERN_DASH, true);
            dispatchToErm(e.getApplicantUserId(), "EVERIFY_STATUS_CHANGED",
                    "E-Verify: " + e.getNewStatus(),
                    "E-Verify case moved to " + e.getNewStatus());
        } catch (Exception ex) {
            log.warn("[Compliance] EverifyStatusChanged handler failed: {}", ex.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkAuthExpiring(WorkAuthExpiringEvent e) {
        if (e == null || e.getUserId() == null) return;
        try {
            User applicant = userRepository.findById(e.getUserId()).orElse(null);
            if (applicant == null) return;
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("firstName", firstName(applicant));
            vars.put("workAuthType", e.getWorkAuthType() != null
                    ? e.getWorkAuthType() : "work authorization");
            vars.put("expirationDate", e.getExpirationDate() != null
                    ? e.getExpirationDate().toString() : "soon");
            vars.put("daysUntilExpiration", e.getDaysUntilExpiration());
            vars.put("ermName", ermNameFor(e.getUserId()));
            renderAndSend("WORK_AUTH_EXPIRING", vars, applicant);
            dispatcher.dispatch(applicant.getId(), "WORK_AUTH_EXPIRING",
                    applicant.getId(),
                    "Work authorization expiring",
                    "Your work authorization expires on "
                            + (e.getExpirationDate() != null ? e.getExpirationDate() : "soon")
                            + ". Please connect with your ERM.",
                    INTERN_DASH, true);
            dispatchToErm(e.getUserId(), "WORK_AUTH_EXPIRING",
                    "Work auth expiring (" + e.getDaysUntilExpiration() + "d)",
                    "Intern work authorization expires "
                            + (e.getExpirationDate() != null ? "on " + e.getExpirationDate() : "soon")
                            + ".");
            // Parity-gap fill — the in-app row above lands with
            // emailSent=false but WORK_AUTH_EXPIRING is not in the
            // dispatcher's auto-email allow-list, and the auto-hook
            // couldn't carry the intern/authtype/deadline context anyway
            // (it composes plaintext from the row's title/body only).
            // Render + send the ERM email directly with the full context.
            emailErmWorkAuthExpiring(e, applicant);
        } catch (Exception ex) {
            log.warn("[Compliance] WorkAuthExpiring handler failed: {}", ex.getMessage());
        }
    }

    /**
     * Parity-gap fill — send the ERM the WORK_AUTH_EXPIRING_ERM template
     * with real context (which intern, which work-auth type, expiry
     * date, days remaining, deep link into the compliance record). The
     * intern already gets a mail via {@link #renderAndSend} in the
     * primary path above; this closes the ERM leg the auto-email hook
     * cannot cover.
     *
     * <p>Best-effort — a lookup or render failure is logged and skips
     * only the email leg; the in-app dispatch already happened.</p>
     */
    private void emailErmWorkAuthExpiring(WorkAuthExpiringEvent e, User intern) {
        try {
            var lc = lifecycleRepository.findByUserId(e.getUserId()).orElse(null);
            if (lc == null || lc.getErmId() == null) return;
            User erm = userRepository.findById(lc.getErmId()).orElse(null);
            if (erm == null || erm.getEmail() == null || erm.getEmail().isBlank()) return;
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("ermName", erm.getFullName() != null && !erm.getFullName().isBlank()
                    ? erm.getFullName() : brand.getName() + " ERM");
            vars.put("internName", intern != null && intern.getFullName() != null
                    ? intern.getFullName() : "the intern");
            vars.put("workAuthType", e.getWorkAuthType() != null
                    ? e.getWorkAuthType() : "work authorization");
            vars.put("expirationDate", e.getExpirationDate() != null
                    ? e.getExpirationDate().toString() : "soon");
            vars.put("daysUntilExpiration", e.getDaysUntilExpiration());
            vars.put("deepLink", ERM_COMPLIANCE + "/" + e.getUserId());
            renderAndSend("WORK_AUTH_EXPIRING_ERM", vars, erm);
        } catch (Exception ex) {
            log.warn("[Compliance] WorkAuthExpiring ERM-mail failed (non-fatal): {}",
                    ex.getMessage());
        }
    }

    private void dispatchToErm(UUID applicantUserId, String eventType,
                                String title, String body) {
        try {
            var lc = lifecycleRepository.findByUserId(applicantUserId).orElse(null);
            if (lc == null || lc.getErmId() == null) return;
            dispatcher.dispatch(lc.getErmId(), eventType, applicantUserId,
                    title, body, ERM_COMPLIANCE + "/" + applicantUserId, false);
        } catch (Exception e) {
            log.debug("[Compliance] ERM dispatch failed for {}: {}", eventType, e.getMessage());
        }
    }

    private void renderAndSend(String templateKey, Map<String, Object> vars, User recipient) {
        if (recipient == null || recipient.getEmail() == null) return;
        try {
            var rendered = templateService.render(templateKey, "EMAIL", vars).orElse(null);
            if (rendered == null) {
                log.debug("[Compliance] template {} missing — skipping send", templateKey);
                return;
            }
            emailProvider.sendRendered(recipient.getEmail(),
                    rendered.subject() != null ? rendered.subject() : templateKey,
                    rendered.body() != null ? rendered.body() : "");
        } catch (Exception e) {
            log.warn("[Compliance] renderAndSend failed for {}: {}", templateKey, e.getMessage());
        }
    }

    private String ermNameFor(UUID applicantUserId) {
        String defaultLabel = brand.getName() + " ERM";
        try {
            var lc = lifecycleRepository.findByUserId(applicantUserId).orElse(null);
            if (lc == null || lc.getErmId() == null) return defaultLabel;
            return userRepository.findById(lc.getErmId())
                    .map(u -> u.getFullName() != null ? u.getFullName() : defaultLabel)
                    .orElse(defaultLabel);
        } catch (Exception e) {
            return defaultLabel;
        }
    }

    private static String firstName(User u) {
        if (u == null) return "there";
        String full = u.getFullName();
        if (full == null || full.isBlank()) return "there";
        return full.trim().split("\\s+", 2)[0];
    }
}
