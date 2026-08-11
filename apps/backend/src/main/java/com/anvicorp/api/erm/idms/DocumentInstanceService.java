package com.anvicorp.api.erm.idms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.admin.editabletemplates.EditableTemplate;
import com.anvicorp.api.admin.editabletemplates.EditableTemplateRepository;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.intern.DocumentVaultService;
import com.anvicorp.api.intern.ReportingStructureAutoLinker;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.service.EmployeeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * IDMS Phase 2 — the living document workflow's service surface.
 *
 * <p>Every state transition:
 * <ol>
 *   <li>Loads the instance with SELECT FOR UPDATE so two writes can't
 *       race through the same guard.</li>
 *   <li>Checks role guard (ERM+SUPER_ADMIN for the ERM path; the owning
 *       intern for the intern path).</li>
 *   <li>Checks state guard (only from valid predecessor states).</li>
 *   <li>Mutates in place + persists.</li>
 *   <li>Writes a {@link DocumentInstanceReviewLog} + a global
 *       {@link AuditLog} row.</li>
 *   <li>Dispatches an in-app + best-effort external notification.</li>
 * </ol>
 *
 * <p>The revocation gate — codified in {@link #canRevoke} — allows revoke
 * from any post-DRAFT non-terminal state <b>only while the intern hasn't
 * started</b>: {@code lifecycle.startedAt == null} AND (either both
 * joining/tentativeStart dates are null, or today &lt;= the earliest one
 * set). Post-start, revoke returns 409 with clean copy.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentInstanceService {

    private static final String CATEGORY_INSTANCE_PDF = "EDITABLE_DOC_INSTANCE_PDF";
    private static final String CATEGORY_SIGNATURE = "SIGNATURE_IMAGE";
    private static final Duration SIGNATURE_URL_TTL = Duration.ofMinutes(15);
    private static final int SIGNATURE_MAX_BYTES = 500 * 1024; // 500KB

    private final DocumentInstanceRepository instanceRepo;
    private final DocumentInstanceFieldValueRepository valueRepo;
    private final DocumentInstanceReviewLogRepository reviewLogRepo;
    private final EditableTemplateRepository templateRepo;
    private final InternLifecycleRepository lifecycleRepo;
    private final UserRepository userRepo;
    private final CandidateRepository candidateRepo;
    private final ApplicationRepository applicationRepo;
    private final DocumentRepository documentRepo;
    private final DocumentVaultService vault;
    // S3StorageService no longer needed here — the final PDF is served
    // by the /final-pdf endpoints via DocumentVaultService.readDocument
    // (which auto-decrypts the PII envelope). Direct S3 presign would
    // return the AES envelope, not the PDF.
    private final DocumentInstancePdfRenderer pdfRenderer;
    private final AuditLogRepository auditLogRepo;
    private final ObjectMapper objectMapper;
    private final UserNotificationDispatcher dispatcher;
    // Find-or-create-lifecycle plumbing — added for the IDMS send fix so
    // ERM can send a document to an interview-completed candidate without
    // the pipeline first requiring the legacy offer-signing to have run.
    private final EmployeeIdGenerator employeeIdGenerator;
    private final ReportingStructureAutoLinker reportingStructureAutoLinker;
    private final com.anvicorp.api.intern.InternLifecycleService internLifecycleService;
    // Selection-ack gate — mirrors the legacy ErmOfferService gate so the
    // IDMS-path offer-family send can't bypass the intern's acknowledgment
    // click on their dashboard. Same policy component both surfaces read.
    private final com.anvicorp.api.erm.offer.SelectionAckPolicy selectionAckPolicy;

    // ── Create + supersede + list ────────────────────────────────────

    /**
     * Create a fresh instance from an active template for a specific intern.
     * Snapshots the template's canonical HTML + field schema onto the row
     * so template edits never mutate this in-flight doc. AUTO-assignee
     * fields are pre-resolved from platform data (intern.fullName /
     * legalName / email / employeeId / position / today) and stored as
     * regular {@link DocumentInstanceFieldValue} rows so the render path
     * stays uniform.
     */
    @Transactional
    public DocumentInstanceDtos.InstanceDetail create(
            DocumentInstanceDtos.CreateInstanceRequest req, User caller) {
        requireErmOrAdmin(caller);
        if (req == null || req.templateId() == null) {
            throw new BadRequestException("templateId required");
        }
        if (req.internLifecycleId() == null && req.applicationId() == null) {
            throw new BadRequestException("internLifecycleId or applicationId required");
        }
        EditableTemplate template = templateRepo.findById(req.templateId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Template not found: " + req.templateId()));
        if (!template.isActive()) {
            throw new BadRequestException("Template is not active — reactivate before use.");
        }
        if (template.getCanonicalHtml() == null || template.getFieldSchemaJson() == null) {
            throw new BadRequestException(
                    "Template hasn't been saved with fields yet. Complete authoring in the studio first.");
        }
        // Selection-ack gate — offer-family templates cannot be created for an
        // application whose intern hasn't clicked "Receive my offer letter" on
        // their dashboard. Mirrors the legacy ErmOfferService.createAndSend
        // gate so the IDMS path can't bypass it. Applied before
        // resolveOrCreateLifecycle so we don't mint an employee id / lifecycle
        // for an unacknowledged candidate. Non-offer templates (NDA, etc.)
        // are unaffected. Only the applicationId path is checked: an existing
        // lifecycleId means the candidate is already past the ack step.
        // Case-insensitive on the template key — legacy rows are UPPERCASE
        // ("OFFER_LETTER"), new rows are lowercase ("offer_letter") per the
        // deriveKey normalisation. See isOfferFamily() for the paired rule.
        if (req.applicationId() != null
                && template.getKey() != null
                && template.getKey().toLowerCase(java.util.Locale.ROOT)
                        .startsWith("offer_")) {
            Application app = applicationRepo.findById(req.applicationId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Application not found: " + req.applicationId()));
            if (selectionAckPolicy.needsAck(app)) {
                throw new ConflictException(
                        "Acknowledgement pending — the intern must click "
                                + "'Receive my offer letter' on their dashboard "
                                + "before you can send an offer.");
            }
        }
        // Resolve the lifecycle. Preferred: an explicit id (existing behavior
        // — byte-identical to the pre-fix path). Fallback: applicationId, in
        // which case we find-or-create the lifecycle inline before continuing.
        InternLifecycle lc = resolveOrCreateLifecycle(req, caller);
        User intern = userRepo.findById(lc.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Intern user not found: " + lc.getUserId()));

        // Guard against the partial UNIQUE — surface a clean 409 instead
        // of a DB constraint error.
        boolean liveExists = instanceRepo.findByInternLifecycleIdOrderByCreatedAtDesc(lc.getId())
                .stream()
                .filter(d -> d.getTemplateId().equals(template.getId()))
                .anyMatch(d -> !DocumentInstanceStatus.NOT_LIVE.contains(d.getStatus()));
        if (liveExists) {
            throw new ConflictException(
                    "There's already a live " + template.getTitle() + " for this person. "
                            + "Revoke or finalize the existing one first.");
        }

        DocumentInstance instance = DocumentInstance.builder()
                .templateId(template.getId())
                .internLifecycleId(lc.getId())
                .internUserId(intern.getId())
                .createdByErmId(caller.getId())
                .status(DocumentInstanceStatus.DRAFT)
                .version(1)
                .internLocked(false)
                .templateTitle(template.getTitle())
                .templateKey(template.getKey())
                .snapshotCanonicalHtml(template.getCanonicalHtml())
                .snapshotFieldSchemaJson(template.getFieldSchemaJson())
                .supersedesId(req.supersedesInstanceId())
                .build();
        instance = instanceRepo.save(instance);

        // Pre-fill AUTO fields — resolved once at create so the render path
        // is uniform + audit shows exactly what platform values landed.
        Instant now = Instant.now();
        List<FieldSchemaEntry> fields = parseSchema(template.getFieldSchemaJson());
        for (FieldSchemaEntry f : fields) {
            if (!"AUTO".equalsIgnoreCase(f.assignee())) continue;
            String value = resolveAutoBinding(f.defaultSource(), intern, lc);
            if (value != null) {
                DocumentInstanceFieldValue val = DocumentInstanceFieldValue.builder()
                        .instanceId(instance.getId())
                        .fieldId(f.id())
                        .fieldName(f.name())
                        .valueText(value)
                        .filledByUserId(null)
                        .filledByRole("AUTO")
                        .filledAt(now)
                        .build();
                valueRepo.save(val);
            }
        }

        writeReview(instance.getId(), "CREATE", null,
                "Instance created from template " + template.getKey(),
                caller, "ERM");
        writeAudit("CREATE", instance, caller,
                Map.of("templateId", template.getId(),
                        "lifecycleId", lc.getId(),
                        "supersedesId", req.supersedesInstanceId() == null ? "" : req.supersedesInstanceId().toString()));
        log.info("[IDMS] created instance={} template={} lifecycle={}",
                instance.getId(), template.getId(), lc.getId());
        return toDetail(instance, caller);
    }

    @Transactional(readOnly = true)
    public DocumentInstanceDtos.InstanceDetail getDetail(UUID instanceId, User caller) {
        DocumentInstance instance = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        requireCanRead(instance, caller);
        return toDetail(instance, caller);
    }

    /** Detail-fetch variant used from the ERM cockpit's Verify page —
     *  stamps {@code lastErmViewedAt} so the "viewed before verify" gate
     *  works. Not readonly because of that stamp. */
    @Transactional
    public DocumentInstanceDtos.InstanceDetail getDetailErmView(UUID instanceId, User caller) {
        requireErmOrAdmin(caller);
        DocumentInstance instance = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        if (instance.getStatus() == DocumentInstanceStatus.INTERN_SUBMITTED) {
            instance.setLastErmViewedAt(Instant.now());
            instanceRepo.save(instance);
        }
        return toDetail(instance, caller);
    }

    // ── Fill + sign ──────────────────────────────────────────────────

    /**
     * Bulk-write text field values. Enforces per-field ownership: ERM can
     * only write ERM fields, intern can only write INTERN fields, AUTO
     * fields are always rejected (they were resolved at create).
     */
    @Transactional
    public DocumentInstanceDtos.InstanceDetail fillFields(
            UUID instanceId,
            DocumentInstanceDtos.FillFieldsRequest req,
            User caller) {
        if (req == null || req.values() == null || req.values().isEmpty()) {
            throw new BadRequestException("values map required");
        }
        DocumentInstance instance = instanceRepo.findByIdForUpdate(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        String callerRole = requireCanFill(instance, caller);
        assertNotStaleForWrite(instance, req.expectedUpdatedAt());
        applyFieldValues(instance, req.values(), callerRole, caller);
        writeAudit("FILL", instance, caller,
                Map.of("fieldCount", req.values().size(), "role", callerRole));
        return toDetail(instance, caller);
    }

    /** Signature-field write. Stores the PNG bytes in the vault as a
     *  SIGNATURE_IMAGE Document (encrypted, PII-class) then links its id
     *  on the field value row. */
    @Transactional
    public DocumentInstanceDtos.InstanceDetail signField(
            UUID instanceId,
            DocumentInstanceDtos.SignFieldRequest req,
            User caller) {
        if (req == null || req.fieldId() == null || req.signatureImageDataUrl() == null) {
            throw new BadRequestException("fieldId + signatureImageDataUrl required");
        }
        byte[] pngBytes = decodeDataUrl(req.signatureImageDataUrl());
        if (pngBytes.length == 0) {
            throw new BadRequestException("signatureImageDataUrl could not be decoded");
        }
        if (pngBytes.length > SIGNATURE_MAX_BYTES) {
            throw new BadRequestException("Signature image exceeds 500KB.");
        }
        DocumentInstance instance = instanceRepo.findByIdForUpdate(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        String callerRole = requireCanFill(instance, caller);
        List<FieldSchemaEntry> schema = parseSchema(instance.getSnapshotFieldSchemaJson());
        FieldSchemaEntry f = schema.stream()
                .filter(x -> req.fieldId().equals(x.id())).findFirst()
                .orElseThrow(() -> new BadRequestException("Unknown field id: " + req.fieldId()));
        if (!"SIGNATURE".equalsIgnoreCase(f.type())) {
            throw new BadRequestException("Field is not a signature field: " + f.name());
        }
        requireFieldOwner(callerRole, f.assignee(), f.name());

        Document doc = vault.saveDocument(
                instance.getInternUserId(),
                "signature-" + f.id() + ".png",
                "image/png",
                pngBytes,
                CATEGORY_SIGNATURE,
                DocumentVaultService.defaultSensitivityForCategory(CATEGORY_SIGNATURE),
                caller.getId());

        Instant now = Instant.now();
        DocumentInstanceFieldValue existing = valueRepo
                .findByInstanceIdAndFieldId(instance.getId(), req.fieldId())
                .orElse(null);
        if (existing == null) {
            existing = DocumentInstanceFieldValue.builder()
                    .instanceId(instance.getId())
                    .fieldId(req.fieldId())
                    .fieldName(f.name())
                    .valueText(req.typedName())
                    .signatureDocumentId(doc.getId())
                    .filledByUserId(caller.getId())
                    .filledByRole(callerRole)
                    .filledAt(now)
                    .build();
        } else {
            existing.setSignatureDocumentId(doc.getId());
            existing.setValueText(req.typedName());
            existing.setFilledByUserId(caller.getId());
            existing.setFilledByRole(callerRole);
            existing.setFilledAt(now);
        }
        valueRepo.save(existing);
        writeAudit("SIGN", instance, caller,
                Map.of("fieldId", req.fieldId(), "role", callerRole, "documentId", doc.getId()));
        log.info("[IDMS] sign field instance={} field={} by={} role={}",
                instance.getId(), req.fieldId(), caller.getId(), callerRole);
        return toDetail(instance, caller);
    }

    // ── State transitions ────────────────────────────────────────────

    @Transactional
    public DocumentInstanceDtos.InstanceDetail send(
            UUID instanceId,
            DocumentInstanceDtos.SendRequest req,
            User caller) {
        requireErmOrAdmin(caller);
        DocumentInstance instance = instanceRepo.findByIdForUpdate(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        // Idempotency: if already SENT (double-click, retry after
        // network hiccup, browser back+forward), return current state
        // — no re-notify, no re-audit, no duplicate side effects.
        if (instance.getStatus() == DocumentInstanceStatus.SENT_TO_INTERN) {
            return toDetail(instance, caller);
        }
        if (instance.getStatus() != DocumentInstanceStatus.DRAFT) {
            throw new ConflictException(
                    "Only draft documents can be sent. Current state: "
                            + instance.getStatus());
        }
        assertNotStaleForTransition(instance,
                req == null ? null : req.expectedUpdatedAt());
        // Authoritative-payload: the client's latest values ride on the
        // send request itself, persisted in this same transaction so a
        // final keystroke between debounced auto-save and Send can
        // never be lost. Backwards-compat: if omitted, use whatever
        // fillFields last persisted.
        if (req != null && req.values() != null && !req.values().isEmpty()) {
            applyFieldValues(instance, req.values(), "ERM", caller);
        }
        assertRequiredFieldsCompleteFor(instance, "ERM");
        Instant now = Instant.now();
        instance.setStatus(DocumentInstanceStatus.SENT_TO_INTERN);
        instance.setSentAt(now);
        instance.setInternLocked(true); // intern can now start their side
        instanceRepo.save(instance);
        writeReview(instance.getId(), "SEND", null, null, caller, "ERM");
        writeAudit("SEND", instance, caller, Map.of("status", "SENT_TO_INTERN"));
        notifyIntern(instance,
                "A document is ready for your signature",
                "Please review and sign \"" + instance.getTemplateTitle() + "\".",
                internDocPath(instance));
        return toDetail(instance, caller);
    }

    @Transactional
    public DocumentInstanceDtos.InstanceDetail internSubmit(
            UUID instanceId,
            DocumentInstanceDtos.InternSubmitRequest req,
            User caller) {
        DocumentInstance instance = instanceRepo.findByIdForUpdate(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        if (!caller.getId().equals(instance.getInternUserId())) {
            throw new ForbiddenException("Only the assigned intern can submit this document.");
        }
        // Idempotency: already INTERN_SUBMITTED → no-op, return state.
        if (instance.getStatus() == DocumentInstanceStatus.INTERN_SUBMITTED) {
            return toDetail(instance, caller);
        }
        if (instance.getStatus() != DocumentInstanceStatus.SENT_TO_INTERN
                && instance.getStatus() != DocumentInstanceStatus.RETURNED) {
            throw new ConflictException(
                    "You can only submit documents that are awaiting your response.");
        }
        assertNotStaleForTransition(instance,
                req == null ? null : req.expectedUpdatedAt());
        if (req != null && req.values() != null && !req.values().isEmpty()) {
            applyFieldValues(instance, req.values(), "INTERN", caller);
        }
        assertRequiredFieldsCompleteFor(instance, "INTERN");
        Instant now = Instant.now();
        instance.setStatus(DocumentInstanceStatus.INTERN_SUBMITTED);
        instance.setInternSubmittedAt(now);
        instance.setInternLocked(true); // frozen until ERM verify / return
        // Clear any prior return reason — the fresh submission overwrites.
        instance.setReturnReasonCode(null);
        instance.setReturnComments(null);
        // Clear the field-level unlock set — a fresh correction cycle
        // must start from a new ERM selection. Without this, a later
        // RETURN that omits unlockedFieldIds would inherit the previous
        // narrowed set instead of falling back to legacy "all editable"
        // behaviour.
        instance.setUnlockedFieldIdsJson(null);
        // "Viewed before verify" gate — reset in the SAME transaction as
        // the resubmit so the stale stamp from a PREVIOUS submission
        // never satisfies the verify() gate at :469-472. Without this,
        // the ERM could VERIFY a resubmission after RETURN without
        // opening the fixed doc, because lastErmViewedAt persisted from
        // their first review.
        instance.setLastErmViewedAt(null);
        instanceRepo.save(instance);
        writeReview(instance.getId(), "INTERN_SUBMIT", null, null, caller, "INTERN");
        writeAudit("INTERN_SUBMIT", instance, caller, Map.of("status", "INTERN_SUBMITTED"));
        // ERM notification — the owning ERM (createdByErmId).
        notifyUser(instance.getCreatedByErmId(),
                "IDMS_DOC_INTERN_SUBMITTED",
                "Signed document awaiting your verification",
                "\"" + instance.getTemplateTitle() + "\" is ready to verify.",
                // Deep-link uses the cockpit's own tab= param, matching the
                // key TABS.find((t) => t.key === tab) reads from the URL.
                "/careers/erm/offers?tab=verifying");
        return toDetail(instance, caller);
    }

    @Transactional
    public DocumentInstanceDtos.InstanceDetail returnForCorrections(
            UUID instanceId,
            DocumentInstanceDtos.ReturnRequest req,
            User caller) {
        requireErmOrAdmin(caller);
        if (req == null || req.reasonCode() == null || req.reasonCode().isBlank()) {
            throw new BadRequestException("reasonCode required");
        }
        DocumentInstance instance = instanceRepo.findByIdForUpdate(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        // Idempotency: already RETURNED → no-op, no re-audit / re-notify.
        if (instance.getStatus() == DocumentInstanceStatus.RETURNED) {
            return toDetail(instance, caller);
        }
        if (instance.getStatus() != DocumentInstanceStatus.INTERN_SUBMITTED) {
            throw new ConflictException(
                    "Only submitted documents can be returned for corrections.");
        }
        assertNotStaleForTransition(instance, req.expectedUpdatedAt());
        Instant now = Instant.now();
        instance.setStatus(DocumentInstanceStatus.RETURNED);
        instance.setReturnedAt(now);
        instance.setReturnReasonCode(req.reasonCode());
        instance.setReturnComments(req.comments());
        instance.setInternLocked(false); // intern can edit their fields again
        // Field-level narrowing. NULL/empty → clear the column (legacy
        // behaviour: every intern field editable). Populated → persist
        // the exact set so applyFieldValues can enforce it + the
        // intern-side UI knows which anchors to render editable.
        List<String> unlocked = req.unlockedFieldIds();
        if (unlocked != null && !unlocked.isEmpty()) {
            // Defensive validation — every listed id must exist in the
            // instance's snapshot schema. Rejects a UI regression that
            // sends stale ids from a previous template version.
            List<FieldSchemaEntry> schema = parseSchema(instance.getSnapshotFieldSchemaJson());
            java.util.Set<String> knownIds = new java.util.HashSet<>();
            for (FieldSchemaEntry f : schema) knownIds.add(f.id());
            for (String id : unlocked) {
                if (id == null || !knownIds.contains(id)) {
                    throw new BadRequestException(
                            "Unknown unlockedFieldIds entry: " + id);
                }
            }
            try {
                instance.setUnlockedFieldIdsJson(objectMapper.writeValueAsString(unlocked));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new BadRequestException(
                        "Could not serialise unlockedFieldIds: " + e.getMessage());
            }
        } else {
            instance.setUnlockedFieldIdsJson(null);
        }
        instance.setVersion(instance.getVersion() + 1);
        instanceRepo.save(instance);
        writeReview(instance.getId(), "RETURN", req.reasonCode(), req.comments(), caller, "ERM");
        writeAudit("RETURN", instance, caller,
                Map.of("reasonCode", req.reasonCode(),
                        "comments", req.comments() == null ? "" : req.comments(),
                        "unlockedFieldIds",
                        unlocked == null ? "" : String.join(",", unlocked)));
        notifyIntern(instance,
                "Please make corrections to your document",
                "Your ERM has asked for changes to \"" + instance.getTemplateTitle() + "\".",
                internDocPath(instance));
        return toDetail(instance, caller);
    }

    @Transactional
    public DocumentInstanceDtos.InstanceDetail verify(
            UUID instanceId,
            DocumentInstanceDtos.VerifyRequest req,
            User caller) {
        requireErmOrAdmin(caller);
        DocumentInstance instance = instanceRepo.findByIdForUpdate(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        // Idempotency: already VERIFIED → no-op, return state.
        if (instance.getStatus() == DocumentInstanceStatus.VERIFIED) {
            return toDetail(instance, caller);
        }
        if (instance.getStatus() != DocumentInstanceStatus.INTERN_SUBMITTED) {
            throw new ConflictException(
                    "Only submitted documents can be verified. Current state: "
                            + instance.getStatus());
        }
        if (instance.getLastErmViewedAt() == null) {
            throw new ConflictException(
                    "Please open and review the completed document before verifying.");
        }
        assertNotStaleForTransition(instance,
                req == null ? null : req.expectedUpdatedAt());
        Instant now = Instant.now();
        instance.setStatus(DocumentInstanceStatus.VERIFIED);
        instance.setVerifiedAt(now);
        instanceRepo.save(instance);
        writeReview(instance.getId(), "VERIFY", null, null, caller, "ERM");
        writeAudit("VERIFY", instance, caller, Map.of("status", "VERIFIED"));
        // No intern-facing notify on VERIFY — finalize will be the message.
        return toDetail(instance, caller);
    }

    /**
     * Terminal happy-path — render the executed PDF via openhtmltopdf and
     * store in vault, then move to FINALIZED. Also marks any prior
     * SUPERSEDES target as SUPERSEDED.
     */
    @Transactional
    public DocumentInstanceDtos.InstanceDetail finalize(
            UUID instanceId,
            DocumentInstanceDtos.FinalizeRequest req,
            User caller) {
        requireErmOrAdmin(caller);
        DocumentInstance instance = instanceRepo.findByIdForUpdate(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        // Idempotency: already FINALIZED → no-op. Critically, this
        // avoids re-rendering the PDF on a duplicate click, which would
        // waste executor slots + emit a duplicate notify + duplicate
        // audit row + a second PDF Document in the vault.
        if (instance.getStatus() == DocumentInstanceStatus.FINALIZED) {
            return toDetail(instance, caller);
        }
        if (instance.getStatus() != DocumentInstanceStatus.VERIFIED) {
            throw new ConflictException(
                    "Only verified documents can be finalized. Current state: "
                            + instance.getStatus());
        }
        assertNotStaleForTransition(instance,
                req == null ? null : req.expectedUpdatedAt());

        // Build the text + signature-url maps for the renderer. Date-type
        // fields get normalised to MM/DD/YYYY here so the PDF matches the
        // live preview exactly (both use IdmsDateFormat / formatIsoDateMdy).
        List<DocumentInstanceFieldValue> vals = valueRepo.findByInstanceId(instance.getId());
        List<FieldSchemaEntry> schemaForRender = parseSchema(instance.getSnapshotFieldSchemaJson());
        Map<String, String> typeByFieldId = new HashMap<>();
        for (FieldSchemaEntry f : schemaForRender) typeByFieldId.put(f.id(), f.type());
        Map<String, String> textByField = new HashMap<>();
        Map<String, String> sigByField = new HashMap<>();
        for (DocumentInstanceFieldValue v : vals) {
            if (v.getSignatureDocumentId() != null) {
                byte[] png = vault.readDocumentBytesNoAuth(v.getSignatureDocumentId());
                sigByField.put(v.getFieldId(),
                        "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            } else if (v.getValueText() != null) {
                String raw = v.getValueText();
                String type = typeByFieldId.get(v.getFieldId());
                String rendered = "date".equalsIgnoreCase(type)
                        ? IdmsDateFormat.formatIsoDateString(raw) : raw;
                textByField.put(v.getFieldId(), rendered);
            }
        }
        byte[] pdfBytes = pdfRenderer.renderToPdf(
                instance.getSnapshotCanonicalHtml(),
                textByField, sigByField,
                instance.getTemplateTitle());

        Document pdfDoc = vault.saveDocument(
                instance.getInternUserId(),
                instance.getTemplateTitle() + " (executed).pdf",
                "application/pdf",
                pdfBytes,
                CATEGORY_INSTANCE_PDF,
                DocumentVaultService.defaultSensitivityForCategory(CATEGORY_INSTANCE_PDF),
                caller.getId());

        Instant now = Instant.now();
        instance.setStatus(DocumentInstanceStatus.FINALIZED);
        instance.setFinalizedAt(now);
        instance.setFinalPdfDocumentId(pdfDoc.getId());
        instanceRepo.save(instance);

        // Supersede: if this instance was created with a supersedesId, mark
        // that prior FINALIZED doc SUPERSEDED. Same-template match is
        // enforced defensively — a UI regression sending the wrong prior
        // id mustn't retire an unrelated document (e.g. finalizing an
        // Offer Letter must never mark a prior NDA superseded).
        if (instance.getSupersedesId() != null) {
            instanceRepo.findByIdForUpdate(instance.getSupersedesId()).ifPresent(prior -> {
                if (!java.util.Objects.equals(prior.getTemplateKey(), instance.getTemplateKey())) {
                    log.warn("[IDMS] supersede target {} template mismatch "
                                    + "(prior key={}, this key={}) — refusing to mark superseded",
                            prior.getId(), prior.getTemplateKey(), instance.getTemplateKey());
                    return;
                }
                if (prior.getStatus() == DocumentInstanceStatus.FINALIZED) {
                    prior.setStatus(DocumentInstanceStatus.SUPERSEDED);
                    prior.setSupersededAt(Instant.now());
                    instanceRepo.save(prior);
                    writeReview(prior.getId(), "SUPERSEDE", null,
                            "Replaced by " + instance.getTemplateTitle()
                                    + " (v" + instance.getVersion() + ")",
                            caller, "ERM");
                } else {
                    log.info("[IDMS] supersede target {} not FINALIZED (status={}) — no-op",
                            prior.getId(), prior.getStatus());
                }
            });
        }

        writeReview(instance.getId(), "FINALIZE", null, null, caller, "ERM");
        writeAudit("FINALIZE", instance, caller,
                Map.of("finalPdfDocumentId", pdfDoc.getId()));
        notifyIntern(instance,
                "Your document has been executed",
                "\"" + instance.getTemplateTitle() + "\" is complete. Download the executed copy from your Agreements.",
                internDocPath(instance));
        log.info("[IDMS] finalized instance={} pdfDoc={}", instance.getId(), pdfDoc.getId());
        return toDetail(instance, caller);
    }

    @Transactional
    public DocumentInstanceDtos.InstanceDetail revoke(
            UUID instanceId,
            DocumentInstanceDtos.RevokeRequest req,
            User caller) {
        requireErmOrAdmin(caller);
        if (req == null || req.reasonCode() == null || req.reasonCode().isBlank()) {
            throw new BadRequestException("reasonCode required");
        }
        DocumentInstance instance = instanceRepo.findByIdForUpdate(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        // Idempotency: already REVOKED → no-op. Prevents duplicate
        // notify to the intern on double-click of the danger button.
        if (instance.getStatus() == DocumentInstanceStatus.REVOKED) {
            return toDetail(instance, caller);
        }
        if (!DocumentInstanceStatus.REVOCABLE.contains(instance.getStatus())) {
            throw new ConflictException(
                    "This document isn't in a state that can be revoked (state="
                            + instance.getStatus() + ").");
        }
        InternLifecycle lc = lifecycleRepo.findById(instance.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Intern lifecycle missing: " + instance.getInternLifecycleId()));
        RevocationGate gate = canRevoke(lc);
        if (!gate.allowed()) {
            throw new ConflictException(gate.reason());
        }
        assertNotStaleForTransition(instance, req.expectedUpdatedAt());
        Instant now = Instant.now();
        instance.setStatus(DocumentInstanceStatus.REVOKED);
        instance.setRevokedAt(now);
        instance.setRevokedById(caller.getId());
        instance.setRevokeReasonCode(req.reasonCode());
        instance.setRevokeComments(req.comments());
        instanceRepo.save(instance);
        writeReview(instance.getId(), "REVOKE", req.reasonCode(), req.comments(),
                caller, "ERM");
        writeAudit("REVOKE", instance, caller,
                Map.of("reasonCode", req.reasonCode(),
                        "comments", req.comments() == null ? "" : req.comments()));
        notifyIntern(instance,
                "Your document has been revoked",
                "\"" + instance.getTemplateTitle()
                        + "\" has been withdrawn. Please reach out to your ERM if you have questions.",
                internDocPath(instance));
        log.info("[IDMS] revoked instance={} by={} reason={}",
                instance.getId(), caller.getId(), req.reasonCode());
        return toDetail(instance, caller);
    }

    // ── Queue / list ─────────────────────────────────────────────────

    /**
     * Every instance in a state the ERM cockpit's queue cares about,
     * ordered by most recent activity. Terminal + soft-hidden states are
     * still returned so History can render them.
     */
    @Transactional(readOnly = true)
    public List<DocumentInstance> listForCockpit() {
        return instanceRepo.findByStatusIn(List.of(
                DocumentInstanceStatus.DRAFT,
                DocumentInstanceStatus.SENT_TO_INTERN,
                DocumentInstanceStatus.INTERN_SUBMITTED,
                DocumentInstanceStatus.RETURNED,
                DocumentInstanceStatus.VERIFIED,
                DocumentInstanceStatus.FINALIZED,
                DocumentInstanceStatus.REVOKED,
                DocumentInstanceStatus.SUPERSEDED));
    }

    @Transactional(readOnly = true)
    public List<DocumentInstance> listForLifecycle(UUID lifecycleId) {
        return instanceRepo.findByInternLifecycleIdOrderByCreatedAtDesc(lifecycleId);
    }

    @Transactional(readOnly = true)
    public List<DocumentInstance> listForIntern(UUID userId) {
        return instanceRepo.findVisibleForIntern(userId);
    }

    // ── Helpers: lifecycle find-or-create ────────────────────────────

    /**
     * IDMS send-fix — resolve the {@link InternLifecycle} for a new
     * {@link DocumentInstance}. Two paths:
     * <ul>
     *   <li><b>Explicit lifecycle id</b> (the pre-fix path — byte-identical
     *       behavior): fetch by id, 404 if absent. Covers re-sends,
     *       paid-after-unpaid, ACTIVE interns getting an additional
     *       document.</li>
     *   <li><b>Application id fallback</b>: for interview-completed
     *       candidates the legacy sign path never ran on. Resolves
     *       application → candidate → user, then reuses the user's
     *       existing lifecycle if one already exists (idempotent — this
     *       is what makes "candidate got a doc, ERM revoked, ERM sent a
     *       new one" reuse the same PROSPECTIVE row). Otherwise creates
     *       one inline, mirroring
     *       {@code OfferIdmsSigningService.finalizeIdmsSigning}:
     *         mint employeeId (via {@link EmployeeIdGenerator});
     *         existsByUserId + findByEmployeeId 409 guards (clean copy);
     *         set ermId = caller, activeStatus = "PROSPECTIVE",
     *         hiredAt = now; run
     *         {@link ReportingStructureAutoLinker#apply};
     *         advance {@code users.lifecycle_status} from wherever it is
     *         up to {@code EMPLOYEE_ID_CREATED}; stamp employeeId on the
     *         user; advance the Application to
     *         {@link ApplicationStatus#ACCEPTED} for parity with the
     *         legacy sign path; write an {@code IDMS_HIRE_INITIATED}
     *         audit distinguishing this from a legacy sign-time hire.
     *       No engagement row is created — see report §Engagement.
     * </ul>
     * Runs inside the caller's transaction so the instance-create + the
     * lifecycle-create commit as one unit; a failure downstream rolls
     * the freshly-minted lifecycle back too.
     */
    private InternLifecycle resolveOrCreateLifecycle(
            DocumentInstanceDtos.CreateInstanceRequest req, User caller) {
        if (req.internLifecycleId() != null) {
            return lifecycleRepo.findById(req.internLifecycleId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Intern lifecycle not found: " + req.internLifecycleId()));
        }
        // applicationId path
        Application application = applicationRepo.findById(req.applicationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + req.applicationId()));
        Candidate candidate = application.getCandidate();
        if (candidate == null || candidate.getUser() == null) {
            throw new BadRequestException(
                    "This application is missing its candidate or user link — "
                            + "can't create a document for it.");
        }
        User intern = candidate.getUser();

        // Reuse the existing lifecycle if the user already has one. Covers
        // the "revoked + resend to the same candidate" case cleanly — the
        // freshly-created lifecycle from the first send is still PROSPECTIVE
        // and reusable. Also handles the future case of a candidate who
        // already went through legacy sign but somehow only has an
        // applicationId in the request.
        InternLifecycle existing = lifecycleRepo.findByUserId(intern.getId()).orElse(null);
        if (existing != null) return existing;

        // Create fresh — this is the IDMS-initiated hire path.
        Instant now = Instant.now();
        String employeeId = employeeIdGenerator.nextEmployeeId();

        // Guards mirror OfferIdmsSigningService: userId + employeeId uniqueness.
        if (lifecycleRepo.existsByUserId(intern.getId())) {
            throw new ConflictException(
                    "This person already has a hire record — refresh the queue and try again.");
        }
        if (lifecycleRepo.findByEmployeeId(employeeId).isPresent()) {
            throw new ConflictException(
                    "Generated employee id " + employeeId
                            + " collides with an existing hire record.");
        }

        // Advance the user's lifecycle_status walk to EMPLOYEE_ID_CREATED,
        // matching the legacy sign path. advance() is strictly forward-only,
        // so REGISTERED / VERIFIED / SELECTED / OFFER_SIGNED all step up to
        // EMPLOYEE_ID_CREATED cleanly; anything already past that (rare on
        // this path) is a no-op.
        intern.setEmployeeId(employeeId);
        userRepo.save(intern);
        internLifecycleService.advance(intern,
                InternLifecycleStatus.EMPLOYEE_ID_CREATED, caller.getId());

        InternLifecycle lc = InternLifecycle.builder()
                .userId(intern.getId())
                .employeeId(employeeId)
                .ermId(caller.getId())
                .activeStatus("PROSPECTIVE")
                .hiredAt(now)
                .build();
        // Auto-link the org-wide default Trainer + Evaluator — same call
        // the legacy path makes. Manager stays null and is assigned inline
        // from the New Hire detail page.
        reportingStructureAutoLinker.apply(lc, caller.getId(), now);
        lc = lifecycleRepo.save(lc);

        // Parity with legacy sign path — the application transitions to
        // ACCEPTED once we've committed to hiring the candidate.
        if (application.getStatus() != ApplicationStatus.ACCEPTED) {
            application.setStatus(ApplicationStatus.ACCEPTED);
            application.setStatusUpdatedAt(now);
            application.setStatusUpdatedBy(caller.getId());
            applicationRepo.save(application);
        }

        // Audit — distinguishes IDMS-initiated hire from the legacy
        // offer-sign path so the ops surface can tell which one created
        // the lifecycle.
        try {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("userId", intern.getId());
            after.put("employeeId", employeeId);
            after.put("lifecycleId", lc.getId());
            after.put("applicationId", application.getId());
            after.put("origin", "IDMS_SEND");
            AuditLog entry = AuditLog.builder()
                    .entityType("InternLifecycle")
                    .entityId(lc.getId())
                    .action("IDMS_HIRE_INITIATED")
                    .userId(caller.getId())
                    .subjectUserId(intern.getId())
                    .afterJson(objectMapper.writeValueAsString(after))
                    .build();
            auditLogRepo.save(entry);
        } catch (Exception e) {
            log.warn("[IDMS] IDMS_HIRE_INITIATED audit write failed (non-fatal): {}",
                    e.getMessage());
        }
        log.info("[IDMS] initiated hire from IDMS send: user={} employeeId={} lifecycle={} application={}",
                intern.getId(), employeeId, lc.getId(), application.getId());
        return lc;
    }

    // ── Helpers: shared write path + version check ───────────────────

    /**
     * Persist a batch of text/date/content_block values on the instance,
     * enforcing per-field owner (ERM writes ERM fields; INTERN writes
     * INTERN fields; AUTO fields refused). Shared between {@link #fillFields},
     * {@link #send} (when values ride on the send payload), and
     * {@link #internSubmit} (same). Signature fields are rejected here —
     * they go through {@link #signField}.
     */
    private void applyFieldValues(
            DocumentInstance instance,
            Map<String, String> values,
            String callerRole,
            User caller) {
        List<FieldSchemaEntry> schema = parseSchema(instance.getSnapshotFieldSchemaJson());
        Map<String, FieldSchemaEntry> byId = schema.stream()
                .collect(java.util.stream.Collectors.toMap(FieldSchemaEntry::id, f -> f));
        // Field-level correction lock — when the instance carries an
        // unlocked-field-ids set AND the caller is the intern, ONLY
        // those field ids are writable. Every other write 409s so a
        // client bug / tampered client can't sneak edits past the UI
        // disable. NULL set = legacy behaviour (no narrowing); ERM
        // writes bypass this narrowing (their edits during
        // INTERN_SUBMITTED aren't in the return-loop scope).
        java.util.Set<String> lockNarrowing = null;
        if ("INTERN".equals(callerRole)) {
            List<String> unlocked = parseUnlockedFieldIds(instance);
            if (unlocked != null) {
                lockNarrowing = new java.util.HashSet<>(unlocked);
            }
        }
        Instant now = Instant.now();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String fieldId = e.getKey();
            FieldSchemaEntry f = byId.get(fieldId);
            if (f == null) {
                throw new BadRequestException("Unknown field id: " + fieldId);
            }
            String type = f.type() == null ? "TEXT" : f.type().toUpperCase(Locale.ROOT);
            if ("SIGNATURE".equals(type)) {
                throw new BadRequestException("Signature fields go through /sign, not /fill.");
            }
            requireFieldOwner(callerRole, f.assignee(), f.name());
            String value = e.getValue();
            DocumentInstanceFieldValue existing = valueRepo
                    .findByInstanceIdAndFieldId(instance.getId(), fieldId)
                    .orElse(null);
            if (!isFieldEditableUnderLock(fieldId, callerRole,
                    lockNarrowing == null ? null : new java.util.ArrayList<>(lockNarrowing))) {
                if (isBenignNoopUnderLock(
                        existing == null ? null : existing.getValueText(), value)) {
                    continue;
                }
                throw new ConflictException(
                        "Field \"" + f.name() + "\" is locked on this correction "
                                + "cycle — ERM didn't request a change here. Only the "
                                + "fields ERM flagged in the return dialog are editable.");
            }
            if (existing == null) {
                existing = DocumentInstanceFieldValue.builder()
                        .instanceId(instance.getId())
                        .fieldId(fieldId)
                        .fieldName(f.name())
                        .valueText(value)
                        .filledByUserId(caller.getId())
                        .filledByRole(callerRole)
                        .filledAt(now)
                        .build();
            } else {
                existing.setValueText(value);
                existing.setFieldName(f.name());
                existing.setFilledByUserId(caller.getId());
                existing.setFilledByRole(callerRole);
                existing.setFilledAt(now);
            }
            valueRepo.save(existing);
        }
    }

    /**
     * Optimistic lock — reject when the caller's cached {@code updatedAt}
     * is more than {@link #STALE_WRITE_GRACE_MS} milliseconds behind the
     * server's current value. The grace covers the caller's own
     * concurrent /sign call (which bumps updatedAt) between renders;
     * genuine cross-tab conflicts are always well beyond that window.
     * Optional per request: null {@code expectedUpdatedAt} skips the
     * check (legacy caller, or the endpoint doesn't need it).
     */
    private void assertNotStaleForWrite(
            DocumentInstance instance, Long expectedUpdatedAtMs) {
        if (expectedUpdatedAtMs == null) return;
        long serverMs = instance.getUpdatedAt() == null
                ? 0L : instance.getUpdatedAt().toEpochMilli();
        if (serverMs - expectedUpdatedAtMs > STALE_WRITE_GRACE_MS) {
            throw new ConflictException(
                    "This document changed elsewhere — refresh to see the "
                            + "latest state and try again.");
        }
    }

    /** Stricter variant for state transitions (send / verify / finalize /
     *  return / revoke). Same rule, same grace — but transitions carry a
     *  cleaner intent so we prefer to 409 rather than silently overwrite. */
    private void assertNotStaleForTransition(
            DocumentInstance instance, Long expectedUpdatedAtMs) {
        assertNotStaleForWrite(instance, expectedUpdatedAtMs);
    }

    private static final long STALE_WRITE_GRACE_MS = 5_000L;

    // ── Helpers: guards ──────────────────────────────────────────────

    private void requireErmOrAdmin(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (caller.getRoles() == null
                || (!caller.getRoles().contains(UserRole.ERM)
                        && !caller.getRoles().contains(UserRole.SUPER_ADMIN))) {
            throw new ForbiddenException("ERM or SUPER_ADMIN required.");
        }
    }

    private void requireCanRead(DocumentInstance instance, User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        boolean isErm = caller.getRoles() != null && (
                caller.getRoles().contains(UserRole.ERM)
                        || caller.getRoles().contains(UserRole.SUPER_ADMIN));
        boolean isOwningIntern = caller.getId().equals(instance.getInternUserId());
        if (!isErm && !isOwningIntern) {
            throw new ForbiddenException("You don't have access to this document.");
        }
    }

    /** Returns caller role for the fill/sign path — either ERM or INTERN. */
    private String requireCanFill(DocumentInstance instance, User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        boolean isErm = caller.getRoles() != null && (
                caller.getRoles().contains(UserRole.ERM)
                        || caller.getRoles().contains(UserRole.SUPER_ADMIN));
        boolean isOwningIntern = caller.getId().equals(instance.getInternUserId());
        if (!isErm && !isOwningIntern) {
            throw new ForbiddenException("You don't have access to this document.");
        }
        if (isErm) {
            if (instance.getStatus() != DocumentInstanceStatus.DRAFT) {
                throw new ConflictException(
                        "This document isn't editable anymore. Send a new version to make changes.");
            }
            return "ERM";
        }
        // INTERN path
        if (instance.getStatus() != DocumentInstanceStatus.SENT_TO_INTERN
                && instance.getStatus() != DocumentInstanceStatus.RETURNED) {
            throw new ConflictException(
                    "This document isn't awaiting your response right now.");
        }
        return "INTERN";
    }

    private void requireFieldOwner(String callerRole, String assignee, String fieldName) {
        String a = assignee == null ? "" : assignee.toUpperCase(Locale.ROOT);
        if ("AUTO".equals(a)) {
            throw new ForbiddenException(
                    "\"" + fieldName + "\" is filled automatically and can't be edited.");
        }
        if (!callerRole.equals(a)) {
            throw new ForbiddenException(
                    "You can't edit \"" + fieldName + "\" — it belongs to a different party.");
        }
    }

    private void assertRequiredFieldsCompleteFor(DocumentInstance instance, String role) {
        List<FieldSchemaEntry> schema = parseSchema(instance.getSnapshotFieldSchemaJson());
        Map<String, DocumentInstanceFieldValue> valuesById =
                valueRepo.findByInstanceId(instance.getId()).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                DocumentInstanceFieldValue::getFieldId, v -> v));
        List<String> missing = new ArrayList<>();
        for (FieldSchemaEntry f : schema) {
            if (!f.required()) continue;
            String assignee = f.assignee() == null ? "" : f.assignee().toUpperCase(Locale.ROOT);
            if (!role.equals(assignee)) continue;
            DocumentInstanceFieldValue val = valuesById.get(f.id());
            String type = f.type() == null ? "TEXT" : f.type().toUpperCase(Locale.ROOT);
            boolean populated;
            if ("SIGNATURE".equals(type)) {
                populated = val != null && val.getSignatureDocumentId() != null;
            } else {
                populated = val != null && val.getValueText() != null && !val.getValueText().isBlank();
            }
            if (!populated) missing.add(f.name());
        }
        if (!missing.isEmpty()) {
            throw new BadRequestException(
                    "Please complete these fields first: " + String.join(", ", missing));
        }
    }

    // ── Revocation gate ──────────────────────────────────────────────

    /** Public record so the DTO builder can render the reason as UI copy. */
    public record RevocationGate(boolean allowed, String reason) {}

    /**
     * Revocation allowed iff:
     *  - lifecycle NOT yet ACTIVE ({@code startedAt IS NULL}), AND
     *  - (joiningDate + tentativeStartDate both null, OR today &lt;= earliest set).
     * After start: 409 with clean copy.
     *
     * <p>Package-private so {@link ErmIdmsController#queue} can drive the
     * cockpit list's {@code canRevoke} chip off the exact same gate as
     * the detail page's {@code canErmRevoke} action — one source of truth,
     * no chance of the list saying "revocable" while the detail says
     * "start date has passed" (or vice versa).</p>
     */
    RevocationGate canRevoke(InternLifecycle lc) {
        if (lc.getStartedAt() != null) {
            return new RevocationGate(false,
                    "This person has already started — revocation isn't available.");
        }
        LocalDate today = LocalDate.now();
        LocalDate earliest = null;
        if (lc.getJoiningDate() != null) earliest = lc.getJoiningDate();
        if (lc.getTentativeStartDate() != null) {
            if (earliest == null || lc.getTentativeStartDate().isBefore(earliest)) {
                earliest = lc.getTentativeStartDate();
            }
        }
        if (earliest != null && today.isAfter(earliest)) {
            return new RevocationGate(false,
                    "This person's start date has passed — revocation isn't available.");
        }
        return new RevocationGate(true, null);
    }

    // ── AUTO field resolution ────────────────────────────────────────

    private String resolveAutoBinding(String source, User intern, InternLifecycle lc) {
        if (source == null || source.isBlank()) return null;
        switch (source.trim()) {
            case "intern.fullName": return intern.getFullName();
            case "intern.email":    return intern.getEmail();
            case "intern.employeeId":
                return lc == null ? null : lc.getEmployeeId();
            case "intern.legalName": {
                Candidate c = candidateRepo.findByUserId(intern.getId()).orElse(null);
                return c != null && c.getLegalName() != null
                        ? c.getLegalName() : intern.getFullName();
            }
            case "intern.position": return null; // no canonical field yet
            // Match the frontend formatIsoDateMdy contract — document-
            // interpolated dates render as MM/DD/YYYY in both preview + PDF.
            case "today":           return IdmsDateFormat.formatLocalDate(LocalDate.now());
            default:
                return null;
        }
    }

    // ── Detail builder ──────────────────────────────────────────────

    private DocumentInstanceDtos.InstanceDetail toDetail(
            DocumentInstance instance, User caller) {
        User intern = userRepo.findById(instance.getInternUserId()).orElse(null);
        Map<String, DocumentInstanceDtos.FieldValue> values = new LinkedHashMap<>();
        List<FieldSchemaEntry> schema = parseSchema(instance.getSnapshotFieldSchemaJson());
        Map<String, FieldSchemaEntry> byId = schema.stream()
                .collect(java.util.stream.Collectors.toMap(FieldSchemaEntry::id, f -> f));
        for (DocumentInstanceFieldValue v : valueRepo.findByInstanceId(instance.getId())) {
            FieldSchemaEntry f = byId.get(v.getFieldId());
            String sigUrl = null;
            if (v.getSignatureDocumentId() != null) {
                // Route signatures through our own authenticated endpoint
                // rather than an S3 presigned URL. Signatures are PII-
                // encrypted at rest, so a direct S3 fetch returns the
                // encrypted envelope (a durable broken image). Our
                // endpoint decrypts + streams the PNG with owner/staff
                // gating. The {@code v=<documentId>} query param is a
                // cache-buster tied to the underlying signature — swaps
                // cleanly to a new blob URL on re-sign.
                sigUrl = "/api/v1/idms/documents/" + instance.getId()
                        + "/signatures/" + v.getFieldId()
                        + "?v=" + v.getSignatureDocumentId();
            }
            values.put(v.getFieldId(), new DocumentInstanceDtos.FieldValue(
                    v.getFieldId(),
                    v.getFieldName(),
                    f == null ? "text" : f.type(),
                    f == null ? "ERM" : f.assignee(),
                    v.getValueText(),
                    sigUrl,
                    v.getFilledByRole(),
                    v.getFilledAt()));
        }

        // Point at our authenticated + decrypting endpoint (NOT a raw S3
        // presign). The vault stores EDITABLE_DOC_INSTANCE_PDF rows as
        // PII-encrypted AES envelopes under a .bin storage key, so a
        // plain presigned URL returns the ciphertext + wrong headers.
        // /final-pdf reads via DocumentVaultService.readDocument (which
        // decrypts) and emits Content-Type: application/pdf +
        // Content-Disposition: attachment; filename="<template> - <intern>.pdf".
        // Frontend must fetch via axios (blob) so the Bearer JWT rides
        // on the request.
        String finalPdfUrl = null;
        if (instance.getFinalPdfDocumentId() != null) {
            Document pdf = documentRepo.findById(instance.getFinalPdfDocumentId()).orElse(null);
            if (pdf != null && pdf.getDeletedAt() == null) {
                // Owner-intern gets the intern route; ERM/SUPER_ADMIN
                // gets the ERM route. Either endpoint accepts the other
                // caller too (both go through readFinalPdfBytes which
                // does owner-OR-staff), so a misroute is harmless — this
                // just keeps the URL role-shaped for readability.
                boolean isOwner = caller != null
                        && caller.getId() != null
                        && caller.getId().equals(instance.getInternUserId());
                finalPdfUrl = isOwner
                        ? "/api/v1/intern/agreements/" + instance.getId() + "/final-pdf"
                        : "/api/v1/erm/idms/" + instance.getId() + "/final-pdf";
            }
        }

        List<DocumentInstanceDtos.ReviewLogEntry> history = new ArrayList<>();
        for (DocumentInstanceReviewLog r :
                reviewLogRepo.findByInstanceIdOrderByCreatedAtAsc(instance.getId())) {
            User actor = userRepo.findById(r.getActorUserId()).orElse(null);
            history.add(new DocumentInstanceDtos.ReviewLogEntry(
                    r.getAction(),
                    r.getReasonCode(),
                    r.getComments(),
                    r.getActorUserId(),
                    actor != null ? actor.getFullName() : null,
                    r.getActorRole(),
                    r.getCreatedAt()));
        }

        InternLifecycle lc = lifecycleRepo.findById(instance.getInternLifecycleId()).orElse(null);
        RevocationGate revocationGate = lc != null
                ? canRevoke(lc) : new RevocationGate(false, "Missing lifecycle.");
        DocumentInstanceDtos.InstanceActions actions = buildActions(instance, caller, revocationGate);

        return new DocumentInstanceDtos.InstanceDetail(
                instance.getId(),
                instance.getTemplateId(),
                instance.getTemplateKey(),
                instance.getTemplateTitle(),
                instance.getInternLifecycleId(),
                instance.getInternUserId(),
                intern != null ? intern.getFullName() : null,
                intern != null ? intern.getEmail() : null,
                instance.getStatus().name(),
                instance.getVersion(),
                Boolean.TRUE.equals(instance.getInternLocked()),
                instance.getSnapshotCanonicalHtml(),
                instance.getSnapshotFieldSchemaJson(),
                values,
                finalPdfUrl,
                instance.getReturnReasonCode(),
                instance.getReturnComments(),
                parseUnlockedFieldIds(instance),
                instance.getRevokeReasonCode(),
                instance.getRevokeComments(),
                instance.getSupersedesId(),
                instance.getSentAt(),
                instance.getInternSubmittedAt(),
                instance.getReturnedAt(),
                instance.getVerifiedAt(),
                instance.getFinalizedAt(),
                instance.getRevokedAt(),
                instance.getCreatedAt(),
                instance.getUpdatedAt(),
                history,
                actions);
    }

    private DocumentInstanceDtos.InstanceActions buildActions(
            DocumentInstance instance, User caller, RevocationGate gate) {
        boolean isErm = caller != null && caller.getRoles() != null && (
                caller.getRoles().contains(UserRole.ERM)
                        || caller.getRoles().contains(UserRole.SUPER_ADMIN));
        boolean isIntern = caller != null && caller.getId().equals(instance.getInternUserId());
        DocumentInstanceStatus s = instance.getStatus();
        boolean canErmFill = isErm && s == DocumentInstanceStatus.DRAFT;
        boolean canErmSend = isErm && s == DocumentInstanceStatus.DRAFT;
        boolean canInternFill = isIntern
                && (s == DocumentInstanceStatus.SENT_TO_INTERN
                        || s == DocumentInstanceStatus.RETURNED);
        boolean canInternSubmit = canInternFill;
        boolean canErmReturn = isErm && s == DocumentInstanceStatus.INTERN_SUBMITTED;
        boolean canErmVerify = isErm && s == DocumentInstanceStatus.INTERN_SUBMITTED
                && instance.getLastErmViewedAt() != null;
        boolean canErmFinalize = isErm && s == DocumentInstanceStatus.VERIFIED;
        boolean canErmRevoke = isErm
                && DocumentInstanceStatus.REVOCABLE.contains(s)
                && gate.allowed();
        return new DocumentInstanceDtos.InstanceActions(
                canErmFill, canErmSend, canInternFill, canInternSubmit,
                canErmReturn, canErmVerify, canErmFinalize, canErmRevoke,
                gate.allowed() ? null : gate.reason());
    }

    // ── Audit + notify + parse helpers ───────────────────────────────

    private void writeReview(UUID instanceId, String action, String reasonCode,
                             String comments, User actor, String actorRole) {
        try {
            DocumentInstanceReviewLog row = DocumentInstanceReviewLog.builder()
                    .instanceId(instanceId)
                    .action(action)
                    .reasonCode(reasonCode)
                    .comments(comments)
                    .actorUserId(actor != null ? actor.getId() : null)
                    .actorRole(actorRole)
                    .build();
            reviewLogRepo.save(row);
        } catch (Exception e) {
            log.warn("[IDMS] review-log write failed: {}", e.getMessage());
        }
    }

    private void writeAudit(String action, DocumentInstance instance, User caller,
                            Object payload) {
        try {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("instanceId", instance.getId());
            after.put("templateKey", instance.getTemplateKey());
            after.put("status", instance.getStatus());
            after.put("payload", payload);
            AuditLog entry = AuditLog.builder()
                    .entityType("DocumentInstance")
                    .entityId(instance.getId())
                    .action(action)
                    .userId(caller != null ? caller.getId() : null)
                    .subjectUserId(instance.getInternUserId())
                    .afterJson(objectMapper.writeValueAsString(after))
                    .build();
            auditLogRepo.save(entry);
        } catch (Exception e) {
            log.warn("[IDMS] audit write failed: {}", e.getMessage());
        }
    }

    private void notifyIntern(DocumentInstance instance,
                              String title, String body, String actionUrl) {
        notifyUser(instance.getInternUserId(),
                "IDMS_DOC_" + instance.getStatus().name(),
                title, body, actionUrl);
    }

    /**
     * Pipeline-restore — resolve the intern-facing deep link for an
     * instance. Offer-family instances land on the offer-letter page (the
     * intern's home for their offer); every other IDMS instance keeps the
     * generic agreements URL so an existing route (with its 301 redirect
     * for backward-compat deep links) still resolves.
     */
    private static String internDocPath(DocumentInstance instance) {
        return isOfferFamily(instance)
                ? "/careers/intern/offer-letter/" + instance.getId()
                : "/careers/intern/agreements/" + instance.getId();
    }

    /** Offer-family templates — any template key that contains
     *  {@code offer_} as a substring, case-INsensitively. Keys were
     *  historically uppercase ({@code OFFER_LETTER},
     *  {@code H1_OFFER_LETTER}) but the frontend + DTO validator now
     *  enforce lowercase snake-case ({@code offer_letter},
     *  {@code h1_offer_letter}); both forms coexist in the DB.
     *  {@code contains("offer_")} on the lowercased key catches every
     *  legitimate offer variant regardless of when the template row was
     *  created.
     *
     *  <p>Kept in sync with the JPQL peer at
     *  {@code DocumentInstanceRepository.findOfferFamilyForIntern}:
     *  {@code LOWER(templateKey) LIKE '%offer\_%'} — same substring
     *  rule under case normalisation.</p> */
    public static boolean isOfferFamily(DocumentInstance instance) {
        return instance != null
                && instance.getTemplateKey() != null
                && instance.getTemplateKey().toLowerCase(java.util.Locale.ROOT)
                        .contains("offer_");
    }

    /**
     * Pipeline-restore — return the intern's current offer-family
     * instance detail (newest non-VOIDED offer-family row), or empty if
     * none exists. Consumed by the intern offer-letter landing page and
     * the intern dashboard's next-action resolver.
     */
    @Transactional(readOnly = true)
    public Optional<DocumentInstanceDtos.InstanceDetail>
            findCurrentOfferLetterForIntern(User caller) {
        if (caller == null) return Optional.empty();
        List<DocumentInstance> offers = instanceRepo.findOfferFamilyForIntern(caller.getId());
        if (offers.isEmpty()) return Optional.empty();
        return Optional.of(toDetail(offers.get(0), caller));
    }

    /** Package-private companion used by DocumentPacketService's onboarding
     *  gate — returns the raw entity so callers can inspect
     *  {@code status} without paying for the full DTO serialisation. */
    @Transactional(readOnly = true)
    public Optional<DocumentInstance> findCurrentOfferFamilyEntityForIntern(UUID internUserId) {
        if (internUserId == null) return Optional.empty();
        List<DocumentInstance> offers = instanceRepo.findOfferFamilyForIntern(internUserId);
        return offers.isEmpty() ? Optional.empty() : Optional.of(offers.get(0));
    }

    /**
     * Return the decrypted final PDF bytes + a display filename for an
     * instance the caller is authorized to read.
     *
     * <p>Auth: same RBAC as {@link #getDetail} + {@code
     * DocumentVaultService.readDocument}. Any owner-intern or ERM /
     * SUPER_ADMIN staff passes; anyone else gets 403. Missing / non-
     * FINALIZED / soft-deleted PDF row → 404.</p>
     *
     * <p>Filename shape: {@code "<template title> - <intern name>.pdf"}
     * with control chars + Windows-illegal set stripped by the shared
     * {@link ContentDispositionFilenames#sanitizeFilename} helper. The
     * caller wraps this in an {@code attachment} Content-Disposition
     * with the RFC-5987 two-header pattern.</p>
     */
    public record FinalPdfPayload(byte[] bytes, String suggestedFilename) {}

    @Transactional
    public FinalPdfPayload readFinalPdfBytes(UUID instanceId, User caller) {
        DocumentInstance instance = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        // Basic access — owner (intern) or ERM/SUPER_ADMIN. The
        // vault.readDocument call below re-enforces owner/staff too;
        // this pre-check produces the cleaner IDMS-shaped 403/404 for
        // clients that don't authenticate.
        boolean owner = caller != null
                && caller.getId().equals(instance.getInternUserId());
        boolean staff = caller != null && caller.getRoles() != null && (
                caller.getRoles().contains(UserRole.ERM)
                        || caller.getRoles().contains(UserRole.SUPER_ADMIN));
        if (!owner && !staff) {
            throw new ForbiddenException(
                    "Only the assigned intern or ERM staff can download this document.");
        }
        if (instance.getStatus() != DocumentInstanceStatus.FINALIZED
                && instance.getStatus() != DocumentInstanceStatus.SUPERSEDED) {
            // Not-yet-executed instances have no PDF. SUPERSEDED
            // preserves the download so an intern can still fetch the
            // executed prior version after a re-issue.
            throw new ResourceNotFoundException(
                    "This document has no executed PDF yet (status "
                            + instance.getStatus() + ").");
        }
        UUID pdfDocId = instance.getFinalPdfDocumentId();
        if (pdfDocId == null) {
            throw new ResourceNotFoundException(
                    "This document's executed PDF is missing (finalize may have failed).");
        }
        // Vault read auto-decrypts the AES envelope when
        // encryption_metadata_json is set. That's the ONLY path that
        // works for EDITABLE_DOC_INSTANCE_PDF — a plain S3 presign
        // would return the ciphertext.
        byte[] plain = vault.readDocument(pdfDocId, caller);

        User intern = userRepo.findById(instance.getInternUserId()).orElse(null);
        String internName = intern != null && intern.getFullName() != null
                ? intern.getFullName() : "Intern";
        String base = (instance.getTemplateTitle() == null
                ? "Document"
                : instance.getTemplateTitle())
                + " - " + internName + ".pdf";
        return new FinalPdfPayload(plain,
                com.anvicorp.api.common.ContentDispositionFilenames.sanitizeFilename(base));
    }

    private void notifyUser(UUID userId, String eventKey, String title,
                            String body, String actionUrl) {
        if (userId == null) return;
        try {
            // emailSent=false lets the dispatcher's own hook attempt a
            // best-effort plaintext email if the eventType is in the
            // allow-list. If not, the in-app notification still lands.
            dispatcher.dispatch(userId, eventKey, userId, title, body, actionUrl, false);
        } catch (Exception e) {
            log.warn("[IDMS] notify {} failed (non-fatal): {}", userId, e.getMessage());
        }
    }

    private List<FieldSchemaEntry> parseSchema(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<FieldSchemaEntry>>() {});
        } catch (Exception e) {
            log.warn("[IDMS] schema parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Read the persisted per-return unlocked-field-ids set.
     *
     * <p>Returns {@code null} when the column is null OR the stored
     * JSON is unparseable (defensive — treats a corrupt blob as "no
     * narrowing" so the intern isn't accidentally locked out of every
     * field). Returns an empty list ONLY when the ERM persisted an
     * explicitly empty array (never today — {@code returnForCorrections}
     * normalises empty → null before saving).</p>
     *
     * <p>The distinction between {@code null} (legacy: every intern
     * field editable) and non-null (narrowed) is load-bearing —
     * callers must preserve it. See {@link #applyFieldValues} and
     * {@code InstanceDetail.unlockedFieldIds} on the wire.</p>
     */
    private List<String> parseUnlockedFieldIds(DocumentInstance instance) {
        String json = instance.getUnlockedFieldIdsJson();
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[IDMS] unlockedFieldIds parse failed on instance {}: {}",
                    instance.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Testable narrowing predicate — package-private + static so the
     * unit test can exercise every branch of the field-level
     * correction lock without spinning a Spring context or mocking
     * repositories. Returns {@code true} when the write should be
     * allowed by the lock (still subject to role + status guards).
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>ERM writes are never narrowed by this lock — the ERM's
     *       fills happen before the correction cycle even starts.</li>
     *   <li>{@code unlockedFieldIds == null} — legacy behaviour, every
     *       intern field editable. Existing rows before this feature
     *       land here (safe backward compat).</li>
     *   <li>Populated list — only listed ids editable; the caller
     *       ({@link #applyFieldValues}) throws {@link ConflictException}
     *       for any other id.</li>
     * </ul>
     */
    static boolean isFieldEditableUnderLock(
            String fieldId, String callerRole, List<String> unlockedFieldIds) {
        if (!"INTERN".equals(callerRole)) return true;
        if (unlockedFieldIds == null) return true;
        return unlockedFieldIds.contains(fieldId);
    }

    /**
     * Distinguishes a benign no-op write on a LOCKED field from an
     * attempted-bypass write.
     *
     * <p>Background: the intern-side autosave used to send EVERY intern-
     * owned field on every debounced save (see
     * {@code IdmsFillSurface.persist}). Even with the client filter now
     * in place, we keep this server-side tolerance as defence in depth —
     * a stale in-flight payload can still ride out after the narrowing
     * became known to the client, and we don't want a raced no-op to
     * 409 the whole save (which reverts the user's actual unlocked-field
     * edit).</p>
     *
     * <p>Rule: if the incoming value equals the stored value, treat the
     * write as a silent no-op. Any DIFFERENT value on a locked field is
     * a real attempted change and must 409 — the security guarantee of
     * the correction lock. Null and empty string are normalised together
     * so a "" that lands on an unset field doesn't false-trip either way.</p>
     */
    static boolean isBenignNoopUnderLock(String storedText, String incomingText) {
        String s = storedText == null ? "" : storedText;
        String i = incomingText == null ? "" : incomingText;
        return s.equals(i);
    }

    private static byte[] decodeDataUrl(String dataUrl) {
        if (dataUrl == null) return new byte[0];
        int comma = dataUrl.indexOf(',');
        String payload = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
        try { return Base64.getDecoder().decode(payload); }
        catch (Exception e) { return new byte[0]; }
    }

    /** Mirror of the frontend {@code FieldEntry} shape saved by the Phase 1
     *  studio. Deserialised out of {@code snapshot_field_schema}. */
    public record FieldSchemaEntry(
            String id,
            String name,
            String type,
            String assignee,
            boolean required,
            String defaultSource
    ) {}

    /** Kept for symmetry — placeholder for a future "AUTO field re-resolve"
     *  action (e.g. today→now() drift). Not called from anywhere yet. */
    @SuppressWarnings("unused")
    private Optional<Void> reserved() { return Optional.empty(); }
}
