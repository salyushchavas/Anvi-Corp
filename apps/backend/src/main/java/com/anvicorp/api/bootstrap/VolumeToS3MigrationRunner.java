package com.anvicorp.api.bootstrap;

import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.Resume;
import com.anvicorp.api.integration.s3.S3StorageService;
import com.anvicorp.api.intern.DocumentVaultService;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/**
 * Phase B (volume → S3) one-shot migration. Copy-only, idempotent,
 * per-file verified. Gated on {@code RUN_FILE_S3_MIGRATION=true} —
 * absent or false → no-op (the runner still constructs so boot stays
 * clean, but the {@link #run} body returns immediately).
 *
 * <h2>Operating principles</h2>
 * <ul>
 *   <li><b>Copy only.</b> The volume file is never deleted or
 *       modified. Phase C is a separate cutover that handles write-path
 *       move + eventual volume cleanup.</li>
 *   <li><b>Bytes verbatim.</b> Encrypted documents (PII / FINANCIAL /
 *       GOVERNMENT_ID) are stored on disk as a base64-encoded
 *       AES-256-GCM envelope. The migrator reads those bytes as-is and
 *       PutObjects them — no decrypt/re-encrypt. The Document entity's
 *       {@code encryption_metadata_json} stays untouched so the read
 *       path's decrypt branch behaves identically against either
 *       source.</li>
 *   <li><b>Idempotent.</b> Rows already pointing at an S3 key (i.e.
 *       {@code storage_key} / {@code file_path} doesn't start with
 *       "/") are skipped. Safe to re-run.</li>
 *   <li><b>Verified.</b> After PutObject, the runner HeadObjects the
 *       new key and compares {@code contentLength} to the source byte
 *       count. Only on match does it update the DB reference; on
 *       mismatch (or any earlier failure) the row stays pointing at
 *       the volume and is counted FAILED.</li>
 *   <li><b>Per-file isolation.</b> Each row migrates in its own
 *       transaction via {@link #migrateOneDocument} /
 *       {@link #migrateOneResume}; one failure never poisons the
 *       whole batch.</li>
 * </ul>
 *
 * <h2>Triggering on Railway</h2>
 * Set {@code RUN_FILE_S3_MIGRATION=true} on the backend service and
 * redeploy. The runner fires once at boot, logs a per-file line + a
 * final summary, then sleeps until the next boot. Unset the env var
 * (or set false) after the run completes so subsequent boots don't
 * re-walk the now-migrated rows for no reason.
 *
 * <h2>What this runner does NOT do</h2>
 * Change the upload path. Every new upload still lands on the volume
 * until Phase C cuts writes over.
 */
@Component
@Order(60)
@RequiredArgsConstructor
@Slf4j
public class VolumeToS3MigrationRunner implements CommandLineRunner {

    private final DocumentRepository documentRepository;
    private final ResumeRepository resumeRepository;
    private final S3StorageService s3;

    /**
     * Env-var gate. Spring's relaxed binding maps {@code
     * RUN_FILE_S3_MIGRATION=true} to this property. Default false so
     * the runner is inert until explicitly enabled.
     */
    @Value("${run.file.s3.migration:false}")
    private boolean migrationEnabled;

    @Override
    public void run(String... args) {
        if (!migrationEnabled) {
            log.debug("[VolumeToS3] RUN_FILE_S3_MIGRATION is false — skip");
            return;
        }
        if (!s3.isReady()) {
            log.warn("[VolumeToS3] S3 not ready (creds missing or "
                    + "AWS_S3_ENABLED=false) — skip");
            return;
        }
        log.info("[VolumeToS3] starting one-shot copy → bucket={} region={}",
                s3.getBucket(), s3.getRegion());

        Counts docs = migrateDocuments();
        Counts res = migrateResumes();

        log.info("[VolumeToS3] DONE — documents {{total={}, migrated={}, "
                        + "skipped={}, failed={}}}; "
                        + "resumes {{total={}, migrated={}, skipped={}, "
                        + "failed={}}}",
                docs.total, docs.migrated, docs.skipped, docs.failed,
                res.total, res.migrated, res.skipped, res.failed);
    }

    // ── Documents ──────────────────────────────────────────────────────────

