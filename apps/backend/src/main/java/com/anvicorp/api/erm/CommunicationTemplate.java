package com.anvicorp.api.erm;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * ERM-editable message template. UNIQUE on {@code key} so the
 * {@link CommunicationTemplateService} can resolve at render time without
 * scanning. Channel is part of the lookup so the same key (e.g.
 * {@code APPLICATION_REJECT}) can carry distinct EMAIL + IN_APP copy in
 * future without a schema change.
 *
 * <p>Phase 0 seeds a starter set via
 * {@link CommunicationTemplateSeeder}; per-phase listeners continue to use
 * hard-coded copy until the migration to {@code render(...)} lands.</p>
 */
@Entity
@Table(name = "communication_templates",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_communication_templates_key_channel",
                columnNames = { "template_key", "channel" }),
        indexes = {
                @Index(name = "idx_communication_templates_active",
                        columnList = "active")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunicationTemplate {

    @Id
    @GeneratedValue
    private UUID id;

    /** Stable lookup key, e.g. APPLICATION_REJECT, OFFER_DOC_REJECTED. */
    @Column(name = "template_key", nullable = false, length = 80)
    private String key;

    /** EMAIL | IN_APP. */
    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    /** Only meaningful for EMAIL templates. Mustache-style {{var}} OK. */
    @Column(name = "subject_template", columnDefinition = "TEXT")
    private String subjectTemplate;

    @Column(name = "body_template", nullable = false, columnDefinition = "TEXT")
    private String bodyTemplate;

    /** CSV of {{var}} placeholders this template uses. Nullable. */
    @Column(name = "variables_csv", length = 500)
    private String variablesCsv;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Column(name = "updated_by_id")
    private UUID updatedById;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
