package com.anvicorp.api.erm.idms;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * IDMS Phase 2 — one filled value per (instance, fieldId). Text fields
 * carry {@code valueText}; signature fields point at a vault Document row
 * via {@code signatureDocumentId} (category=SIGNATURE_IMAGE, PII-class).
 * AUTO fields are resolved from platform data at instance-create time and
 * stored here just like ERM values so the render path stays uniform.
 */
@Entity
@Table(name = "document_instance_field_values",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_di_field_values_field",
                columnNames = {"instance_id", "field_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentInstanceFieldValue {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "instance_id", nullable = false)
    private UUID instanceId;

    @Column(name = "field_id", nullable = false, length = 64)
    private String fieldId;

    /** Snapshotted from the schema for legible admin queries. */
    @Column(name = "field_name", length = 200)
    private String fieldName;

    @Column(name = "value_text", columnDefinition = "TEXT")
    private String valueText;

    @Column(name = "signature_document_id")
    private UUID signatureDocumentId;

    @Column(name = "filled_by_user_id")
    private UUID filledByUserId;

    /** {@code ERM | INTERN | AUTO}. */
    @Column(name = "filled_by_role", length = 16)
    private String filledByRole;

    @Column(name = "filled_at", nullable = false)
    private Instant filledAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (this.filledAt == null) this.filledAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}
