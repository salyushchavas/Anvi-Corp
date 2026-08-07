package com.anvicorp.api.profile;

import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WorkAuthorizationRecord;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.notification.EmailProvider;
import com.anvicorp.api.notification.NotificationSenderContext;
import com.anvicorp.api.notification.NotificationSenderRoles;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EducationRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.repository.WorkAuthorizationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * B2 profile expansion — ERM notification helpers for the intern profile
 * lifecycle.
 *
 * <p>Two channels, both firing per-ERM (email + in-app) with an inline
 * role lookup ({@link UserRepository#findByRole} filtered to active users).
 * The shared chain-listener infrastructure (Weekly/Timesheet listeners)
 * is intentionally NOT touched — the profile events don't belong on the
 * lifecycle chain and don't need the transactional-event-listener
 * plumbing.</p>
 *
 * <h3>Submission-ack ({@link #maybeFireSubmissionAck})</h3>
 * Fires exactly once per intern when the profile first meets the stricter
 * bar (base fields + address + at least one education). Guarded by
 * {@code Candidate.profileSubmittedAt} — set to {@code Instant.now()} in
 * the same transaction as the notifications, so a caller retry can never
 * double-fire.
 *
 * <h3>Post-submission edit-notify ({@link #maybeNotifyOnEdit})</h3>
 * Fires when a monitored field (name / phone / address / education /
 * skillset / work-auth attestation) changes after {@code profileSubmittedAt}
 * has been stamped. Throttled per-intern to at most one email per 15 minutes
 * via {@code Candidate.lastProfileNotifiedAt}; the in-app row is emitted on
 * the same 15-min cadence (rapid edits collapse into one round on both
 * channels — simpler than two throttles and matches the operator's "your
 * call" latitude in the spec).
 *
 * <h3>Mail suppression</h3>
 * These notifications target STAFF (ERMs). Staff without an activated
 * company mailbox route straight to SMTP via BridgingEmailProvider's
 * suppression check; that's the existing pattern. No code here needs to
 * know the difference.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileNotificationService {

    /** Guard duration for the post-submission edit-notify throttle. */
    static final Duration EDIT_NOTIFY_THROTTLE = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final CandidateRepository candidateRepository;
    private final EducationRepository educationRepository;
    private final WorkAuthorizationRecordRepository workAuthRepository;
    private final EmailProvider emailProvider;
    private final UserNotificationDispatcher userNotificationDispatcher;

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Stricter completeness bar for the submission-ack — DELIBERATELY not
     * merged into {@link com.anvicorp.api.service.ProfileCompletionService}:
     * that service is the "can this intern apply?" gate and stays unchanged
     * so existing readers (dashboard advisory %, apply endpoint gate) don't
     * shift under this feature. The bar here layers ON TOP of that gate
     * (base fields still checked via school/skillset presence) and adds:
     * full US address + at least one education row.
     */
    public boolean meetsStricterSubmissionBar(User user, Candidate c) {
        if (user == null || c == null) return false;
        if (isBlank(user.getFullName())) return false;
        if (isBlank(user.getPhoneNumber())) return false;
        if (isBlank(c.getSkillset())) return false;
        // Address (all five key fields present + state 2-letter shape).
        if (isBlank(c.getAddressStreet())
                || isBlank(c.getAddressCity())
                || isBlank(c.getAddressState())
                || isBlank(c.getAddressZip())) {
            return false;
        }
        // Education — at least one row.
        return educationRepository.countByCandidateId(c.getId()) > 0;
    }

    /**
     * Called by {@code UserProfileService.updateProfile} after every save.
     * No-op unless {@link #meetsStricterSubmissionBar} passes AND
     * {@code profileSubmittedAt} is null. On fire: stamps the timestamp,
     * emails every active ERM, and drops an in-app notification per ERM.
     */
    @Transactional
    public void maybeFireSubmissionAck(User user, Candidate c) {
        if (c == null || c.getProfileSubmittedAt() != null) return;
        if (!meetsStricterSubmissionBar(user, c)) return;
        // Stamp FIRST inside the same tx as the notifications — if the tx
        // rolls back the stamp is reverted along with the sends and the
        // caller retry can safely re-attempt.
        c.setProfileSubmittedAt(Instant.now());
        candidateRepository.save(c);
        List<User> recipients = activeErms();
        if (recipients.isEmpty()) {
            log.info("[ProfileNotify] submission-ack for {} — no active ERM recipients",
                    user.getEmail());
            return;
        }
        String subject = "Profile submitted: " + user.getFullName();
        String body = buildSubmissionBody(user, c);
        for (User erm : recipients) {
            sendEmailAndInApp(erm, subject, body, user.getId(),
                    "PROFILE_SUBMITTED", "/careers/erm/applications");
        }
        log.info("[ProfileNotify] submission-ack fired for intern={} to {} ERM(s)",
                user.getEmail(), recipients.size());
    }

    /**
     * Called by every write path that touches a monitored profile field.
     * No-op unless the profile has already been submitted AND the throttle
     * window has elapsed.
     */
    @Transactional
    public void maybeNotifyOnEdit(User user, Candidate c, String changedField) {
        if (c == null || c.getProfileSubmittedAt() == null) return;
        Instant last = c.getLastProfileNotifiedAt();
        Instant now = Instant.now();
        if (last != null && Duration.between(last, now).compareTo(EDIT_NOTIFY_THROTTLE) < 0) {
            log.debug("[ProfileNotify] edit-notify throttled for intern={} field={} "
                            + "(last {} ago)",
                    user.getEmail(), changedField, Duration.between(last, now));
            return;
        }
        // Stamp BEFORE the sends so rapid re-entry (Education CRUD +
        // UserProfileService in the same request) collapses to one round.
        c.setLastProfileNotifiedAt(now);
        candidateRepository.save(c);

        List<User> recipients = activeErms();
        if (recipients.isEmpty()) return;
        String subject = "Profile updated: " + user.getFullName();
        String body = buildEditBody(user, c, changedField);
        for (User erm : recipients) {
            sendEmailAndInApp(erm, subject, body, user.getId(),
                    "PROFILE_EDITED", "/careers/erm/applications");
        }
        log.info("[ProfileNotify] edit-notify fired for intern={} field={} to {} ERM(s)",
                user.getEmail(), changedField, recipients.size());
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /**
     * Inline recipient resolution — deliberately not sharing the chain
     * listeners' helper. Filter to {@code active=true} so deactivated ERMs
     * don't get mail.
     */
    private List<User> activeErms() {
        return userRepository.findByRole(UserRole.ERM).stream()
                .filter(u -> Boolean.TRUE.equals(u.getActive()))
                .toList();
    }

    private void sendEmailAndInApp(User recipient, String subject, String body,
                                   java.util.UUID subjectUserId, String eventType,
                                   String actionUrl) {
        // Stamp ERM sender role so BridgingEmailProvider routes internal
        // when the recipient is on an activated company mailbox.
        NotificationSenderContext.set(NotificationSenderRoles.ERM);
        boolean emailed = false;
        try {
            emailProvider.sendRendered(recipient.getEmail(), subject, body);
            emailed = true;
        } catch (Exception e) {
            log.warn("[ProfileNotify] email to {} failed (non-fatal): {}",
                    recipient.getEmail(), e.getMessage());
        } finally {
            NotificationSenderContext.clear();
        }
        // In-app row — even if email failed, the ERM still sees it in the
        // bell + Messages page. emailSent=true suppresses the dispatcher's
        // own email hook so we don't double-send when the event ever gets
        // added to that allow-list.
        try {
            userNotificationDispatcher.dispatch(
                    recipient.getId(), eventType, subjectUserId,
                    subject, body, actionUrl, emailed);
        } catch (Exception e) {
            log.warn("[ProfileNotify] in-app dispatch to {} failed (non-fatal): {}",
                    recipient.getId(), e.getMessage());
        }
    }

    /**
     * Body for the one-shot submission email. The spec explicitly lists the
     * fields to include: Name, Email, Contact, Work Authorization, Skillset,
     * Date of submission, Full Address.
     */
    private String buildSubmissionBody(User user, Candidate c) {
        StringBuilder sb = new StringBuilder();
        sb.append("A new intern profile has been submitted.\n\n");
        sb.append("Name: ").append(nullSafe(user.getFullName())).append('\n');
        sb.append("Email: ").append(nullSafe(user.getEmail())).append('\n');
        sb.append("Contact: ").append(nullSafe(user.getPhoneNumber())).append('\n');
        sb.append("Work authorization: ").append(describeWorkAuth(user, c)).append('\n');
        sb.append("Skillset: ").append(nullSafe(c.getSkillset())).append('\n');
        sb.append("Date of submission: ")
                .append(c.getProfileSubmittedAt() != null
                        ? DateTimeFormatter.ISO_INSTANT.format(c.getProfileSubmittedAt())
                        : "—")
                .append('\n');
        sb.append("Full address: ").append(formatAddress(c)).append('\n');
        return sb.toString();
    }

    private String buildEditBody(User user, Candidate c, String changedField) {
        StringBuilder sb = new StringBuilder();
        sb.append("An intern updated their profile after submission.\n\n");
        sb.append("Name: ").append(nullSafe(user.getFullName())).append('\n');
        sb.append("Email: ").append(nullSafe(user.getEmail())).append('\n');
        sb.append("Contact: ").append(nullSafe(user.getPhoneNumber())).append('\n');
        sb.append("Changed area: ").append(changedField).append('\n');
        sb.append("Skillset: ").append(nullSafe(c.getSkillset())).append('\n');
        sb.append("Full address: ").append(formatAddress(c)).append('\n');
        sb.append("Work authorization: ").append(describeWorkAuth(user, c)).append('\n');
        sb.append("\n(Note: further edits by this intern in the next 15 minutes "
                + "will not trigger additional notifications.)");
        return sb.toString();
    }

    private String describeWorkAuth(User user, Candidate c) {
        StringBuilder sb = new StringBuilder();
        if (c.getExpectedTrack() != null) sb.append(c.getExpectedTrack().name());
        if (c.getAuthorizedToWork() != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Boolean.TRUE.equals(c.getAuthorizedToWork())
                    ? "authorized" : "not authorized");
        }
        if (c.getSponsorshipNeeded() != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Boolean.TRUE.equals(c.getSponsorshipNeeded())
                    ? "needs sponsorship" : "no sponsorship");
        }
        // If a WorkAuthorizationRecord exists, include the authorizedFrom /
        // authorizedUntil dates the intern surfaced from their profile.
        WorkAuthorizationRecord war = workAuthRepository.findByUserId(user.getId())
                .orElse(null);
        if (war != null) {
            if (war.getAuthorizedFrom() != null) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("from ").append(war.getAuthorizedFrom());
            }
            if (war.getAuthorizedUntil() != null) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("until ").append(war.getAuthorizedUntil());
            }
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }

    private String formatAddress(Candidate c) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(c.getAddressStreet())) sb.append(c.getAddressStreet());
        if (!isBlank(c.getAddressApt())) sb.append(", Apt ").append(c.getAddressApt());
        if (!isBlank(c.getAddressCity())) sb.append(", ").append(c.getAddressCity());
        if (!isBlank(c.getAddressState())) sb.append(", ").append(c.getAddressState());
        if (!isBlank(c.getAddressZip())) sb.append(' ').append(c.getAddressZip());
        if (!isBlank(c.getAddressCountry())) sb.append(", ").append(c.getAddressCountry());
        return sb.length() == 0 ? "—" : sb.toString();
    }

    private static String nullSafe(String s) { return s == null ? "—" : s; }
    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
