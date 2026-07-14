package com.anvicorp.api.mail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
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
 * A mail domain (e.g. {@code anvicorp.com}). Mail is multi-domain with strict
 * per-domain walling — an account belongs to exactly one domain and an email
 * address is {@code localPart@domain.name}.
 */
@Entity
@Table(name = "mail_domains",
        uniqueConstraints = @UniqueConstraint(name = "uq_mail_domain_name", columnNames = "name"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailDomain {

    @Id
    @GeneratedValue
    private UUID id;

    /** Canonical domain name, e.g. "anvicorp.com". Unique, lower-case. */
    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "display_name")
    private String displayName;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
