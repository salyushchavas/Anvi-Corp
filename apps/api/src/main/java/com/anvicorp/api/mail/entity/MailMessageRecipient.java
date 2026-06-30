package com.anvicorp.api.mail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** A resolved (same-domain) recipient of a sent message. */
@Entity
@Table(name = "mail_message_recipients",
        indexes = {
                @Index(name = "idx_mail_recipient_message", columnList = "message_id"),
                @Index(name = "idx_mail_recipient_account", columnList = "recipient_account_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailMessageRecipient {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "recipient_account_id", nullable = false)
    private UUID recipientAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, length = 8)
    private MailRecipientType recipientType;
}