    private Counts migrateDocuments() {
        Counts c = new Counts();
        // Pull all volume-path rows (absolute AND relative — the v1
        // finder only matched "/%" and silently skipped "./uploads/..."
        // rows produced by the default app.documents.storage-path).
        long totalAll = documentRepository.count();
        List<Document> toMigrate;
        try {
            toMigrate = documentRepository.findVolumeStored();
        } catch (Exception e) {
            log.warn("[VolumeToS3] document fetch failed (non-fatal): {}",
                    e.getMessage());
            return c;
        }
        c.total = (int) totalAll;
        c.skipped = (int) totalAll - toMigrate.size();
        log.info("[VolumeToS3] documents: {} total rows, {} skipped "
                        + "(already on S3), {} to migrate",
                totalAll, c.skipped, toMigrate.size());

        for (Document d : toMigrate) {
            try {
                MigrateResult r = migrateOneDocument(d.getId());
                if (r == MigrateResult.MIGRATED) c.migrated++;
                else if (r == MigrateResult.SKIPPED) c.skipped++;
                else c.failed++;
            } catch (Exception e) {
                c.failed++;
                log.warn("[VolumeToS3] document {} FAILED: {}",
                        d.getId(), e.getMessage());
            }
        }
        return c;
    }

    /**
     * Migrate a single document row in its own transaction. The fetch +
     * update + log line all live here so a failure on one row never
     * leaves the table in a half-updated state and never aborts the
     * surrounding batch.
     */
    @Transactional
    public MigrateResult migrateOneDocument(UUID documentId) {
        Document d = documentRepository.findById(documentId).orElse(null);
        if (d == null) return MigrateResult.SKIPPED;
        String currentKey = d.getStorageKey();
        if (currentKey == null || currentKey.isBlank()) {
            log.warn("[VolumeToS3] document {} has no storage_key — skip", documentId);
            return MigrateResult.SKIPPED;
        }
        if (!DocumentVaultService.looksLikeFilesystemPath(currentKey)) {
            // Already migrated (S3 key shape).
            return MigrateResult.SKIPPED;
        }
        Path src = Paths.get(currentKey);
        if (!Files.exists(src)) {
            log.warn("[VolumeToS3] document {} FAILED — source file missing: {}",
                    documentId, src);
            return MigrateResult.FAILED;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(src);
        } catch (Exception e) {
            log.warn("[VolumeToS3] document {} FAILED — read error: {}",
                    documentId, e.getMessage());
            return MigrateResult.FAILED;
        }
        // Build the canonical S3 key. We reuse the on-disk filename
        // (the random ".bin" basename) so the per-row identity carried
        // by storage_key today survives the move.
        String basename = src.getFileName() != null ? src.getFileName().toString()
                : (UUID.randomUUID() + ".bin");
        String targetKey = s3.key("documents",
                d.getOwnerUserId() == null ? "_unknown" : d.getOwnerUserId().toString(),
                basename);
        try {
            s3.putObject(targetKey, bytes,
                    d.getMimeType() != null ? d.getMimeType() : "application/octet-stream");
        } catch (Exception e) {
            log.warn("[VolumeToS3] document {} FAILED — putObject {}: {}",
                    documentId, targetKey, e.getMessage());
            return MigrateResult.FAILED;
        }
        // Verify: HeadObject + size check. headObject returns null on
        // missing key, which would be a silent put failure on the
        // provider side — treat that as FAILED.
        HeadObjectResponse head;
        try {
            head = s3.headObject(targetKey);
        } catch (Exception e) {
            log.warn("[VolumeToS3] document {} FAILED — headObject {}: {}",
                    documentId, targetKey, e.getMessage());
            return MigrateResult.FAILED;
        }
        if (head == null) {
            log.warn("[VolumeToS3] document {} FAILED — headObject returned null "
                    + "for {} (silent put failure?)", documentId, targetKey);
            return MigrateResult.FAILED;
        }
        long remoteSize = head.contentLength() == null ? -1L : head.contentLength();
        if (remoteSize != bytes.length) {
            log.warn("[VolumeToS3] document {} FAILED — size mismatch "
                    + "(local={}, remote={}) for {}",
                    documentId, bytes.length, remoteSize, targetKey);
            return MigrateResult.FAILED;
        }
        // Verified. Rewrite the pointer; the volume file stays intact.
        d.setStorageKey(targetKey);
        documentRepository.save(d);
        log.info("[VolumeToS3] document {} migrated → {} ({} bytes)",
                documentId, targetKey, bytes.length);
        return MigrateResult.MIGRATED;
    }

    // ── Resumes ────────────────────────────────────────────────────────────

