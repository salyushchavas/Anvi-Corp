package com.anvicorp.api.notification;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.UserNotification;
import com.anvicorp.api.repository.UserNotificationRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 7 dispatcher for in-app notification rows. Distinct from the
 * existing {@code NotificationService} which is the email send + per-event
 * idempotency ledger ({@code SentNotification}). This dispatcher writes
 * one {@code UserNotification} row per recipient so the bell + Messages
 * page can render real per-user feeds with read-tracking.
 *
 * <h2>Email hook (2026-07)</h2>
 * The dispatcher now also sends an email to the recipient for
 * {@link #EMAIL_ENABLED_EVENT_TYPES} — the "action required / must be
 * informed" set derived from the operator's lifecycle-email matrix. The
 * {@code emailSent} caller-supplied flag is the dedupe signal:
 *
 * <ul>
 *   <li>{@code emailSent=true} → the caller already sent an email
 *       out-of-band (via {@code EmailProvider} directly, or via
 *       {@code InternNotificationService.notifyIntern}). The dispatcher
 *       recognises this and NEVER re-sends — the flag was already the
 *       "I sent an email" contract; this hook honours it.</li>
 *   <li>{@code emailSent=false} → in-app only until today. The hook now
 *       delivers an email if the {@code eventType} is in
 *       {@link #EMAIL_ENABLED_EVENT_TYPES} AND not muted by
 *       {@code app.notifications.email-hook.disabled-events}.</li>
 * </ul>
 *
 * <p>Robustness: the email send is wrapped in try/catch and runs after
 * the notification row is persisted. A mail-send failure logs at WARN
 * and never affects the in-app notification or the parent transaction.
 * Runs in {@link Propagation#REQUIRES_NEW} so the whole dispatcher call
 * survives caller rollbacks.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserNotificationDispatcher {

    /**
     * Event types whose recipients get an email from the dispatcher hook
     * when the caller passes {@code emailSent=false}. Explicit allow-list
     * (not deny-list) so a new event type has to be intentionally opted
     * into email delivery — safer default for a system with ~90 event
     * types where most fan-outs are intentionally in-app-only.
     *
     * <p>Events NOT in this set that already email via a direct path
     * (caller passes {@code emailSent=true}) continue to email as before
     * — this hook is additive, never a replacement.</p>
     */
    private static final Set<String> EMAIL_ENABLED_EVENT_TYPES = Set.of(
            // ── Hiring: action-required alerts for Manager + ERM ──
            "HIRE_APPROVAL_PENDING",          // Manager: ERM finalised scorecard, decide hire/no-hire
            "MANAGER_HIRE_APPROVED",          // ERM: Manager approved hire (send offer)
            "MANAGER_HIRE_DECLINED",          // ERM: Manager declined hire (send rejection)
            "MANAGER_HIRE_HOLD",              // ERM: Manager parked hire on HOLD — nudge / gather info
            "SELECTION_ACKNOWLEDGED",         // ERM: intern acknowledged selection — send offer
            "APPLICATION_INFO_PROVIDED",      // ERM: applicant returned requested info

            // ── Interview lifecycle for the interviewer ──
            "INTERVIEW_RESCHEDULED",          // Interviewer: time changed
            "INTERVIEW_CANCELLED",            // Interviewer: interview cancelled

            // ── Offer expiry job (once per offer, low-noise) ──
            "OFFER_EXPIRING",                 // Applicant + ERM: 24h before expiry
            "OFFER_EXPIRED",                  // Applicant + ERM: past expiry

            // ── Active lifecycle: submissions the reviewer must act on ──
            "PROJECT_SUBMITTED",              // Trainer: intern submitted, ready to review
            "WEEKLY_REPORT_SUBMITTED",        // ERM: intern submitted weekly report
            "WEEKLY_REPORT_VERIFIED",         // Manager: ERM verified, approve or reject
            "TIMESHEET_SUBMITTED",            // ERM: intern submitted timesheet
            "TIMESHEET_VERIFIED",             // Manager: ERM verified, approve or reject

            // ── Escalations / exceptions ──
            "EXCEPTION_OPENED",               // ERM: auto-opened exception needs triage
            "EXCEPTION_ASSIGNED",             // Assigned staff: exception routed to them
            "EXCEPTION_RESOLVED",             // ERM owner: another staff resolved
            "EXCEPTION_DISMISSED"             // ERM owner: another staff dismissed
    );

    private final UserNotificationRepository repository;
    private final UserRepository userRepository;
    private final EmailProvider emailProvider;

    /** Master kill-switch for the hook. Default ON. */
    @Value("${app.notifications.email-hook.enabled:true}")
    private boolean emailHookEnabled;

    /**
     * Per-event runtime override — comma-separated event type names.
     * A member of this list is treated as if it were NOT in
     * {@link #EMAIL_ENABLED_EVENT_TYPES}. Lets operators mute a noisy
     * event in prod without a redeploy.
     */
    @Value("${app.notifications.email-hook.disabled-events:}")
    private String disabledEventsCsv;

    /**
     * Frontend origin used to promote a relative {@code actionUrl} into
     * a clickable absolute URL for mail clients. Matches the pattern
     * peer email code uses (e.g. {@code EvaluationNotificationFanout},
     * {@code DocumentEmailListener}).
     */
    @Value("${app.frontend.base-url:https://anvicorp.com}")
    private String frontendBaseUrl;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(UUID recipientUserId, String eventType, UUID subjectUserId,
                          String title, String body, String actionUrl, boolean emailSent) {
        if (recipientUserId == null) return;
        UserNotification saved;
        try {
            UserNotification row = UserNotification.builder()
                    .recipientUserId(recipientUserId)
                    .eventType(eventType)
                    .subjectUserId(subjectUserId)
                    .title(title)
                    .body(body)
                    .actionUrl(actionUrl)
                    .emailSent(emailSent)
                    .emailSentAt(emailSent ? Instant.now() : null)
                    .build();
            saved = repository.save(row);
        } catch (Exception e) {
            log.warn("[UserNotif] dispatch failed (non-fatal): {}", e.getMessage());
            return;
        }
        // Email hook — dedupes on the caller-supplied emailSent flag. If
        // the caller already emailed, we NEVER re-send. If the event is
        // in the allow-list and the recipient has an email, deliver a
        // plaintext notification with an absolute deep-link.
        if (!emailSent) {
            tryEmailHook(saved);
        }
    }

    // ── Email hook internals ─────────────────────────────────────────────

    private void tryEmailHook(UserNotification row) {
        try {
            if (!emailHookEnabled) return;
            String eventType = row.getEventType();
            if (eventType == null) return;
            if (!EMAIL_ENABLED_EVENT_TYPES.contains(eventType)) return;
            if (isMutedByConfig(eventType)) {
                log.debug("[UserNotif] email-hook muted by config event={}", eventType);
                return;
            }
            User recipient = userRepository.findById(row.getRecipientUserId()).orElse(null);
            if (recipient == null) {
                log.debug("[UserNotif] email-hook skip — recipient {} not found",
                        row.getRecipientUserId());
                return;
            }
            String email = recipient.getEmail();
            if (email == null || email.isBlank()) {
                log.debug("[UserNotif] email-hook skip — recipient {} has no email",
                        row.getRecipientUserId());
                return;
            }
            String subject = row.getTitle() != null && !row.getTitle().isBlank()
                    ? row.getTitle()
                    : "Anvi Corp — notification";
            String body = composeBody(row);
            // Stamp the sender-role context so BridgingEmailProvider can
            // route the send FROM the acting staff mailbox (erm@/trainer@/
            // evaluator@/manager@) when the recipient is a fully-activated
            // intern. Falls back to the raw SMTP send for everyone else —
            // the bridge's guards handle it. Strict set/clear so a
            // Tomcat-pooled thread never leaks the role into the next
            // request.
            String senderLocalPart = resolveSenderLocalPart(eventType);
            NotificationSenderContext.set(senderLocalPart);
            try {
                emailProvider.sendRendered(email, subject, body);
            } finally {
                NotificationSenderContext.clear();
            }
            row.setEmailSent(true);
            row.setEmailSentAt(Instant.now());
            try {
                repository.save(row);
            } catch (Exception e) {
                log.debug("[UserNotif] email-hook post-save flag update failed "
                        + "(non-fatal): {}", e.getMessage());
            }
            log.info("[UserNotif] email-hook sent event={} recipient={} subject='{}'",
                    eventType, recipient.getId(), subject);
        } catch (Exception e) {
            // Never let an email failure roll back the in-app dispatch
            // or the parent business event.
            log.warn("[UserNotif] email-hook failed (non-fatal) event={}: {}",
                    row.getEventType(), e.getMessage());
        }
    }

    private String composeBody(UserNotification row) {
        StringBuilder sb = new StringBuilder();
        if (row.getBody() != null && !row.getBody().isBlank()) {
            sb.append(row.getBody());
        }
        String path = row.getActionUrl();
        if (path != null && !path.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("Open in your dashboard:\n").append(absoluteLink(path));
        }
        return sb.length() > 0 ? sb.toString() : "You have a new notification in your Anvi Corp dashboard.";
    }

    /**
     * Promote a relative path to an absolute URL using
     * {@code app.frontend.base-url}. Leaves already-absolute URLs
     * unchanged (some future events may pass fully-qualified links).
     */
    private String absoluteLink(String path) {
        if (path == null) return "";
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        String base = frontendBaseUrl == null ? "" : frontendBaseUrl.replaceAll("/+$", "");
        return base + path;
    }

    /**
     * Map the string event-type back to a {@link NotificationEventType} value
     * so {@link NotificationSenderRoles#forEvent} can resolve the sender
     * local-part. Returns {@link NotificationSenderRoles#DEFAULT_LOCAL_PART}
     * (noreply) when the event isn't in the enum — in-app string events
     * outside the enum (e.g. TIMESHEET_APPROVED, FEEDBACK_PUBLISHED) fall
     * back to noreply here; those flows already stamp the correct sender
     * role themselves via {@code InternNotificationService.notifyInternFromRole}
     * upstream, so the bridge routing still fires for the internal-mail leg.
     */
    private String resolveSenderLocalPart(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return NotificationSenderRoles.DEFAULT_LOCAL_PART;
        }
        try {
            return NotificationSenderRoles.forEvent(NotificationEventType.valueOf(eventType));
        } catch (IllegalArgumentException notInEnum) {
            return NotificationSenderRoles.DEFAULT_LOCAL_PART;
        }
    }

    private boolean isMutedByConfig(String eventType) {
        if (disabledEventsCsv == null || disabledEventsCsv.isBlank()) return false;
        Set<String> muted = Arrays.stream(disabledEventsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return muted.contains(eventType);
    }
}
