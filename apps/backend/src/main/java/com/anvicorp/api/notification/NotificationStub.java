package com.anvicorp.api.notification;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Facade around the wired {@link EmailProvider}. Delegates the actual send to
 * whichever provider was selected by {@link EmailProviderConfiguration} —
 * {@link SmtpEmailProvider} in prod, {@link LogEmailProvider} in dev /
 * unconfigured envs.
 *
 * <h2>History</h2>
 * Previously a concrete log-only stub (decision D2: "real send NOT wired");
 * also owned a {@code surface-stub} flag that echoed the verification code in
 * the registration HTTP response so the verify-email page could prefill it.
 * That field was a security hole — the code now rides email ONLY, never the
 * API. {@code surface-stub} is retired.
 *
 * <h2>Failure semantics</h2>
 * This class passes {@link EmailDeliveryException} through unchanged.
 * Callers decide:
 * <ul>
 *   <li>Auth verification flows: catch + surface a retryable HTTP error
 *       (and let the @Transactional roll back).</li>
 *   <li>Post-verification "applicant ID issued" notice: best-effort —
 *       catching + logging keeps the verification successful.</li>
 *   <li>Password reset: best-effort — never reveal account existence.</li>
 *   <li>Recruiter conditional-selection: best-effort — selection itself is
 *       the source of truth.</li>
 * </ul>
 */
@Component
public class NotificationStub {

    private final EmailProvider emailProvider;

    public NotificationStub(EmailProvider emailProvider) {
        this.emailProvider = emailProvider;
    }

    public void sendVerificationCode(String email, String code, Instant expiresAt) {
        emailProvider.sendVerificationCode(email, code, expiresAt);
    }

    public void sendApplicantIdIssued(String email, String applicantId) {
        emailProvider.sendApplicantIdIssued(email, applicantId);
    }

    public void sendPasswordReset(String email, String code, Instant expiresAt) {
        emailProvider.sendPasswordReset(email, code, expiresAt);
    }

    public void sendConditionalSelectionConfirmation(String email,
                                                     String jobPostingTitle,
                                                     String entityName) {
        emailProvider.sendConditionalSelectionConfirmation(email, jobPostingTitle, entityName);
    }
}
