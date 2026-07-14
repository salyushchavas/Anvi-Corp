package com.anvicorp.api.notification;

/**
 * Mail bridge Phase 2 — thread-scoped carrier for the sender local-part of
 * the in-flight notification.
 *
 * <p>{@link NotificationService#deliver} sets this just before invoking the
 * send {@code Runnable} and clears it in a {@code finally}. The
 * {@link BridgingEmailProvider} reads it inside the SMTP-or-internal routing
 * decision so the role-shaped sender address (e.g. {@code erm@},
 * {@code trainer@}) follows the notification all the way to the mail layer
 * — without changing the {@link EmailProvider} interface signature.</p>
 *
 * <p>{@code null} means "no context set": bridge falls back to
 * {@code noreply}. Safe-by-default for any send that bypasses
 * {@link NotificationService#deliver} (e.g. the listeners that call
 * {@code emailProvider.sendRendered(...)} directly).</p>
 *
 * <p>Strict pairing: every {@link #set(String)} MUST be followed by a
 * {@link #clear()} in the calling code's {@code finally} block. Without
 * that pairing a thread reused by Tomcat could leak the previous
 * notification's sender into an unrelated request.</p>
 */
public final class NotificationSenderContext {

    private static final ThreadLocal<String> SENDER_LOCAL_PART = new ThreadLocal<>();

    private NotificationSenderContext() {}

    /** Set the sender local-part for the current thread. */
    public static void set(String localPart) {
        SENDER_LOCAL_PART.set(localPart);
    }

    /** Current sender local-part, or {@code null} if unset. */
    public static String get() {
        return SENDER_LOCAL_PART.get();
    }

    /** Remove the per-thread value. MUST be called in a {@code finally}. */
    public static void clear() {
        SENDER_LOCAL_PART.remove();
    }
}
