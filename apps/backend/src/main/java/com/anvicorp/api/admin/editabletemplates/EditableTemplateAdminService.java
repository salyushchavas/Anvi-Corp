package com.anvicorp.api.admin.editabletemplates;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.s3.S3StorageService;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Editable Documents Phase 1 — admin CRUD for editable template rows.
 *
 * <p>Sibling of {@link com.anvicorp.api.admin.onboardingtemplates.OnboardingTemplateAdminService}
 * — the presign / attach choreography is forked verbatim so the DOCX source
 * upload behaves identically to the onboarding-template file upload the
 * admin surface already speaks. Additional endpoints on top of the forked
 * set:</p>
 *
 * <ul>
 *   <li>{@link #create}          — mint a shell row (title, key, description)</li>
 *   <li>{@link #presignUpload}   — presign an S3 PUT for the DOCX source file</li>
 *   <li>{@link #attachSourceFile}— HEAD-verify + swap the source_document_id
 *                                  pointer; RESETS canonical_html + field_schema
 *                                  to null (anchor-drift rule, survey §4)</li>
 *   <li>{@link #saveSchema}      — persist canonical HTML + field schema JSON
 *                                  + docx-preview fidelity warnings</li>
 *   <li>{@link #list} / {@link #get} — read paths (get returns a presigned GET
 *                                  URL for the source DOCX so docx-preview
 *                                  can fetch it directly from S3)</li>
 *   <li>{@link #updateMetadata}  — title / description / sortOrder</li>
 *   <li>{@link #softDelete}      — active=false</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EditableTemplateAdminService {

    public static final String CATEGORY_SOURCE = "EDITABLE_TEMPLATE_SOURCE";

    private static final Duration PRESIGN_TTL = Duration.ofMinutes(30);
    private static final Duration DOWNLOAD_TTL = Duration.ofMinutes(30);
    private static final long MAX_SOURCE_BYTES = 25L * 1024 * 1024; // 25 MB — DOCX only
    private static final Set<String> ACCEPTED_MIME = new HashSet<>();
    static {
        ACCEPTED_MIME.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        // Some browsers upload without the vnd.openxml… mime type; accept the
        // generic zip mime too. The .docx extension check gates the client
        // side (see the /new page); the server keeps the mime check loose.
        ACCEPTED_MIME.add("application/octet-stream");
        ACCEPTED_MIME.add("application/zip");
    }

    private final EditableTemplateRepository repo;
    private final DocumentRepository documentRepo;
    private final S3StorageService s3;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    // ── List + get ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public EditableTemplateDtos.TemplateListResponse list() {
        List<EditableTemplate> rows = repo.findAllByOrderBySortOrderAscTitleAsc();
        Set<UUID> docIds = new HashSet<>();
        for (EditableTemplate t : rows) {
            if (t.getSourceDocumentId() != null) docIds.add(t.getSourceDocumentId());
        }
        Map<UUID, Document> docs = new LinkedHashMap<>();
        for (Document d : documentRepo.findAllById(docIds)) {
            if (d.getDeletedAt() == null) docs.put(d.getId(), d);
        }
        List<EditableTemplateDtos.TemplateRow> items = new ArrayList<>(rows.size());
        for (EditableTemplate t : rows) {
            Document doc = t.getSourceDocumentId() != null
                    ? docs.get(t.getSourceDocumentId()) : null;
            items.add(toRow(t, doc, /*includeHeavy*/ false));
        }
        return new EditableTemplateDtos.TemplateListResponse(items);
    }

    @Transactional(readOnly = true)
    public EditableTemplateDtos.TemplateRow get(UUID id) {
        EditableTemplate t = repo.findById(id).orElseThrow(() ->
                new ResourceNotFoundException("Template not found: " + id));
        Document doc = t.getSourceDocumentId() != null
                ? documentRepo.findById(t.getSourceDocumentId()).orElse(null) : null;
        return toRow(t, doc, /*includeHeavy*/ true);
    }

    @Transactional(readOnly = true)
    public EditableTemplateDtos.TemplateRow getByKey(String key) {
        EditableTemplate t = repo.findByKey(key).orElseThrow(() ->
                new ResourceNotFoundException("Template not found: " + key));
        Document doc = t.getSourceDocumentId() != null
                ? documentRepo.findById(t.getSourceDocumentId()).orElse(null) : null;
        return toRow(t, doc, /*includeHeavy*/ true);
    }

    // ── Create + update metadata ─────────────────────────────────────

    @Transactional
    public EditableTemplateDtos.TemplateRow create(
            EditableTemplateDtos.CreateTemplateRequest req, User caller) {
        String title = trimOrNull(req == null ? null : req.title());
        if (title == null) throw new BadRequestException("title required");
        String key = trimOrNull(req.key());
        if (key == null) key = deriveKey(title);
        if (repo.existsByKey(key)) {
            throw new BadRequestException("A template already exists with key='" + key + "'");
        }
        EditableTemplate t = EditableTemplate.builder()
                .key(key)
                .title(title)
                .description(trimOrNull(req.description()))
                .active(true)
                .sortOrder(500)
                .createdById(caller.getId())
                .build();
        t = repo.save(t);
        writeAudit("CREATE", t, caller, Map.of("key", t.getKey(), "title", t.getTitle()));
        log.info("[EditableTemplateAdmin] created id={} key={} title={}",
                t.getId(), t.getKey(), t.getTitle());
        return toRow(t, null, /*includeHeavy*/ true);
    }

    @Transactional
    public EditableTemplateDtos.TemplateRow updateMetadata(
            UUID id, EditableTemplateDtos.UpdateTemplateMetadataRequest req, User caller) {
        EditableTemplate t = repo.findById(id).orElseThrow(() ->
                new ResourceNotFoundException("Template not found: " + id));
        Map<String, Object> before = snapshot(t);
        if (req.title() != null && !req.title().isBlank()) t.setTitle(req.title().trim());
        if (req.description() != null) t.setDescription(trimOrNull(req.description()));
        if (req.sortOrder() != null) t.setSortOrder(req.sortOrder());
        repo.save(t);
        writeAudit("UPDATE", t, caller, Map.of("before", before, "after", snapshot(t)));
        Document doc = t.getSourceDocumentId() != null
                ? documentRepo.findById(t.getSourceDocumentId()).orElse(null) : null;
        return toRow(t, doc, /*includeHeavy*/ true);
    }

    @Transactional
    public void softDelete(UUID id, User caller) {
        EditableTemplate t = repo.findById(id).orElseThrow(() ->
                new ResourceNotFoundException("Template not found: " + id));
        t.setActive(false);
        repo.save(t);
        writeAudit("DEACTIVATE", t, caller, Map.of("key", t.getKey()));
        log.info("[EditableTemplateAdmin] soft-deleted id={} key={}", t.getId(), t.getKey());
    }

    // ── Presign + attach (source DOCX) ───────────────────────────────

    @Transactional
    public EditableTemplateDtos.PresignUploadResponse presignUpload(
            UUID id,
            EditableTemplateDtos.PresignUploadRequest req,
            User caller) {
        EditableTemplate t = repo.findById(id).orElseThrow(() ->
                new ResourceNotFoundException("Template not found: " + id));
        String fileName = req == null || req.fileName() == null ? "" : req.fileName().trim();
        String contentType = req == null || req.contentType() == null
                ? "" : req.contentType().trim().toLowerCase();
        Long fileSize = req == null ? null : req.fileSize();
        if (fileName.isEmpty()) throw new BadRequestException("fileName required");
        if (!fileName.toLowerCase().endsWith(".docx")) {
            throw new BadRequestException("Editable-template source must be a .docx file.");
        }
        if (contentType.isEmpty()) throw new BadRequestException("contentType required");
        if (fileSize == null || fileSize <= 0L) {
            throw new BadRequestException("fileSize must be positive");
        }
        if (fileSize > MAX_SOURCE_BYTES) {
            throw new BadRequestException("Template file too large (max 25 MB).");
        }
        if (!s3.isReady()) {
            throw new BadRequestException("S3 is not configured on this environment.");
        }
        String storageKey = s3.key(
                "editable-templates",
                t.getKey(),
                UUID.randomUUID() + "-" + safeName(fileName));
        Document doc = Document.builder()
                .ownerUserId(caller.getId())
                .uploadedById(caller.getId())
                .fileName(fileName)
                .fileSize(fileSize)
                .mimeType(contentType)
                .storageKey(storageKey)
                .category(CATEGORY_SOURCE)
                .sensitivity("NORMAL")
                .build();
        doc = documentRepo.save(doc);
        String uploadUrl = s3.presignPutUrl(storageKey, contentType, PRESIGN_TTL);
        return new EditableTemplateDtos.PresignUploadResponse(
                uploadUrl, doc.getId(), storageKey, Instant.now().plus(PRESIGN_TTL));
    }

    /**
     * Swap the template's {@code source_document_id} to the newly uploaded
     * Document row after HEAD-verifying the bytes actually landed in S3.
     *
     * <p><b>Anchor-drift rule (survey §4):</b> when a NEW DOCX version
     * is attached (any attach after the first one), both
     * {@code canonical_html} and {@code field_schema} are reset to
     * {@code null} so the admin re-selects fields on the fresh render.
     * The docx-preview conversion may drift the DOM structure between
     * source versions, and silent auto-migration would leave orphaned
     * {@code data-field-id} spans anchored to the wrong (or missing)
     * ranges. The fidelity warnings JSONB is also reset — the new save
     * will re-populate it.</p>
     */
    @Transactional
    public EditableTemplateDtos.TemplateRow attachSourceFile(
            UUID id, EditableTemplateDtos.AttachSourceRequest req, User caller) {
        if (req == null || req.documentId() == null) {
            throw new BadRequestException("documentId required");
        }
        EditableTemplate t = repo.findById(id).orElseThrow(() ->
                new ResourceNotFoundException("Template not found: " + id));
        Document doc = documentRepo.findById(req.documentId()).orElseThrow(() ->
                new ResourceNotFoundException("Document not found: " + req.documentId()));
        if (!CATEGORY_SOURCE.equals(doc.getCategory())) {
            throw new BadRequestException(
                    "Document is not an editable-template source file "
                            + "(category=" + doc.getCategory() + ")");
        }
        if (!s3.isReady()) {
            throw new BadRequestException("S3 is not configured on this environment.");
        }
        if (!s3.exists(doc.getStorageKey())) {
            throw new BadRequestException(
                    "Uploaded file is not present in storage yet — the direct-to-S3 "
                            + "upload may have failed. Try uploading again.");
        }
        // Security Wave 2 — download the bytes from S3 and validate:
        // (1) magic-byte check confirms it's a real OOXML zip, not e.g.
        //     an HTML page renamed .docx (presign PUT accepts any bytes);
        // (2) zip-bomb guard walks the archive and caps entry count /
        //     ratio / total uncompressed size so a malicious template
        //     can't OOM docx-preview or the extract pipeline.
        try {
            byte[] docxBytes = s3.getObject(doc.getStorageKey());
            com.anvicorp.api.security.FileContentValidator.requireOneOf(
                    docxBytes,
                    com.anvicorp.api.security.FileContentValidator.DOCX_ONLY,
                    "editable template DOCX");
            com.anvicorp.api.security.DocxSafetyValidator.assertSafe(
                    docxBytes, "editable template DOCX");
        } catch (com.anvicorp.api.exception.BadRequestException validationErr) {
            // Best-effort delete of the just-uploaded S3 object so a rejected
            // upload doesn't linger in storage.
            try { s3.deleteObject(doc.getStorageKey()); }
            catch (Exception ignore) { /* leak-safe: audit trail wins over vault cleanliness */ }
            throw validationErr;
        }
        UUID previous = t.getSourceDocumentId();
        boolean isReupload = previous != null;
        t.setSourceDocumentId(doc.getId());
        if (isReupload) {
            // Anchor-drift rule — clear the schema so the admin re-selects
            // fields on the fresh render.
            t.setCanonicalHtml(null);
            t.setFieldSchemaJson(null);
            t.setFidelityWarningsJson(null);
        }
        repo.save(t);
        // Soft-delete the prior source so the vault list stays lean; bytes
        // remain in S3 for audit.
        if (previous != null) {
            documentRepo.findById(previous).ifPresent(prior -> {
                if (prior.getDeletedAt() == null) {
                    prior.setDeletedAt(Instant.now());
                    documentRepo.save(prior);
                }
            });
        }
        String action = isReupload ? "SCHEMA_RESET" : "SOURCE_ATTACHED";
        writeAudit(action, t, caller,
                Map.of("newDocumentId", doc.getId(),
                        "previousDocumentId", previous == null ? "" : previous.toString(),
                        "clearedSchema", isReupload));
        log.info("[EditableTemplateAdmin] attachSourceFile template={} newDoc={} "
                        + "previousDoc={} clearedSchema={}",
                id, doc.getId(), previous, isReupload);
        return toRow(t, doc, /*includeHeavy*/ true);
    }

    // ── Save canonical HTML + field schema ───────────────────────────

    @Transactional
    public EditableTemplateDtos.TemplateRow saveSchema(
            UUID id, EditableTemplateDtos.SaveSchemaRequest req, User caller) {
        if (req == null) throw new BadRequestException("request body required");
        EditableTemplate t = repo.findById(id).orElseThrow(() ->
                new ResourceNotFoundException("Template not found: " + id));
        if (t.getSourceDocumentId() == null) {
            throw new BadRequestException(
                    "Upload the source .docx before saving fields.");
        }
        String html = req.canonicalHtml();
        if (html == null || html.isBlank()) {
            throw new BadRequestException("canonicalHtml required");
        }
        List<EditableTemplateDtos.FieldEntry> fields = req.fields() == null
                ? List.of() : req.fields();
        if (fields.isEmpty()) {
            throw new BadRequestException("At least one field is required to save.");
        }
        Set<String> seenNames = new HashSet<>();
        Set<String> seenIds = new HashSet<>();
        for (EditableTemplateDtos.FieldEntry f : fields) {
            if (f == null) throw new BadRequestException("field entries must be non-null");
            if (f.id() == null || f.id().isBlank()) {
                throw new BadRequestException("Every field needs an id");
            }
            if (f.name() == null || f.name().isBlank()) {
                throw new BadRequestException("Every field needs a name");
            }
            if (!seenIds.add(f.id())) {
                throw new BadRequestException("Duplicate field id: " + f.id());
            }
            if (!seenNames.add(f.name().trim().toLowerCase())) {
                throw new BadRequestException("Duplicate field name: " + f.name());
            }
            String type = f.type() == null ? "" : f.type().trim().toUpperCase();
            if (!List.of("TEXT", "DATE", "SIGNATURE", "CONTENT_BLOCK").contains(type)) {
                throw new BadRequestException(
                        "field.type must be one of text | date | signature | content_block");
            }
            String assignee = f.assignee() == null ? "" : f.assignee().trim().toUpperCase();
            if (!List.of("ERM", "INTERN", "AUTO").contains(assignee)) {
                throw new BadRequestException(
                        "field.assignee must be one of ERM | INTERN | AUTO");
            }
        }

        String fieldsJson;
        String warningsJson;
        try {
            fieldsJson = objectMapper.writeValueAsString(fields);
            warningsJson = req.fidelityWarnings() == null
                    ? null
                    : objectMapper.writeValueAsString(req.fidelityWarnings());
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Could not serialise schema JSON: " + e.getMessage());
        }
        // Normalise the studio's serialised HTML to well-formed XHTML
        // before persisting. The docx-preview canvas emits browser-
        // tolerant HTML (unclosed <p>, non-self-closed <br>/<img>, bare
        // &nbsp;) that openhtmltopdf's strict SAX parser rejects at
        // finalize time. Running the round-trip HERE means new/re-
        // saved templates land clean on disk; the render path still
        // re-runs the same normaliser as a last-mile guard.
        String normalizedHtml =
                com.anvicorp.api.erm.idms.XhtmlNormalizer.toXhtmlFragment(html);
        // Security Wave 2 (M) — sanitize the canonical HTML before persist.
        // Strips <script>, on* handlers, javascript: URLs, and any tag /
        // attribute / protocol not on the safelist. Preserves formatting,
        // <span data-field-id="…"> field anchors, and data: URLs on
        // signature <img> tags. See CanonicalHtmlSanitizer for the policy.
        String sanitizedHtml =
                com.anvicorp.api.security.CanonicalHtmlSanitizer.sanitize(normalizedHtml);
        // ── DIAGNOSTIC LOGGING — save-time font-strip investigation ──
        // Logs the incoming payload (canvas.innerHTML) side-by-side with
        // the stored canonical_html after both XhtmlNormalizer and
        // CanonicalHtmlSanitizer have run, so the operator can compare
        // what docx-preview actually encoded on the wire vs what
        // survived to disk. Look for lines tagged
        // [IdmsSaveSchemaTrace] in the Railway logs; grep for the
        // template id or the "<style>" substring for the font-carrying
        // rules. Includes a byte-count delta as a fast sanity check
        // (a large negative delta = something big was stripped).
        //
        // OPERATOR: capture a save with the target template open, then
        // pull the two blocks and diff. This should be removed once
        // the font-strip investigation closes; it is diagnostic-only,
        // logged at INFO so Railway captures it without extra
        // configuration.
        {
            int inLen = html.length();
            int outLen = sanitizedHtml == null ? 0 : sanitizedHtml.length();
            log.info("[IdmsSaveSchemaTrace] template={} inLen={} outLen={} delta={}",
                    id, inLen, outLen, outLen - inLen);
            log.info("[IdmsSaveSchemaTrace] template={} INCOMING(canvas.innerHTML)=\n{}",
                    id, html);
            log.info("[IdmsSaveSchemaTrace] template={} NORMALIZED(XhtmlNormalizer)=\n{}",
                    id, normalizedHtml);
            log.info("[IdmsSaveSchemaTrace] template={} STORED(sanitizer)=\n{}",
                    id, sanitizedHtml);
        }
        t.setCanonicalHtml(sanitizedHtml);
        t.setFieldSchemaJson(fieldsJson);
        t.setFidelityWarningsJson(warningsJson);
        repo.save(t);
        writeAudit("SAVE_SCHEMA", t, caller,
                Map.of("fieldCount", fields.size(),
                        "warningCount", req.fidelityWarnings() == null
                                ? 0 : req.fidelityWarnings().size()));
        log.info("[EditableTemplateAdmin] saveSchema template={} key={} fields={} warnings={}",
                id, t.getKey(), fields.size(),
                req.fidelityWarnings() == null ? 0 : req.fidelityWarnings().size());
        Document doc = documentRepo.findById(t.getSourceDocumentId()).orElse(null);
        return toRow(t, doc, /*includeHeavy*/ true);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private EditableTemplateDtos.TemplateRow toRow(
            EditableTemplate t, Document sourceDoc, boolean includeHeavy) {
        String downloadUrl = null;
        if (includeHeavy && sourceDoc != null && s3.isReady()
                && s3.exists(sourceDoc.getStorageKey())) {
            downloadUrl = s3.presignGetUrl(sourceDoc.getStorageKey(), DOWNLOAD_TTL);
        }
        int fieldCount = 0;
        if (t.getFieldSchemaJson() != null && !t.getFieldSchemaJson().isBlank()) {
            try {
                List<?> parsed = objectMapper.readValue(t.getFieldSchemaJson(), List.class);
                fieldCount = parsed.size();
            } catch (Exception e) {
                log.warn("[EditableTemplateAdmin] fieldSchemaJson parse failed for {}: {}",
                        t.getId(), e.getMessage());
            }
        }
        return new EditableTemplateDtos.TemplateRow(
                t.getId(),
                t.getKey(),
                t.getTitle(),
                t.getDescription(),
                t.isActive(),
                t.getSortOrder(),
                sourceDoc != null && sourceDoc.getDeletedAt() == null,
                sourceDoc != null ? sourceDoc.getId() : null,
                sourceDoc != null ? sourceDoc.getFileName() : null,
                sourceDoc != null ? sourceDoc.getFileSize() : null,
                sourceDoc != null ? sourceDoc.getMimeType() : null,
                downloadUrl,
                fieldCount,
                includeHeavy ? t.getCanonicalHtml() : null,
                includeHeavy ? t.getFieldSchemaJson() : null,
                includeHeavy ? t.getFidelityWarningsJson() : null,
                t.getCreatedAt(),
                t.getUpdatedAt());
    }

    private void writeAudit(String action, EditableTemplate t, User caller, Object payload) {
        try {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("templateId", t.getId());
            after.put("key", t.getKey());
            after.put("title", t.getTitle());
            after.put("payload", payload);
            AuditLog entry = AuditLog.builder()
                    .entityType("EditableTemplate")
                    .entityId(t.getId())
                    .action(action)
                    .userId(caller != null ? caller.getId() : null)
                    .afterJson(objectMapper.writeValueAsString(after))
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("[EditableTemplateAdmin] audit write failed: {}", e.getMessage());
        }
    }

    private static Map<String, Object> snapshot(EditableTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", t.getTitle());
        m.put("description", t.getDescription());
        m.put("sortOrder", t.getSortOrder());
        m.put("active", t.isActive());
        return m;
    }

    private static String trimOrNull(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    /** Fallback key derivation when the client omits {@code key}. MUST
     *  produce lowercase snake-case to satisfy
     *  {@code CreateTemplateRequest.@Pattern("^[a-z0-9][a-z0-9_-]*$|^$")}
     *  AND match the frontend's {@code deriveKey} in
     *  {@code editable-templates.ts}, which is what most templates arrive
     *  with. Prior implementation produced UPPERCASE — that broke the
     *  intern's offer-letter visibility on new rows (the offer-family
     *  filter searched lowercased). */
    private static String deriveKey(String title) {
        return title.toLowerCase().replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String safeName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.length() > 100) cleaned = cleaned.substring(cleaned.length() - 100);
        return cleaned;
    }

    /** Suppress unused import warning on Optional (kept for symmetry with sibling). */
    @SuppressWarnings("unused")
    private static void referenceOptional(Optional<?> o) {
    }
}
