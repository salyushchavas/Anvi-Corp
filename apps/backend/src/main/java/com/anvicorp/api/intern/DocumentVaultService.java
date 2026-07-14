package com.anvicorp.api.intern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.s3.S3StorageService;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.security.PiiEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 4 document vault write/read service. Distinct from the existing
 * {@code DocumentService} (a read-only aggregator that maps I-9 / I-983 /
 * Offer / Resume rows into a unified vault DTO).
 *
 * <p>This service manages the {@code documents} table directly — uploads
 * from the intern UI (voided checks, supporting docs) and the SIGNED_OFFER
 * archive from the Phase 3 webhook both write through here. Content bytes
 * are encrypted at rest when sensitivity is PII / FINANCIAL / GOVERNMENT_ID.</p>
 *
 * <p>Read path is RBAC-gated: owner reads with no audit row; staff (ERM /
 * MANAGER / SUPER_ADMIN) reads write a {@code DOCUMENT_READ} audit row.
 * Trainer / Evaluator never see PII categories.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentVaultService {

    private static final Set<String> PII_SENSITIVITIES =
            Set.of("PII", "FINANCIAL", "GOVERNMENT_ID");

    /** Categories that auto-tag a sensitivity classification on upload. */
    private static final Map<String, String> CATEGORY_SENSITIVITY = Map.of(
            "W4",                  "PII",
            "I9",                  "GOVERNMENT_ID",
            "ACH",                 "FINANCIAL",
            "EMERGENCY_CONTACT",   "NORMAL",
            "HANDBOOK_ACK",        "NORMAL",
            "I983",                "PII",
            "SIGNED_OFFER",        "PII",
            "EVERIFY",             "GOVERNMENT_ID",
            "RESUME",              "NORMAL",
            "OTHER",               "NORMAL"
    );

    private final DocumentRepository documentRepository;
    private final AuditLogRepository auditLogRepository;
    private final PiiEncryptionService piiEncryption;
    private final ObjectMapper objectMapper;
    /**
     * Phase B/C (volume → S3) hook. Reads check the {@code storage_key}
     * shape via {@link #looksLikeFilesystemPath(String)}: filesystem-shaped
     * keys ({@code "./..."}, {@code "/..."}, etc.) come off the volume;
     * any other shape is an S3 object key. Writes cut over in Phase C —
     * when {@link S3StorageService#isReady()} the new upload is pushed
     * to S3 under {@code documents/<ownerUserId>/<uuid>.bin} (with the
     * configured brand prefix) and that key is stored in {@code
     * storage_key}. When S3 is not configured we fall back to writing
     * the bytes to the filesystem path under {@code app.documents.storage-path}
     * (which is ephemeral on Railway without a Volume — surviving writes
     * are evicted on redeploy, and the read path surfaces that as a clean
     * 404).
     */
    private final S3StorageService s3StorageService;

    @Value("${app.documents.storage-path:./uploads/documents}")
    private String storageRoot;

    // ── Writes ─────────────────────────────────────────────────────────────

    @Transactional
    public Document saveDocument(UUID ownerUserId,
                                  String fileName,
                                  String mimeType,
                                  byte[] bytes,
                                  String category,
                                  String sensitivity,
                                  UUID uploadedById) {
        if (ownerUserId == null) {
            throw new IllegalArgumentException("ownerUserId is required");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Document content is empty");
        }
        if (category == null) category = "OTHER";
        if (sensitivity == null) {
            sensitivity = CATEGORY_SENSITIVITY.getOrDefault(category, "NORMAL");
        }

        try {
            UUID storageUuid = UUID.randomUUID();

            String encryptionMetadataJson = null;
            byte[] toWrite = bytes;
            if (PII_SENSITIVITIES.contains(sensitivity)) {
                // Wrap bytes in our standard base64(IV||ct+tag) envelope and
                // store the on-disk/S3 object as that envelope.
                // encryption_metadata_json records the algorithm so the
                // reader knows to decrypt.
                String b64 = piiEncryption.encrypt(
                        Base64.getEncoder().encodeToString(bytes));
                toWrite = b64.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("alg", "AES-256-GCM");
                meta.put("encoded_base64_inner", true);
                encryptionMetadataJson = objectMapper.writeValueAsString(meta);
            }

            // Phase C — when S3 is configured, the bytes go to S3 and the
            // storage_key holds the object key. The read-side discriminator
            // (looksLikeFilesystemPath) is structurally false for S3 keys
            // ("documents/<userId>/<uuid>.bin" or "<brand>/documents/..."),
            // so future reads route correctly without any extra flag column.
            // When S3 isn't configured we fall back to the filesystem write
            // (legacy behavior — ephemeral on Railway without a Volume).
            String storageKey;
            if (s3StorageService.isReady()) {
                storageKey = s3StorageService.key(
                        "documents", ownerUserId.toString(), storageUuid + ".bin");
                s3StorageService.putObject(storageKey, toWrite,
                        "application/octet-stream");
            } else {
                Path dir = Paths.get(storageRoot, ownerUserId.toString());
                Files.createDirectories(dir);
                Path target = dir.resolve(storageUuid + ".bin");
                Files.write(target, toWrite);
                storageKey = target.toString();
            }

            Document doc = Document.builder()
                    .ownerUserId(ownerUserId)
                    .fileName(fileName != null ? fileName : "upload.bin")
                    .fileSize(bytes.length)
                    .mimeType(mimeType != null ? mimeType : "application/octet-stream")
                    .storageKey(storageKey)
                    .category(category)
                    .sensitivity(sensitivity)
                    .uploadedById(uploadedById)
                    .encryptionMetadataJson(encryptionMetadataJson)
                    .build();
            return documentRepository.save(doc);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Document write failed: " + e.getMessage(), e);
        }
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional
    public byte[] readDocument(UUID documentId, User caller) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        if (doc.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Document not found: " + documentId);
        }
        boolean owner = caller != null && caller.getId().equals(doc.getOwnerUserId());
        boolean staff = caller != null && (
                caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.MANAGER)
                || caller.getRoles().contains(UserRole.SUPER_ADMIN));
        if (!owner && !staff) {
            throw new ForbiddenException("Not allowed to read this document");
        }
        // PII categories never visible to non-staff non-owner; Trainer / Evaluator
        // never see encrypted-content documents at all.
        try {
            // Dual-resolve. "/..." = volume (legacy + Phase B pre-migration);
            // anything else = S3 object key written by the Phase B migration.
            // Ciphertext rows are stored verbatim in both locations, so the
            // downstream decrypt-on-read branch works identically.
            byte[] raw = readBytesByStorageKey(doc.getStorageKey());
            byte[] result;
            if (doc.getEncryptionMetadataJson() != null) {
                String b64 = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                String inner = piiEncryption.decrypt(b64);
                result = Base64.getDecoder().decode(inner);
            } else {
                result = raw;
            }
            // Audit every staff read of a sensitive document.
            if (!owner && PII_SENSITIVITIES.contains(doc.getSensitivity())) {
                writeAudit(doc, caller, "DOCUMENT_READ");
            }
            return result;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            log.error("[DocumentVault] read failed for {}: {}", documentId, e.getMessage());
            throw new RuntimeException("Document read failed");
        }
    }

    @Transactional(readOnly = true)
    public List<Document> listForOwner(UUID ownerUserId) {
        return documentRepository.findByOwnerUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(ownerUserId);
    }

    @Transactional
    public Document loadMetadataForOwner(UUID documentId, User caller) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        if (doc.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Document not found: " + documentId);
        }
        boolean owner = caller != null && caller.getId().equals(doc.getOwnerUserId());
        boolean staff = caller != null && (
                caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.MANAGER)
                || caller.getRoles().contains(UserRole.SUPER_ADMIN));
        if (!owner && !staff) {
            throw new ForbiddenException("Not allowed to read this document");
        }
        return doc;
    }

    /**
     * Load document metadata WITHOUT enforcing owner/staff RBAC. Reserved for
     * trusted callers that resolve their own access policy from a different
     * authority (e.g. the project-assignment surface authorizes by assignment
     * ownership: the trainer-uploaded project file is owned by the trainer
     * but must be downloadable by the assigned intern). Returns a clean 404
     * when the document is missing or soft-deleted.
     */
    @Transactional(readOnly = true)
    public Document loadDocumentNoAuth(UUID documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        if (doc.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Document not found: " + documentId);
        }
        return doc;
    }

    /**
     * Read document bytes WITHOUT enforcing owner/staff RBAC. See
     * {@link #loadDocumentNoAuth(UUID)} for the access-policy rationale.
     * Decrypts PII envelopes the same way {@link #readDocument} does.
     */
    @Transactional
    public byte[] readDocumentBytesNoAuth(UUID documentId) {
        Document doc = loadDocumentNoAuth(documentId);
        try {
            byte[] raw = readBytesByStorageKey(doc.getStorageKey());
            if (doc.getEncryptionMetadataJson() != null) {
                String b64 = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                String inner = piiEncryption.decrypt(b64);
                return Base64.getDecoder().decode(inner);
            }
            return raw;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            log.error("[DocumentVault] no-auth read failed for {}: {}", documentId, e.getMessage());
            throw new RuntimeException("Document read failed");
        }
    }

    @Transactional
    public void softDelete(UUID documentId, User caller) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + documentId));
        boolean owner = caller != null && caller.getId().equals(doc.getOwnerUserId());
        boolean staff = caller != null && (
                caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.SUPER_ADMIN));
        if (!owner && !staff) {
            throw new ForbiddenException("Not allowed to delete this document");
        }
        doc.setDeletedAt(Instant.now());
        documentRepository.save(doc);
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /**
     * Phase B read-side dual-resolver. Discriminates the {@code storage_key}
     * shape:
     * <ul>
     *   <li><b>Filesystem path</b> — leading {@code "/"} (absolute on Linux),
     *       {@code "./"} / {@code "../"} (relative — produced by the default
     *       {@code app.documents.storage-path=./uploads/documents} when the
     *       {@code DOCUMENTS_STORAGE_PATH} env var is unset), {@code "\\"}
     *       (Windows absolute), or {@code "<drive>:"} (Windows drive). Read
     *       via {@link Files#readAllBytes}.</li>
     *   <li><b>S3 object key</b> — any other shape (e.g. {@code
     *       documents/<userId>/<uuid>.bin} or {@code
     *       <brand>/documents/...}). Fetched via {@link
     *       S3StorageService#getObject}.</li>
     * </ul>
     * Earlier versions only recognized the absolute {@code "/"} case, which
     * mis-routed relative-path rows (the common default) to S3 → 404
     * {@code NoSuchKeyException}. Bytes are returned VERBATIM — caller still
     * applies decrypt-on-read when {@code encryption_metadata_json} is
     * non-null, regardless of source.
     */
    private byte[] readBytesByStorageKey(String storageKey) throws Exception {
        if (storageKey == null || storageKey.isBlank()) {
            throw new ResourceNotFoundException("Document has no storage key");
        }
        if (looksLikeFilesystemPath(storageKey)) {
            try {
                return Files.readAllBytes(Paths.get(storageKey));
            } catch (java.nio.file.NoSuchFileException nsf) {
                // Railway containers have ephemeral filesystems unless a
                // persistent Volume is mounted at the storage root. A redeploy
                // evicts every file previously written under `./uploads/...`.
                // Surface this as a clean 404 with an actionable message
                // rather than a cryptic 500 — the ERM needs to know the
                // bytes are gone (re-upload required), not that the server
                // exploded.
                log.warn("[DocumentVault] file evicted from volume — key={} "
                        + "(ephemeral storage was wiped by a redeploy)", storageKey);
                throw new ResourceNotFoundException(
                        "Document file is no longer available — the original "
                                + "upload was stored on ephemeral disk and was "
                                + "lost on a server restart. The owner needs to "
                                + "re-upload the document.");
            }
        }
        try {
            return s3StorageService.getObject(storageKey);
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException nsk) {
            log.warn("[DocumentVault] S3 object missing — key={}", storageKey);
            throw new ResourceNotFoundException(
                    "Document file is no longer available in storage. The "
                            + "owner needs to re-upload the document.");
        }
    }

    /**
     * True when the storage key looks like a filesystem path rather than
     * an S3 object key. Conservative: any S3 key that began with one of
     * these markers would be malformed (S3 keys don't start with
     * {@code "/"} in any reasonable layout), so false positives are
     * effectively impossible for the layouts this app produces
     * ({@code documents/...}, {@code resumes/...}, or
     * {@code <brand>/documents/...}). Public so {@link
     * com.anvicorp.api.service.ResumeService} and the Phase B
     * migration runner can share the same discriminator.
     */
    public static boolean looksLikeFilesystemPath(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.startsWith("/") || s.startsWith("\\")) return true;       // absolute (POSIX / Windows UNC)
        if (s.startsWith("./") || s.startsWith("../")) return true;     // relative
        if (s.startsWith(".\\") || s.startsWith("..\\")) return true;   // relative (Windows)
        // Windows drive letter: <letter>:[/\]
        if (s.length() >= 2 && s.charAt(1) == ':') return true;
        return false;
    }

    private void writeAudit(Document doc, User caller, String action) {
        try {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("category", doc.getCategory());
            after.put("sensitivity", doc.getSensitivity());
            after.put("ownerUserId", doc.getOwnerUserId());
            AuditLog entry = AuditLog.builder()
                    .entityType("Document")
                    .entityId(doc.getId())
                    .action(action)
                    .userId(caller != null ? caller.getId() : null)
                    .subjectUserId(doc.getOwnerUserId())
                    .afterJson(objectMapper.writeValueAsString(after))
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("[DocumentVault] audit write failed: {}", e.getMessage());
        }
    }

    public static String defaultSensitivityForCategory(String category) {
        return CATEGORY_SENSITIVITY.getOrDefault(category, "NORMAL");
    }
}
