package com.anvicorp.api.mail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * A mail identity. A SELF-CONTAINED population — its own table, its own BCrypt
 * password hash, its own role enum ({@link MailRole}). Email address =
 * {@code localPart + "@" + domain.name}.
 *
 * <p>The {@code domain} association is EAGER on purpose: with
 * {@code spring.jpa.open-in-view=false} the mail JWT filter (which runs outside
 * any transaction) reads {@code domain.name}/{@code domain.id} while building the
 * principal, so the domain must already be loaded.</p>
 */
@Entity
@Table(name = "mail_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_mail_account_local_domain",
                columnNames = {"local_part", "domain_id"}),
        indexes = {
                @Index(name = "idx_mail_account_domain", columnList = "domain_id"),
                @Index(name = "idx_mail_account_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailAccount {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "domain_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_mail_account_domain"))
    private MailDomain domain;

    @Column(name = "local_part", nullable = false)
    private String localPart;

    @Column(name = "display_name")
    private String displayName;

    /** BCrypt hash (shared {@code PasswordEncoder} bean). Never plaintext. */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private MailRole role = MailRole.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private MailAccountStatus status = MailAccountStatus.ACTIVE;

    @Column(name = "must_change_password", nullable = false)
    @Builder.Default
    private Boolean mustChangePassword = Boolean.FALSE;

    @Column(name = "require_change_on_first_login", nullable = false)
    @Builder.Default
    private Boolean requireChangeOnFirstLogin = Boolean.TRUE;

    /** Mailbox quota in bytes. Defaults to 1 GiB. */
    @Column(name = "quota_bytes", nullable = false)
    @Builder.Default
    private Long quotaBytes = 1_073_741_824L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
