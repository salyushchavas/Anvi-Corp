package com.anvicorp.api.erm.idms;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
            boolean canSupersede,
            /** REVOKED-tab columns. Null on every non-revoked row so the
             *  cockpit's other tabs render exactly as before; the Revoked
             *  Offers section reads these to show the plain-English reason
             *  and the revoke timestamp without a second round-trip. */
            Instant revokedAt,
            String revokeReasonHuman,
            /** AWAITING_OFFER rows only — {@code "PENDING"} when the intern
             *  hasn't clicked "Receive my offer letter" on their dashboard,
             *  {@code "READY"} once they have. Null for every non-awaiting
             *  row so the cockpit's other tabs are unaffected. Drives the
             *  "Acknowledgement pending" chip + the disabled Send-document
             *  button copy on the queue. Server-authoritative: matches
             *  {@link com.anvicorp.api.erm.offer.SelectionAckPolicy#needsAck}
             *  so the row's chip never disagrees with the 409 that
             *  {@link com.anvicorp.api.erm.idms.DocumentInstanceService#create}
             *  throws for offer-family templates. */
            String selectionAckStatus
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
             *  via {@link SignFieldRequest} instead. Wave 3 — the map itself
             *  is required (empty is fine — a no-op autosave), and each
             *  value string is capped at 5000 chars to prevent an attacker
             *  from writing megabytes into the canonical HTML through a
             *  single field. */
            @NotNull Map<String, @Size(max = 5000) String> values,
            /**
             * Optional optimistic-lock token — the client's cached
             * {@code detail.updatedAt} in millis-since-epoch. When
             * supplied AND ≥ 5 s older than the server's current
             * updatedAt, the server rejects with 409 "changed
             * elsewhere". Small tolerance covers the client's own
             * concurrent /sign call bumping updatedAt between renders.
             * Omit for legacy callers.
             */
            Long expectedUpdatedAt
    ) {}

    public record SignFieldRequest(
            @NotBlank @Size(max = 200) String fieldId,
            /** Base64 PNG data URL from the SignaturePad component. Signature
             *  payloads legitimately run to a few tens of KB; 200 KB is well
             *  above any real touchpad rendering but short of DoS payloads. */
            @NotBlank @Size(max = 200_000) String signatureImageDataUrl,
            /** Optional typed name (SignaturePad already renders the initials
             *  strip; typed name is captured for the audit row). */
            @Size(max = 200) String typedName
    ) {}

    // ── State transitions ────────────────────────────────────────────
    // Every transition request accepts an optional
    // {@code expectedUpdatedAt} (millis) — the client's cached
    // detail.updatedAt. If supplied AND stale by > 5 s, the server
    // 409s with a "changed elsewhere" message so cross-tab / cross-
    // actor conflicts surface cleanly instead of silently overwriting.
    // Send + InternSubmit additionally accept a values map so the
    // transition atomically persists the client's authoritative bytes
    // in the same DB transaction — a final keystroke can never be
    // dropped between debounced auto-save and the send/submit click.

    public record SendRequest(
            Map<String, @Size(max = 5000) String> values,
            Long expectedUpdatedAt
    ) {}

    public record InternSubmitRequest(
            Map<String, @Size(max = 5000) String> values,
            Long expectedUpdatedAt
    ) {}

    public record VerifyRequest(
            Long expectedUpdatedAt
    ) {}

    public record FinalizeRequest(
            Long expectedUpdatedAt
    ) {}

    public record ReturnRequest(
            @NotBlank @Size(max = 40) String reasonCode,
            @Size(max = 2000) String comments,
            Long expectedUpdatedAt
    ) {}

    public record RevokeRequest(
            @NotBlank @Size(max = 40) String reasonCode,
            @Size(max = 2000) String comments,
            Long expectedUpdatedAt
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
