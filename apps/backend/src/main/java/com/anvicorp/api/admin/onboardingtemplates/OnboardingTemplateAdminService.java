package com.anvicorp.api.admin.onboardingtemplates;

import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.erm.documents.SkyzenDocument;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.s3.S3StorageService;
import com.anvicorp.api.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Admin CRUD for onboarding document templates. Sits over the
 * {@link OnboardingDocumentTemplate} table which is bootstrap-seeded
 * from the {@link SkyzenDocument} enum, so every known template is
 * immediately manageable. Actions:
 * <ul>
 *   <li>{@link #list}          — every template + its current file ref</li>
 *   <li>{@link #create}        — add a NEW template row</li>
 *   <li>{@link #updateMetadata}— rename / retag an existing template</li>
 *   <li>{@link #presignUpload} — presign an S3 PUT for a replacement file</li>
 *   <li>{@link #attachFile}    — swap the template's currentDocumentId
 *                                to the newly uploaded document row;
 *                                the previous doc is soft-deleted for
 *                                audit (bytes kept in S3)</li>
 *   <li>{@link #softDelete}    — flip active=false; in-progress packets
 *                                that reference the template's key
 *                                keep resolving via the same DB row
 *                                (see resolveDownload — active=false
 *                                still serves the file)</li>
 *   <li>{@link #resolveDownload} — intern-side lookup: S3 presigned
 *                                URL when a file has been uploaded,
 *                                fallback to the static enum URL
 *                                otherwise</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingTemplateAdminService {

    public static final String CATEGORY_DOCUMENT = "ONBOARDING_TEMPLATE";

    private static final Duration PRESIGN_TTL = Duration.ofMinutes(30);

    private final OnboardingDocumentTemplateRepository repo;
    private final DocumentRepository documentRepo;
    private final S3StorageService s3;

    // ── List ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OnboardingTemplateDtos.TemplateListResponse list() {
        List<OnboardingDocumentTemplate> rows = repo.findAllByOrderBySortOrderAscTitleAsc();
        // Bulk-load current documents in one query — avoids N+1 when the
        // admin has replaced many templates.
        Set<UUID> docIds = new HashSet<>();
        for (OnboardingDocumentTemplate t : rows) {
            if (t.getCurrentDocumentId() != null) docIds.add(t.getCurrentDocumentId());
        }
        Map<UUID, Document> docs = new HashMap<>();
        for (Document d : documentRepo.findAllById(docIds)) {
            if (d.getDeletedAt() == null) docs.put(d.getId(), d);
        }
        List<OnboardingTemplateDtos.TemplateRow> items = new ArrayList<>(rows.size());
        for (OnboardingDocumentTemplate t : rows) {
            Document doc = t.getCurrentDocumentId() != null
                    ? docs.get(t.getCurrentDocumentId()) : null;
            String legacyUrl = legacyStaticUrl(t.getKey());
            items.add(new OnboardingTemplateDtos.TemplateRow(
                    t.getId(),
                    t.getKey(),
                    t.getTitle(),
                    t.getCategory(),
                    t.getSensitivity(),
                    t.getDescription(),
                    doc != null ? doc.getId() : null,
                    doc != null ? doc.getFileName() : null,
                    doc != null ? doc.getFileSize() : null,
                    doc != null ? doc.getMimeType() : null,
                    legacyUrl,
                    t.isActive(),
                    t.getSortOrder(),
                    t.getCreatedAt(),
                    t.getUpdatedAt()));
        }
        return new OnboardingTemplateDtos.TemplateListResponse(items);
    }

    // ── Create / Update ──────────────────────────────────────────────

    @Transactional
    public OnboardingTemplateDtos.TemplateRow create(
            OnboardingTemplateDtos.CreateTemplateRequest req) {
        String title = trimOrNull(req == null ? null : req.title());
        String category = trimOrNull(req == null ? null : req.category());
        String sensitivity = trimOrNull(req == null ? null : req.sensitivity());
        if (title == null) throw new BadRequestException("title required");
        if (category == null) throw new BadRequestException("category required");
        if (sensitivity == null) throw new BadRequestException("sensitivity required");
        String key = trimOrNull(req.key());
        if (key == null) key = deriveKey(title);
        if (repo.existsByKey(key)) {
            throw new BadRequestException("A template already exists with key='" + key + "'");
        }
        OnboardingDocumentTemplate t = OnboardingDocumentTemplate.builder()
                .key(key)
                .title(title)
                .category(category.toUpperCase())
                .sensitivity(sensitivity.toUpperCase())
                .description(trimOrNull(req.description()))
                .active(true)
                .sortOrder(500) // custom rows land after the enum-seeded set
                .build();
        t = repo.save(t);
        log.info("[OnboardingTemplateAdmin] created template id={} key={} title={}",
                t.getId(), t.getKey(), t.getTitle());
        return toRow(t, null);
    }

    @Transactional
    public OnboardingTemplateDtos.TemplateRow updateMetadata(
            UUID templateId, OnboardingTemplateDtos.UpdateTemplateMetadataRequest req) {
        OnboardingDocumentTemplate t = repo.findById(templateId).orElseThrow(() ->
                new ResourceNotFoundException("Template not found: " + templateId));
        if (req.title() != null && !req.title().isBlank()) t.setTitle(req.title().trim());
        if (req.category() != null && !req.category().isBlank()) {
            t.setCategory(req.category().trim().toUpperCase());
        }
        if (req.sensitivity() != null && !req.sensitivity().isBlank()) {
            t.setSensitivity(req.sensitivity().trim().toUpperCase());
        }
        if (req.description() != null) t.setDescription(trimOrNull(req.description()));
        if (req.sortOrder() != null) t.setSortOrder(req.sortOrder());
        repo.save(t);
        return toRow(t, currentDocOrNull(t));
    }

    // ── Presign + attach (replace file) ──────────────────────────────

    @Transactional
    public OnboardingTemplateDtos.PresignUploadResponse presignUpload(
            UUID templateId,
            OnboardingTemplateDtos.PresignUploadRequest req,
            User caller) {
        OnboardingDocumentTemplate t = repo.findById(templateId).orElseThrow(() ->
                new ResourceNotFoundException("Template not found: " + templateId));
        String fileName = req == null || req.fileName() == null
                ? "" : req.fileName().trim();
        String contentType = req == null || req.contentType() == null
                ? "" : req.contentType().trim().toLowerCase();
        Long fileSize = req == null ? null : req.fileSize();
        if (fileName.isEmpty()) throw new BadRequestException("fileName required");
        if (contentType.isEmpty()) throw new BadRequestException("contentType required");
        if (fileSize == null || fileSize <= 0L) {
            throw new BadRequestException("fileSize must be positive");
        }
        if (fileSize > 50L * 1024 * 1024) {
            // 50 MB soft cap — templates should be lean PDFs / docs, not
            // multi-GB video assets. Cap catches accidental uploads.
            throw new BadRequestException("Template file too large (max 50 MB).");
        }
        if (!s3.isReady()) {
            throw new BadRequestException(
                    "S3 is not configured on this environment.");
        }
        String storageKey = s3.key(
                "onboarding-templates",
                t.getKey(),
                UUID.randomUUID() + "-" + safeName(fileName));
        Document doc = Document.builder()
                .ownerUserId(caller.getId())
                .uploadedById(caller.getId())
                .fileName(fileName)
                .fileSize(fileSize)
                .mimeType(contentType)
                .storageKey(storageKey)
                .category(CATEGORY_DOCUMENT)
                .sensitivity("NORMAL")
                .build();
        doc = documentRepo.save(doc);
        String uploadUrl = s3.presignPutUrl(storageKey, contentType, PRESIGN_TTL);
        return new OnboardingTemplateDtos.PresignUploadResponse(
                uploadUrl, doc.getId(), storageKey, Instant.now().plus(PRESIGN_TTL));
    }

    @Transactional
    public OnboardingTemplateDtos.TemplateRow attachFile(
            UUID templateId, OnboardingTemplateDtos.AttachFileRequest req) {
        if (req == null || req.documentId() == null) {
            throw new BadRequestException("documentId required");
        }
        OnboardingDocumentTemplate t = repo.findById(templateId).orElseThrow(() ->
                new ResourceNotFoundException("Template not found: " + templateId));
        Document doc = documentRepo.findById(req.documentId()).orElseThrow(() ->
                new ResourceNotFoundException("Document not found: " + req.documentId()));
        if (!CATEGORY_DOCUMENT.equals(doc.getCategory())) {
            throw new BadRequestException(
                    "Document is not an onboarding template file (category=" + doc.getCategory() + ")");
        }
        // Soft-delete the prior current-document — bytes stay in S3 for
        // audit, but the row disappears from any listing that filters on
        // deleted_at IS NULL. Intern packet tasks that were already
        // resolved won't re-fetch the presigned URL, so a swap doesn't
        // yank the file out from under an intern mid-download.
        UUID previous = t.getCurrentDocumentId();
        t.setCurrentDocumentId(doc.getId());
        repo.save(t);
        if (previous != null) {
            documentRepo.findById(previous).ifPresent(prior -> {
                if (prior.getDeletedAt() == null) {
                    prior.setDeletedAt(Instant.now());
                    documentRepo.save(prior);
                }
            });
        }
        log.info("[OnboardingTemplateAdmin] attachFile template={} newDoc={} previousDoc={}",
                templateId, doc.getId(), previous);
        return toRow(t, doc);
    }

    // ── Soft-delete ──────────────────────────────────────────────────

    @Transactional
    public void softDelete(UUID templateId) {
        OnboardingDocumentTemplate t = repo.findById(templateId).orElseThrow(() ->
                new ResourceNotFoundException("Template not found: " + templateId));
        // Soft — active=false — so in-progress packets that reference
        // this key still resolveDownload without a 404. The admin list
        // shows deactivated rows; the intern-facing packet UI can filter
        // if desired. Bytes stay in S3.
        t.setActive(false);
        repo.save(t);
        log.info("[OnboardingTemplateAdmin] soft-deleted template id={} key={}",
                t.getId(), t.getKey());
    }

    // ── Intern-side download resolution ──────────────────────────────

    /**
     * Resolve the current download URL for a template key. Returns an
     * S3 presigned URL when the admin has uploaded a file, else the
     * legacy static asset URL from the {@link SkyzenDocument} enum. If
     * neither is available (unknown key + no admin upload), throws a
     * 404-mapped ResourceNotFoundException.
     */
    @Transactional(readOnly = true)
    public OnboardingTemplateDtos.DownloadResolutionResponse resolveDownload(String key) {
        String cleanKey = key == null ? "" : key.trim();
        if (cleanKey.isEmpty()) throw new BadRequestException("key required");
        Optional<OnboardingDocumentTemplate> tOpt = repo.findByKey(cleanKey);
        if (tOpt.isPresent() && tOpt.get().getCurrentDocumentId() != null) {
            OnboardingDocumentTemplate t = tOpt.get();
            Document doc = documentRepo.findById(t.getCurrentDocumentId()).orElse(null);
            if (doc != null && doc.getDeletedAt() == null && s3.isReady()) {
                String url = s3.presignGetUrl(doc.getStorageKey(), PRESIGN_TTL);
                return new OnboardingTemplateDtos.DownloadResolutionResponse(
                        url, "S3", doc.getFileName(), Instant.now().plus(PRESIGN_TTL));
            }
        }
        String staticUrl = legacyStaticUrl(cleanKey);
        if (staticUrl != null) {
            return new OnboardingTemplateDtos.DownloadResolutionResponse(
                    staticUrl, "STATIC",
                    tOpt.map(OnboardingDocumentTemplate::getTitle).orElse(cleanKey),
                    null);
        }
        throw new ResourceNotFoundException("No template file available for key=" + cleanKey);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private Document currentDocOrNull(OnboardingDocumentTemplate t) {
        return t.getCurrentDocumentId() != null
                ? documentRepo.findById(t.getCurrentDocumentId()).orElse(null)
                : null;
    }

    private OnboardingTemplateDtos.TemplateRow toRow(
            OnboardingDocumentTemplate t, Document doc) {
        return new OnboardingTemplateDtos.TemplateRow(
                t.getId(), t.getKey(), t.getTitle(), t.getCategory(),
                t.getSensitivity(), t.getDescription(),
                doc != null ? doc.getId() : null,
                doc != null ? doc.getFileName() : null,
                doc != null ? doc.getFileSize() : null,
                doc != null ? doc.getMimeType() : null,
                legacyStaticUrl(t.getKey()),
                t.isActive(), t.getSortOrder(),
                t.getCreatedAt(), t.getUpdatedAt());
    }

    private static String legacyStaticUrl(String key) {
        SkyzenDocument enumVal = SkyzenDocument.fromKey(key);
        return enumVal != null ? enumVal.publicUrl() : null;
    }

    private static String trimOrNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String deriveKey(String title) {
        return title.toUpperCase().replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String safeName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.length() > 100) cleaned = cleaned.substring(cleaned.length() - 100);
        return cleaned;
    }
}
