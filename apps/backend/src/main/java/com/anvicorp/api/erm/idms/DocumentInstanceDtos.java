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
            /** Field-level correction lock — the ids ERM explicitly
             *  unlocked on the last RETURN. {@code null} means legacy
             *  behaviour (every intern-assigneed field editable while
             *  the doc is RETURNED); a populated list means ONLY
             *  those ids are editable. Server-side write path enforces
             *  the same restriction — see
             *  {@code DocumentInstanceService.applyFieldValues}. */
            List<String> unlockedFieldIds,
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
            InstanceActions actions,
            /**
             * True when the admin has edited the source template since this
             * instance's canonical HTML + field schema were snapshotted. Only
             * meaningfully computed for {@code DRAFT} instances — every non-
             * draft state returns {@code false} regardless of what the
             * underlying template has done, because sent / signed / finalized
             * docs are frozen (an admin template edit must NEVER alter them).
             *
             * <p>The ERM draft view uses this to surface an "Update to latest
             * template" banner + button; clicking POSTs
             * {@code /api/v1/erm/idms/{instanceId}/resync-template} which
             * re-snapshots the template onto the draft, preserves
             * still-existing field values by id, drops values whose fields
             * were removed / type-changed, and sets this back to
             * {@code false}.</p>
             */
            boolean isTemplateStale
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
            String revokeBlockedReason,
            /** ERM "correct &amp; re-send" — true only on
             *  {@code SENT_TO_INTERN}, the sent-but-unsigned window.
             *  Once the intern submits, correcting the document is the
             *  separate issue-corrected flow, not a reopen. */
            boolean canErmCorrect,
            /** ERM "issue corrected offer" — available once a party has
             *  signed (or the offer is already revoked), where the
             *  document must be preserved as a record rather than
             *  reopened. Deliberately a separate flag from
             *  {@code canErmCorrect} so a reopen can never swallow a
             *  document the intern has submitted. */
            boolean canErmIssueCorrected,
            /** Why issue-corrected is unavailable, when it is. Carries
             *  the revocation gate's reason (the action has to revoke
             *  the prior, so the gate applies) — shown rather than
             *  hiding the button, so the ERM sees the cause. */
            String issueCorrectedBlockedReason
    ) {}

    // ── Draft re-sync to latest template ────────────────────────────

    /**
     * Response payload for {@code POST /api/v1/erm/idms/{id}/resync-template}.
     *
     * <p>Wraps the refreshed instance detail AND a compact summary of what
     * happened to the ERM's already-entered field values during the re-
     * apply pass. The summary is populated on this endpoint ONLY — the
     * persistent {@link InstanceDetail} DTO carries no long-term "last
     * resync" state (a resync is a one-shot admin-drift catch-up, not
     * something the ERM re-visits between sessions), so putting the
     * summary on a wrapper here keeps the general instance-detail shape
     * unchanged for every other read path.</p>
     *
     * <p>The frontend uses {@code summary} to surface an honest, specific
     * confirmation after a resync: "Draft updated. N field(s) you'd filled
     * were removed or changed in the updated template: name1, name2.
     * Please review before sending." — so the ERM never discovers a
     * silently-vanished value on a legal doc about to be sent.</p>
     */
    public record ResyncTemplateResponse(
            InstanceDetail instance,
            ResyncSummary summary
    ) {}

    /**
     * Response payload for {@code POST /api/v1/erm/idms/{id}/reopen-template-update}.
     *
     * <p>Wraps the refreshed instance detail (post-backward-hop, with
     * the newly-latest template snapshotted onto the row) plus a
     * summary of what the reopen decided: which state we routed back
     * to, who's been re-routed, and which signatures were invalidated.
     * Endpoint-only — the persistent {@link InstanceDetail} DTO stays
     * unchanged for every other read path.</p>
     *
     * <p>The frontend uses this to surface an honest, specific toast:
     * "Reopened to RETURNED — Alice must re-review and re-sign; 2
     * field(s) were removed: <names>. Their signature was invalidated
     * (the template was edited after they signed)."</p>
     */
    public record ReopenTemplateResponse(
            InstanceDetail instance,
            ReopenSummary summary
    ) {}

    /**
     * Per-reopen decision + counts. The reroute + signature
     * invalidation counts are the two fields that make the reopen
     * legally auditable at the response layer.
     */
    public record ReopenSummary(
            /** Status the doc was in BEFORE the reopen. */
            String fromStatus,
            /** Status the doc was routed to. If equal to
             *  {@code fromStatus}, the reopen was a metadata-only no-op
             *  (e.g. only AUTO fields changed, or template metadata
             *  changed without any party-relevant field diff). */
            String toStatus,
            /** Who's been re-routed to re-engage:
             *  {@code "ERM"} — reopened to DRAFT for ERM re-do (fields
             *      the ERM owns changed, or content over-approximation).
             *  {@code "INTERN"} — reopened to RETURNED for intern
             *      re-review + re-sign (only intern-owned fields
             *      changed).
             *  {@code "BOTH_VIA_ERM"} — DRAFT: ERM re-does first,
             *      forward flow carries it to the intern on Send.
             *  {@code "AUTO_ONLY"} — no human re-routing; AUTO fields
             *      re-resolved in place, no status change.
             *  {@code "NONE"} — no party-relevant change; watermark
             *      re-stamped, no status change, no side effects
             *      (metadata-only template edit). */
            String reroutedTo,
            /** Value rows kept (id + type matched) — also counts renamed. */
            int keptCount,
            /** Human names of REMOVED fields whose values were dropped —
             *  used verbatim in the frontend notice. May contain null
             *  entries if a legacy value row had no fieldName snapshot;
             *  filter defensively before rendering. */
            List<String> droppedRemovedFieldNames,
            /** Number of signature rows that were invalidated
             *  ({@code valueRepo.delete} on the signature value rows).
             *  A signature is invalidated when its owner's fields
             *  changed, or when the canonical HTML content differs
             *  (over-approximation — safer to ask for a re-sign than
             *  keep a signature over changed content). */
            int invalidatedSignatureCount,
            /** Field ids added by the admin — the appropriate party
             *  will fill these on the reopened cycle. */
            int newFieldCount
    ) {}

    /**
     * Per-resync counts + dropped-field names. Names are resolved from
     * the OLD schema during the value-walk (before re-snapshot) so a
     * removed field's user-facing label is still available for the
     * notice — after re-snapshot, the id is gone from the current
     * schema and the name would be unresolvable.
     */
    public record ResyncSummary(
            /** Value rows kept (id + type matched) — also counts renamed. */
            int keptCount,
            /** Value rows deleted because the field id is gone from the
             *  new schema. */
            int droppedRemovedCount,
            /** Value rows deleted because the field's type changed
             *  (e.g. TEXT → SIGNATURE) and the payload wouldn't render. */
            int droppedTypeChangedCount,
            /** Field ids added by the admin — no existing value rows
             *  affected; the ERM will fill these before Send. */
            int newFieldCount,
            /** Human-readable names of fields whose values were dropped
             *  because the FIELD itself was removed. Used verbatim in the
             *  frontend notice. Names for type-changed drops are intentionally
             *  omitted — the field is still there, just with a different
             *  type, so it's discoverable in the refreshed form.
             *
             *  <p>May contain nulls if a legacy value row had no field_name
             *  snapshot — callers must filter defensively.</p> */
            List<String> droppedRemovedFieldNames
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
             *  value string is capped at 50_000 chars. Bumped from the
             *  initial 5000 after audit found long content_block fields
             *  (legal clauses, multi-paragraph offers) can legitimately
             *  exceed 5 KB. 50 KB per field is still short of any DoS
             *  payload while giving genuine content-block bodies room. */
            @NotNull Map<String, @Size(max = 50_000) String> values,
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
            Map<String, @Size(max = 50_000) String> values,
            Long expectedUpdatedAt
    ) {}

    public record InternSubmitRequest(
            Map<String, @Size(max = 50_000) String> values,
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
            /** Field-level unlock list. NULL or empty = current
             *  behaviour (every intern-assigneed field becomes
             *  editable — backward compatible). Populated = ONLY
             *  those field ids are editable on the returned instance;
             *  the intern sees the rest read-only. Signatures are
             *  untouched by return by default; include a signature
             *  field's id here to explicitly re-open it (the case
             *  where the signature itself is what's wrong). */
            @Size(max = 200) List<@Size(max = 64) String> unlockedFieldIds,
            Long expectedUpdatedAt
    ) {}

    public record RevokeRequest(
            @NotBlank @Size(max = 40) String reasonCode,
            @Size(max = 2000) String comments,
            Long expectedUpdatedAt
    ) {}

    /**
     * Request for {@code POST /api/v1/erm/idms/{id}/correct-and-reopen}
     * — the ERM pulling a sent, unsigned offer back to DRAFT to fix a
     * value they got wrong.
     *
     * <p>{@code reasonCode} is OPTIONAL here, unlike {@link ReturnRequest}
     * and {@link RevokeRequest} where it is mandatory. Those two are
     * addressed to someone else — the intern reads the reason on their
     * correction surface, and a revocation reason is a record of a
     * decision taken about them. This one is the ERM annotating their
     * own typo for the audit trail; requiring a reason code to fix your
     * own mistake is friction with no reader. Omitted, it records as
     * {@code ERM_CORRECTION}.</p>
     */
    public record CorrectRequest(
            @Size(max = 40) String reasonCode,
            @Size(max = 2000) String comments,
            Long expectedUpdatedAt
    ) {}

    /**
     * Request for {@code POST /api/v1/erm/idms/{id}/issue-corrected} —
     * revoke a signed offer and issue a corrected copy pre-filled from
     * it.
     *
     * <p>{@code reasonCode} is REQUIRED here, unlike
     * {@link CorrectRequest}. That one fixes a document nobody has
     * signed and notifies no one, so a reason has no reader. This one
     * revokes a SIGNED document and the intern is told it was
     * withdrawn — they are owed a reason they can read.</p>
     */
    public record IssueCorrectedRequest(
            @NotBlank @Size(max = 40) String reasonCode,
            @Size(max = 2000) String comments,
            Long expectedUpdatedAt
    ) {}

    /**
     * Response for the issue-corrected action.
     *
     * <p>{@code droppedFieldNames} exists because a value silently
     * vanishing from a legal document is the failure mode worth being
     * loud about. When the admin has edited the template since the prior
     * offer was created, a field the ERM had filled may no longer exist
     * (or may have changed type), and its answer cannot carry. The ERM
     * is told which ones by name so they can re-check the corrected
     * offer before sending rather than discovering a blank on the
     * executed PDF.</p>
     */
    public record IssueCorrectedResponse(
            /** The new DRAFT offer, pre-filled and linked to the prior
             *  via {@code supersedesId}. */
            InstanceDetail instance,
            /** How many of the prior's answers carried over. */
            int carriedCount,
            /** Fields whose answers could NOT carry, by name. Empty in
             *  the common case where the template hasn't moved. */
            List<String> droppedFieldNames
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
