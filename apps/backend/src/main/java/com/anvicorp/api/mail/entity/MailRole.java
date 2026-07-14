package com.anvicorp.api.mail.entity;

/**
 * Authority role for a mail account. The mail security chain grants authorities
 * as {@code "MAIL_" + role.name()} (e.g. MAIL_SUPER_ADMIN) and authorizes with
 * {@code hasAuthority(...)} only, never {@code hasRole(...)}. This keeps mail
 * authorities non-overlapping with any future {@code ROLE_*} world a careers
 * backend might introduce.
 */
public enum MailRole {
    USER,
    ADMIN,
    SUPER_ADMIN
}
