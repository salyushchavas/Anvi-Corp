package com.anvicorp.api.erm.idms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.admin.editabletemplates.EditableTemplate;
import com.anvicorp.api.admin.editabletemplates.EditableTemplateRepository;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.s3.S3StorageService;
import com.anvicorp.api.intern.DocumentVaultService;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
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
    private static final Duration FINAL_PDF_URL_TTL = Duration.ofMinutes(30);
    private static final int SIGNATURE_MAX_BYTES = 500 * 1024; // 500KB

    private final DocumentInstanceRepository instanceRepo;
    private final DocumentInstanceFieldValueRepository valueRepo;
    private final DocumentInstanceReviewLogRepository reviewLogRepo;
    private final EditableTemplateRepository templateRepo;
    private final InternLifecycleRepository lifecycleRepo;
    private final UserRepository userRepo;
    private final CandidateRepository candidateRepo;
    private final DocumentRepository documentRepo;
    private final DocumentVaultService vault;
    private final S3StorageService s3;
    private final DocumentInstancePdfRenderer pdfRenderer;
    private final AuditLogRepository auditLogRepo;
    private final ObjectMapper objectMapper;
    private final UserNotificationDispatcher dispatcher;

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
        if (req == null || req.templateId() == null || req.internLifecycleId() == null) {
            throw new BadRequestException("templateId + internLifecycleId required");
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
        InternLifecycle lc = lifecycleRepo.findById(req.internLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Intern lifecycle not found: " + req.internLifecycleId()));
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
        List<FieldSchemaEntry> schema = parseSchema(instance.getSnapshotFieldSchemaJson());
        Map<String, FieldSchemaEntry> byId = schema.stream()
                .collect(java.util.stream.Collectors.toMap(FieldSchemaEntry::id, f -> f));

        Instant now = Instant.now();
        for (Map.Entry<String, String> e : req.values().entrySet()) {
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
    public DocumentInstanceDtos.InstanceDetail send(UUID instanceId, User caller) {
        requireErmOrAdmin(caller);
        DocumentInstance instance = instanceRepo.findByIdForUpdate(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        if (instance.getStatus() != DocumentInstanceStatus.DRAFT) {
            throw new ConflictException(
                    "Only draft documents can be sent. Current state: "
                            + instance.getStatus());
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
                "/careers/intern/agreements/" + instance.getId());
        return toDetail(instance, caller);
    }

    @Transactional
    public DocumentInstanceDtos.InstanceDetail internSubmit(UUID instanceId, User caller) {
        DocumentInstance instance = instanceRepo.findByIdForUpdate(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        if (!caller.getId().equals(instance.getInternUserId())) {
            throw new ForbiddenException("Only the assigned intern can submit this document.");
        }
        if (instance.getStatus() != DocumentInstanceStatus.SENT_TO_INTERN
                && instance.getStatus() != DocumentInstanceStatus.RETURNED) {
            throw new ConflictException(
                    "You can only submit documents that are awaiting your response.");
        }
        assertRequiredFieldsCompleteFor(instance, "INTERN");
        Instant now = Instant.now();
        instance.setStatus(DocumentInstanceStatus.INTERN_SUBMITTED);
        instance.setInternSubmittedAt(now);
        instance.setInternLocked(true); // frozen until ERM verify / return
        // Clear any prior return reason — the fresh submission overwrites.
        instance.setReturnReasonCode(null);
        instance.setReturnComments(null);
        instanceRepo.save(instance);
        writeReview(instance.getId(), "INTERN_SUBMIT", null, null, caller, "INTERN");
        writeAudit("INTERN_SUBMIT", instance, caller, Map.of("status", "INTERN_SUBMITTED"));
        // ERM notification — the owning ERM (createdByErmId).
        notifyUser(instance.getCreatedByErmId(),
                "IDMS_DOC_INTERN_SUBMITTED",
                "Signed document awaiting your verification",
                "\"" + instance.getTemplateTitle() + "\" is ready to verify.",
                "/careers/erm/offers?filter=verifying");
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
        if (instance.getStatus() != DocumentInstanceStatus.INTERN_SUBMITTED) {
            throw new ConflictException(
                    "Only submitted documents can be returned for corrections.");
        }
        Instant now = Instant.now();
        instance.setStatus(DocumentInstanceStatus.RETURNED);
        instance.setReturnedAt(now);
        instance.setReturnReasonCode(req.reasonCode());
        instance.setReturnComments(req.comments());
        instance.setInternLocked(false); // intern can edit their fields again
        instance.setVersion(instance.getVersion() + 1);
        instanceRepo.save(instance);
        writeReview(instance.getId(), "RETURN", req.reasonCode(), req.comments(), caller, "ERM");
        writeAudit("RETURN", instance, caller,
                Map.of("reasonCode", req.reasonCode(),
                        "comments", req.comments() == null ? "" : req.comments()));
        notifyIntern(instance,
                "Please make corrections to your document",
                "Your ERM has asked for changes to \"" + instance.getTemplateTitle() + "\".",
                "/careers/intern/agreements/" + instance.getId());
        return toDetail(instance, caller);
    }

    @Transactional
    public DocumentInstanceDtos.InstanceDetail verify(UUID instanceId, User caller) {
        requireErmOrAdmin(caller);
        DocumentInstance instance = instanceRepo.findByIdForUpdate(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        if (instance.getStatus() != DocumentInstanceStatus.INTERN_SUBMITTED) {
            throw new ConflictException(
                    "Only submitted documents can be verified. Current state: "
                            + instance.getStatus());
        }
        if (instance.getLastErmViewedAt() == null) {
            throw new ConflictException(
                    "Please open and review the completed document before verifying.");
        }
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
    public DocumentInstanceDtos.InstanceDetail finalize(UUID instanceId, User caller) {
        requireErmOrAdmin(caller);
        DocumentInstance instance = instanceRepo.findByIdForUpdate(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document instance not found: " + instanceId));
        if (instance.getStatus() != DocumentInstanceStatus.VERIFIED) {
            throw new ConflictException(
                    "Only verified documents can be finalized. Current state: "
                            + instance.getStatus());
        }

        // Build the text + signature-url maps for the renderer.
        List<DocumentInstanceFieldValue> vals = valueRepo.findByInstanceId(instance.getId());
        Map<String, String> textByField = new HashMap<>();
        Map<String, String> sigByField = new HashMap<>();
        for (DocumentInstanceFieldValue v : vals) {
            if (v.getSignatureDocumentId() != null) {
                byte[] png = vault.readDocumentBytesNoAuth(v.getSignatureDocumentId());
                sigByField.put(v.getFieldId(),
                        "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            } else if (v.getValueText() != null) {
                textByField.put(v.getFieldId(), v.getValueText());
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
        // that prior FINALIZED doc SUPERSEDED. Only meaningful when the
        // predecessor is actually still FINALIZED — a stale reference is a
        // no-op with a log line so the ERM sees why nothing happened.
        if (instance.getSupersedesId() != null) {
            instanceRepo.findByIdForUpdate(instance.getSupersedesId()).ifPresent(prior -> {
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
                "/careers/intern/agreements/" + instance.getId());
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
                "/careers/intern/agreements/" + instance.getId());
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
     */
    private RevocationGate canRevoke(InternLifecycle lc) {
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
            case "today":            return LocalDate.now().toString();
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
            if (v.getSignatureDocumentId() != null && s3.isReady()) {
                try {
                    Document sig = documentRepo.findById(v.getSignatureDocumentId()).orElse(null);
                    if (sig != null && sig.getDeletedAt() == null) {
                        sigUrl = s3.presignGetUrl(sig.getStorageKey(), SIGNATURE_URL_TTL);
                    }
                } catch (Exception ex) {
                    log.debug("[IDMS] signature presign failed for {}: {}",
                            v.getSignatureDocumentId(), ex.getMessage());
                }
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

        String finalPdfUrl = null;
        if (instance.getFinalPdfDocumentId() != null && s3.isReady()) {
            try {
                Document pdf = documentRepo.findById(instance.getFinalPdfDocumentId()).orElse(null);
                if (pdf != null && pdf.getDeletedAt() == null) {
                    finalPdfUrl = s3.presignGetUrl(pdf.getStorageKey(), FINAL_PDF_URL_TTL);
                }
            } catch (Exception ex) {
                log.debug("[IDMS] final PDF presign failed for {}: {}",
                        instance.getFinalPdfDocumentId(), ex.getMessage());
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
