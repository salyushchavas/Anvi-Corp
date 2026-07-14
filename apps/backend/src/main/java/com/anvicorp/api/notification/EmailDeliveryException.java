package com.anvicorp.api.notification;

/**
 * Thrown by {@link SmtpEmailProvider} when the underlying JavaMailSender call
 * fails (SMTP timeout, auth rejected, transport closed, etc.). Unchecked so
 * call sites that want best-effort sends can simply not catch — but the auth
 * flows (verification code) MUST catch and surface a retryable error.
 *
 * The log provider never throws this — it always succeeds at "logging".
 */
public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
