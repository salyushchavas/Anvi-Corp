package com.anvicorp.api.notification;

/**
 * Thread-scoped exemption flag for account-security mail (credential
 * delivery, password reset, mailbox provisioning) so it bypasses
 * {@link BridgingEmailProvider}'s "no external send to ACTIVATED intern"
 * gate.
 *
 * <p>Once an intern's company mailbox is ACTIVATED, all lifecycle mail
 * (project/evaluation/document/timesheet/…) is confined to their internal
 * inbox — the gate in {@link BridgingEmailProvider} suppresses SMTP
 * fall-through for these recipients. Security mail is different: if the
 * user can't sign in (forgot password, needs credentials to first-time
 * log in to their mailbox), the ONLY delivery channel is their external
 * address. Callers wrap those sends in
 * {@code SecurityMailContext.markSecurity()} so the bridge knows to let
 * the external send through.</p>
 *
 * <p>Strict pairing: every {@link #markSecurity()} MUST be followed by a
 * {@link #clear()} in {@code finally} — a Tomcat-pooled thread reusing
 * this ThreadLocal across requests would otherwise leak the exemption
 * into unrelated sends.</p>
 */
public final class SecurityMailContext {

    private static final ThreadLocal<Boolean> SECURITY = new ThreadLocal<>();

    private SecurityMailContext() {}

    public static void markSecurity() {
        SECURITY.set(Boolean.TRUE);
    }

    public static boolean isSecurity() {
        Boolean b = SECURITY.get();
        return b != null && b;
    }

    public static void clear() {
        SECURITY.remove();
    }
}
