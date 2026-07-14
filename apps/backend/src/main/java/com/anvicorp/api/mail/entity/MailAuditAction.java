package com.anvicorp.api.mail.entity;

/**
 * Audited admin actions. Persisted as its {@code name()} string in
 * {@link MailAuditLog#getAction()} (a plain varchar, NOT an @Enumerated column)
 * so new actions never require a CHECK-constraint migration.
 */
public enum MailAuditAction {
    MAILBOX_CREATE,
    PASSWORD_RESET,
    MAILBOX_SUSPEND,
    MAILBOX_REACTIVATE,
    ROLE_CHANGE,
    DOMAIN_CREATE,
    DOMAIN_UPDATE,
    DOMAIN_DELETE
}
