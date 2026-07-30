package com.anvicorp.api.service;

import com.anvicorp.api.dto.ResumeResponse;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Resume;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.s3.S3StorageService;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.ResumeRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResumeService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            // Image resumes surface fine inline via the ERM preview — accept
            // the common web-safe formats so candidates who scan/screenshot
            // their resume don't get rejected at upload.
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private final ResumeRepository resumeRepository;
    private final CandidateRepository candidateRepository;
    private final ApplicationRepository applicationRepository;
    private final AuditLogRepository auditLogRepository;
    /**
     * Phase B dual-resolve hook. {@link #loadFile} prefers S3 when the row
     * carries an S3-shaped {@code filePath} (anything not starting with "/");
     * else falls back to the existing volume-based lookup via
     * {@code storedFileName}. Writes are unchanged in Phase B.
     */
    private final S3StorageService s3StorageService;

    @Value("${app.resume.storage-path:./uploads/resumes}")
    private String storagePath;

    private Path storageDir;

    @PostConstruct
    void initStorage() {
        try {
            storageDir = Paths.get(storagePath).toAbsolutePath().normalize();
            Files.createDirectories(storageDir);
            log.info("Resume storage directory initialized at: {}", storageDir);
        } catch (IOException e) {
            log.error("Failed to initialize resume storage directory at {}", storagePath, e);
            throw new IllegalStateException("Cannot initialize resume storage directory", e);
        }
    }

    @Transactional
    public ResumeResponse upload(User user, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Resume file is required");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BadRequestException(
                    "Unsupported content type. Allowed: pdf, doc, docx, jpg, png, webp");
        }

        // Lazy-create Candidate if missing — same safety net ApplicationService uses.
        // Newly registered candidates can hit upload before any code path materializes
        // their Candidate row.
        Candidate candidate = candidateRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    log.warn("Lazy-creating Candidate for user {} during resume upload",
                            user.getId());
                    return candidateRepository.save(
                            Candidate.builder().user(user).build());
                });

        String originalName = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "resume";
        String extension = extractExtension(originalName, contentType);
        String stored = UUID.randomUUID() + extension;

        // S3 first (production path). Railway's container filesystem is
        // EPHEMERAL — anything under /app/uploads/ is wiped on every
        // restart / redeploy, so a local-disk write means the resume is
        // guaranteed to disappear. When S3 is configured, we write to a
        // key like {brand}/resumes/{candidateId}/{uuid}.{ext} and store
        // the S3 key in filePath — loadFile() already knows how to
        // resolve S3-shaped filePaths (Phase B dual-resolve).
        //
        // Falls back to the volume path only when S3 is not configured
        // (local dev, CI). That fallback still works on a single-container
        // dev session but is UNSAFE in prod — which is why the boot log
        // warns loudly when S3 is not ready.
        String s3Key = null;
        Path volumeTarget = null;
        long fileSize = file.getSize();
        if (s3StorageService.isReady()) {
            s3Key = s3StorageService.key("resumes",
                    candidate.getId().toString(), stored);
            try (InputStream in = file.getInputStream()) {
                s3StorageService.putObject(s3Key, in, fileSize, contentType);
            } catch (IOException e) {
                log.error("Failed to upload resume to S3 (key={}): {}",
                        s3Key, e.getMessage(), e);
                throw new RuntimeException("Could not save resume file to S3", e);
            }
            log.info("Uploaded resume to S3 key={} sizeBytes={} contentType={}",
                    s3Key, fileSize, contentType);
        } else {
            log.warn("S3 not configured — writing resume to EPHEMERAL local "
                    + "disk at {}. In production, set AWS_S3_* env vars so "
                    + "resumes persist across restarts.", storageDir);
            try {
                Files.createDirectories(storageDir);
            } catch (IOException e) {
                log.error("Failed to ensure resume storage directory exists at {}: {}",
                        storageDir, e.getMessage(), e);
                throw new RuntimeException("Could not prepare resume storage directory", e);
            }
            volumeTarget = storageDir.resolve(stored);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, volumeTarget, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.error("Failed to write resume file to {}: {}",
                        storageDir, e.getMessage(), e);
                throw new RuntimeException("Could not save resume file", e);
            }
        }

        boolean isFirst = resumeRepository.countByCandidateId(candidate.getId()) == 0;

        // filePath discriminator per loadFile()'s Phase-B dual-resolve:
        //   S3-shaped key ("resumes/…" — no leading "/") → S3 branch
        //   filesystem path (absolute or ./relative)     → volume branch
        // Storing the S3 key here means loadFile() picks the S3 read
        // path automatically; no schema change needed.
        String filePath = s3Key != null
                ? s3Key
                : (volumeTarget != null ? volumeTarget.toString() : null);
        Resume resume = Resume.builder()
                .candidate(candidate)
                .fileName(originalName)
                .storedFileName(stored)
                .filePath(filePath)
                .fileSize(fileSize)
                .contentType(contentType)
                .fileUrl(stored)
                .isDefault(isFirst)
                .version(1)
                .build();
        resume = resumeRepository.save(resume);

        if (isFirst) {
            candidate.setDefaultResumeId(resume.getId());
            candidateRepository.save(candidate);
        }
        // GAP E6 — sensitive PII event (resume contains personal info).
        writeAudit(resume.getId(), "RESUME_UPLOADED", user.getId());
        return toResponse(resume);
    }

    @Transactional(readOnly = true)
    public List<ResumeResponse> listForUser(UUID userId) {
        Candidate candidate = candidateRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate profile not found for user " + userId));
        return resumeRepository.findByCandidateId(candidate.getId())
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public ResumeResponse setDefault(UUID userId, UUID resumeId) {
        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume not found: " + resumeId));
        Candidate candidate = candidateRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate profile not found for user " + userId));
        if (!resume.getCandidate().getId().equals(candidate.getId())) {
            throw new ForbiddenException("Resume does not belong to this user");
        }
        resumeRepository.clearDefaultForOtherResumes(candidate.getId(), resume.getId());
        resume.setIsDefault(true);
        candidate.setDefaultResumeId(resume.getId());
        candidateRepository.save(candidate);
        return toResponse(resume);
    }

    @Transactional
    public void delete(UUID userId, UUID resumeId, boolean isCandidate) {
        Resume resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume not found: " + resumeId));
        if (isCandidate) {
            Candidate candidate = candidateRepository.findByUserId(userId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Candidate profile not found for user " + userId));
            if (!resume.getCandidate().getId().equals(candidate.getId())) {
                throw new ForbiddenException("Resume does not belong to this user");
            }
        }
        if (applicationRepository.existsByResumeId(resume.getId())) {
            throw new ConflictException("Cannot delete a resume that is referenced by an application");
        }
        // Delete the underlying artifact — S3 object for S3-shaped keys,
        // volume file otherwise. Best-effort; the DB row is deleted below
        // regardless because the audit trail is the source of truth for
        // access after removal.
        String fp = resume.getFilePath();
        try {
            if (fp != null && !fp.isBlank()) {
                boolean looksLikeVolume =
                        com.anvicorp.api.intern.DocumentVaultService.looksLikeFilesystemPath(fp);
                if (looksLikeVolume) {
                    Files.deleteIfExists(Paths.get(fp));
                } else if (s3StorageService.isReady()) {
                    s3StorageService.deleteObject(fp);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to delete resume artifact {}: {}", fp, e.getMessage());
        }
        // GAP E6 — audit BEFORE the row vanishes; entityId still resolves.
        writeAudit(resume.getId(), "RESUME_DELETED", userId);
        resumeRepository.delete(resume);
    }

    /**
     * GAP E6 — write a RESUME_DOWNLOADED audit row. Public so the controller
     * can call it after the row-level ensureCanDownload gate has passed.
     * Separate @Transactional so this commit doesn't depend on the controller's
     * file-serving lifecycle.
     */
    @Transactional
    public void recordDownloadAudit(Resume resume, User caller) {
        if (resume == null || resume.getId() == null) return;
        writeAudit(resume.getId(), "RESUME_DOWNLOADED",
                caller != null ? caller.getId() : null);
    }

    private void writeAudit(UUID resumeId, String action, UUID userId) {
        // Lean event row — sensitive resume contents stay out of the audit JSON.
        // Audit IS the access log for compliance review; the file is the artifact.
        AuditLog entry = AuditLog.builder()
                .entityType("Resume")
                .entityId(resumeId)
                .action(action)
                .userId(userId)
                .build();
        auditLogRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public Resume loadEntity(UUID resumeId) {
        // Fetch-join candidate + user so the download authorization check in
        // ResumeController.ensureCanDownload (which touches resume.getCandidate().getUser())
        // doesn't hit a detached lazy proxy after this transaction closes.
        return resumeRepository.findByIdWithCandidateUser(resumeId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume not found: " + resumeId));
    }

    public Resource loadFile(Resume resume) {
        // Phase B dual-resolve. Discriminator on Resume.filePath:
        //   null / looks like a filesystem path (absolute "/", relative
        //     "./" / "../", Windows drive) → volume path (current
        //     behavior; resolve via storedFileName + storageDir so a
        //     Railway FS path change between deploys doesn't matter);
        //   anything else → S3 object key written by the Phase B
        //     migration (e.g. "resumes/<userId>/<storedFileName>") →
        //     fetch bytes and return a ByteArrayResource.
        // The earlier version only recognized "/" as a volume path,
        // which mis-routed relative-path rows (produced by the default
        // app.resume.storage-path=./uploads/resumes) to S3 → 404.
        // Writes still land on the volume in Phase B, so freshly
        // uploaded rows hit the volume branch.
        String fp = resume.getFilePath();
        boolean looksLikeS3Key = fp != null && !fp.isBlank()
                && !com.anvicorp.api.intern.DocumentVaultService.looksLikeFilesystemPath(fp);
        if (looksLikeS3Key) {
            try {
                byte[] bytes = s3StorageService.getObject(fp);
                return new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return resume.getFileName();
                    }
                };
            } catch (Exception e) {
                log.warn("Resume {} S3 fetch failed for key {}: {} — "
                        + "falling back to volume", resume.getId(), fp, e.getMessage());
                // fall through to the volume path; volume copy is the
                // intact backup until Phase C deletes it.
            }
        }
        if (resume.getStoredFileName() == null || resume.getStoredFileName().isBlank()) {
            log.warn("Resume {} has no storedFileName — cannot serve file", resume.getId());
            throw new ResourceNotFoundException("Resume file not available");
        }
        Path path = storageDir.resolve(resume.getStoredFileName());
        if (!Files.exists(path)) {
            // Clean 404, not a 500. The frontend surfaces this as "Resume file not available".
            log.warn("Resume file not on disk: {}", path);
            throw new ResourceNotFoundException("Resume file not available");
        }
        return new FileSystemResource(path.toFile());
    }

    public ResumeResponse toResponse(Resume r) {
        return ResumeResponse.builder()
                .id(r.getId())
                .fileName(r.getFileName())
                .fileSize(r.getFileSize())
                .contentType(r.getContentType())
                .isDefault(r.getIsDefault())
                .createdAt(r.getCreatedAt())
                .build();
    }

    private String extractExtension(String filename, String contentType) {
        int dot = filename.lastIndexOf('.');
        if (dot > 0 && dot < filename.length() - 1) {
            String ext = filename.substring(dot).toLowerCase();
            if (ext.matches("\\.(pdf|doc|docx)")) {
                return ext;
            }
        }
        return switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "application/msword" -> ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            default -> "";
        };
    }
}
