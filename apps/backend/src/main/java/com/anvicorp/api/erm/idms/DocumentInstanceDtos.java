package com.anvicorp.api.erm.idms;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** DTOs for the IDMS Phase 2 living-document workflow. */
public final class DocumentInstanceDtos {

    private DocumentInstanceDtos() {}

    // ── ERM cockpit queue ────────────────────────────────────────────

    /**
     * Row shape on the ERM cockpit queue. Merges two sources: interns
     * awaiting an offer (no instance yet) + interns with an in-flight
     * instance. {@code stage} is a UI-facing chip label (Awaiting offer,
     * Sent, Signed — verifying, Returned, Verified, Executed, Revoked).
     */
    public record QueueRow(
            /** Present when this row is an actual instance; null for
             *  Awaiting-offer placeholder rows. */
            UUID instanceId,
            /** For Awaiting-offer rows this is the application id. */
            UUID applicationId,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String internEmail,
            String templateTitle,
            String templateKey,
            String stage,            // UI label
            String statusRaw,        // machine enum name (for filters)
            Instant lastActivityAt,
            boolean canRevoke,
            boolean canSupersede
    ) {}

    public record QueueResponse(
            List<QueueRow> items,
            int total
    ) {}

    // ── Template picker ──────────────────────────────────────────────

    public record PickableTemplate(
            UUID id,
            String key,
            String title,
            String description,
            int fieldCount
    ) {}

    public record PickableTemplateList(List<PickableTemplate> items) {}

    // ── Detail ───────────────────────────────────────────────────────

    /** Complete instance detail — the shape the fill/verify page reads. */
    public record InstanceDetail(
            UUID id,
            UUID templateId,
            String templateKey,
            String templateTitle,
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String internEmail,
            String status,
            int version,
            boolean internLocked,
            /** Canonical HTML snapshot (with data-field-id spans). */
            String canonicalHtml,
            /** JSON-encoded snapshot of the field schema (array of FieldEntry). */
            String fieldSchemaJson,
            /** Every filled value keyed by fieldId. Signature fields carry a
             *  presigned GET URL in {@code signatureUrl}. */
            Map<String, FieldValue> values,
            /** Presigned download URL for the executed PDF (when FINALIZED). */
            String finalPdfUrl,
            String returnReasonCode,
            String returnComments,
            String revokeReasonCode,
            String revokeComments,
            /** Prior FINALIZED instance this one replaced. Nullable. */
            UUID supersedesId,
            Instant sentAt,
            Instant internSubmittedAt,
            Instant returnedAt,
            Instant verifiedAt,
            Instant finalizedAt,
            Instant revokedAt,
            Instant createdAt,
            Instant updatedAt,
            List<ReviewLogEntry> history,
            /** UI action gates — the frontend uses these to enable/disable
             *  buttons without reproducing the state-machine rules. */
            InstanceActions actions
    ) {}

    public record FieldValue(
            String fieldId,
            String fieldName,
            String type,
            String assignee,
            String valueText,
            /** Presigned GET URL for the signature image (short TTL). */
            String signatureUrl,
            String filledByRole,
            Instant filledAt
    ) {}

    public record ReviewLogEntry(
            String action,
            String reasonCode,
            String comments,
            UUID actorUserId,
            String actorName,
            String actorRole,
            Instant createdAt
    ) {}

    public record InstanceActions(
            boolean canErmFill,
            boolean canErmSend,
            boolean canInternFill,
            boolean canInternSubmit,
            boolean canErmReturn,
            boolean canErmVerify,
            boolean canErmFinalize,
            boolean canErmRevoke,
            /** Revocation gate reason when {@code canErmRevoke=false}. */
            String revokeBlockedReason
    ) {}

    // ── Create + supersede ───────────────────────────────────────────

    public record CreateInstanceRequest(
            UUID templateId,
            /**
             * The intern's existing lifecycle. Preferred path — supply this
             * when the candidate is already onboarded (paid-after-unpaid,
             * re-sends, ACTIVE interns) so the send is byte-identical to
             * the pre-fix behavior.
             */
            UUID internLifecycleId,
            /**
             * Fallback path for interview-completed candidates who have not
             * yet been signed into a lifecycle. When supplied AND
             * {@code internLifecycleId} is null, the service resolves the
             * application → candidate → user, then find-or-creates the
             * lifecycle inline (mirrors {@code
             * OfferIdmsSigningService.finalizeIdmsSigning}): mints
             * employeeId, seeds {@code ermId = caller}, {@code
             * activeStatus = "PROSPECTIVE"}, runs the reporting-structure
             * auto-linker, advances {@code users.lifecycle_status} to
             * {@code EMPLOYEE_ID_CREATED}, and stamps the application to
             * {@code ACCEPTED}.
             */
            UUID applicationId,
            /** When set, the newly created instance will mark the referenced
             *  prior FINALIZED instance as SUPERSEDED as soon as this new one
             *  itself hits FINALIZED. */
            UUID supersedesInstanceId
    ) {}

    // ── Fill / sign ──────────────────────────────────────────────────

    public record FillFieldsRequest(
            /** Field values keyed by fieldId. Signature fields are handled
             *  via {@link SignFieldRequest} instead. */
            Map<String, String> values
    ) {}

    public record SignFieldRequest(
            String fieldId,
            /** Base64 PNG data URL from the SignaturePad component. */
            String signatureImageDataUrl,
            /** Optional typed name (SignaturePad already renders the initials
             *  strip; typed name is captured for the audit row). */
            String typedName
    ) {}

    // ── State transitions ────────────────────────────────────────────

    public record SendRequest() {}
    public record InternSubmitRequest() {}
    public record VerifyRequest() {}

    public record ReturnRequest(
            String reasonCode,
            String comments
    ) {}

    public record RevokeRequest(
            String reasonCode,
            String comments
    ) {}

    // ── Awaiting-offer bridge (reused shape) ─────────────────────────

    /** For the cockpit: an intern who completed interviews but doesn't yet
     *  have any IDMS document sent. Mirrors {@code ErmOfferDtos.AwaitingOfferRow}
     *  shape but decouples IDMS from that DTO namespace. */
    public record AwaitingRow(
            UUID applicationId,
            UUID candidateId,
            UUID internUserId,
            String internName,
            String internEmail,
            LocalDate interviewedAt
    ) {}

    public record AwaitingList(List<AwaitingRow> items) {}
}
