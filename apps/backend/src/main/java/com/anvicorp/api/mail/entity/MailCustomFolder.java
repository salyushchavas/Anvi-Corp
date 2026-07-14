package com.anvicorp.api.mail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A per-account, user-created custom folder (flat — no nesting/reorder). The
 * system {@link MailFolder} enum is unchanged; a mailbox entry lives in EXACTLY
 * one of (system enum | custom folder) via the nullable
 * {@code MailMailboxEntry.customFolderId} FK. Additive: ddl-auto creates this
 * table; existing rows are untouched.
 */
@Entity
@Table(name = "mail_folders",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_mail_folder_account_name", columnNames = {"account_id", "name"}),
        indexes = @Index(name = "idx_mail_folder_account", columnList = "account_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailCustomFolder {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 100)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
