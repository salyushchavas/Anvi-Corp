package com.anvicorp.api.mail.entity;

/**
 * The five system folders. Custom/nested folders are a later phase — this enum
 * is the whole folder model for now (no folder table). Stored as a plain enum
 * name on mail_mailbox_entries.
 */
public enum MailFolder {
    INBOX,
    SENT,
    DRAFTS,
    ARCHIVE,
    TRASH
}
