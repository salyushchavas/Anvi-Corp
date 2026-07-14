package com.anvicorp.api.mail.entity;

/** Recipient kind on a message. BCC recipients are never exposed to others. */
public enum MailRecipientType {
    TO,
    CC,
    BCC
}