    private Counts migrateResumes() {
        Counts c = new Counts();
        long totalAll = resumeRepository.count();
        List<Resume> toMigrate;
        try {
            // Picks up absolute + relative volume paths (the v1 finder
            // only matched "/%" and silently skipped "./uploads/...").
            toMigrate = resumeRepository.findVolumeStoredWithCandidateUser();
        } catch (Exception e) {
            log.warn("[VolumeToS3] resume fetch failed (non-fatal): {}",
                    e.getMessage());
            return c;
        }
        c.total = (int) totalAll;
        c.skipped = (int) totalAll - toMigrate.size();
        log.info("[VolumeToS3] resumes: {} total rows, {} skipped "
                        + "(already on S3 or null filePath), {} to migrate",
                totalAll, c.skipped, toMigrate.size());

        for (Resume r : toMigrate) {
            try {
                MigrateResult res = migrateOneResume(r.getId());
                if (res == MigrateResult.MIGRATED) c.migrated++;
                else if (res == MigrateResult.SKIPPED) c.skipped++;
                else c.failed++;
            } catch (Exception e) {
                c.failed++;
                log.warn("[VolumeToS3] resume {} FAILED: {}",
                        r.getId(), e.getMessage());
            }
        }
        return c;
    }

    @Transactional
    public MigrateResult migrateOneResume(UUID resumeId) {
        Resume r = resumeRepository.findByIdWithCandidateUser(resumeId).orElse(null);
        if (r == null) return MigrateResult.SKIPPED;
        String fp = r.getFilePath();
        if (fp == null || fp.isBlank()) {
            log.warn("[VolumeToS3] resume {} has no filePath — skip", resumeId);
            return MigrateResult.SKIPPED;
        }
        if (!DocumentVaultService.looksLikeFilesystemPath(fp)) {
            // Already migrated (S3 key shape).
            return MigrateResult.SKIPPED;
        }
        Path src = Paths.get(fp);
        if (!Files.exists(src)) {
            log.warn("[VolumeToS3] resume {} FAILED — source file missing: {}",
                    resumeId, src);
            return MigrateResult.FAILED;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(src);
        } catch (Exception e) {
            log.warn("[VolumeToS3] resume {} FAILED — read error: {}",
                    resumeId, e.getMessage());
            return MigrateResult.FAILED;
        }
        // Resume key shape mirrors the docs convention. The storedFileName
        // (UUID + extension) is the canonical on-disk identifier, so reuse
        // it as the S3 key basename for stable round-tripping.
        UUID ownerUserId = r.getCandidate() != null && r.getCandidate().getUser() != null
                ? r.getCandidate().getUser().getId() : null;
        String basename = r.getStoredFileName() != null && !r.getStoredFileName().isBlank()
                ? r.getStoredFileName()
                : (src.getFileName() != null ? src.getFileName().toString()
                        : UUID.randomUUID() + ".pdf");
        String targetKey = s3.key("resumes",
                ownerUserId == null ? "_unknown" : ownerUserId.toString(),
                basename);
        try {
            s3.putObject(targetKey, bytes,
                    r.getContentType() != null ? r.getContentType() : "application/pdf");
        } catch (Exception e) {
            log.warn("[VolumeToS3] resume {} FAILED — putObject {}: {}",
                    resumeId, targetKey, e.getMessage());
            return MigrateResult.FAILED;
        }
        HeadObjectResponse head;
        try {
            head = s3.headObject(targetKey);
        } catch (Exception e) {
            log.warn("[VolumeToS3] resume {} FAILED — headObject {}: {}",
                    resumeId, targetKey, e.getMessage());
            return MigrateResult.FAILED;
        }
        if (head == null) {
            log.warn("[VolumeToS3] resume {} FAILED — headObject returned null "
                    + "for {}", resumeId, targetKey);
            return MigrateResult.FAILED;
        }
        long remoteSize = head.contentLength() == null ? -1L : head.contentLength();
        if (remoteSize != bytes.length) {
            log.warn("[VolumeToS3] resume {} FAILED — size mismatch "
                    + "(local={}, remote={}) for {}",
                    resumeId, bytes.length, remoteSize, targetKey);
            return MigrateResult.FAILED;
        }
        r.setFilePath(targetKey);
        resumeRepository.save(r);
        log.info("[VolumeToS3] resume {} migrated → {} ({} bytes)",
                resumeId, targetKey, bytes.length);
        return MigrateResult.MIGRATED;
    }

    private enum MigrateResult { MIGRATED, SKIPPED, FAILED }

    private static class Counts {
        int total;
        int migrated;
        int skipped;
        int failed;
    }
}
