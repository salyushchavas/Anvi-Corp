package com.anvicorp.api.recordings;

import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.s3.S3StorageService;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Standalone ERM interview-recording flow — used by the dedicated
 * "Upload Interview Recording" page (intern picker + video only, no
 * Interview row). Bytes go direct-to-S3 via the same presign pattern
 * the scorecard flow uses; the returned Document row has
 * {@code ownerUserId = intern.userId} so the unified gallery can
 * group by intern without needing an Interview join.
 *
 * <p>Category discriminator {@link #CATEGORY_STANDALONE_INTERVIEW}
 * distinguishes these from the scorecard-flow rows (which set
 * ownerUserId to the ERM). The gallery service filters on the pair.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmStandaloneRecordingService {

    /** Same S3 category as the scorecard flow so both share prefixes,
     *  IAM rules, and future retention policies. The ownerUserId +
     *  gallery-side split is how we distinguish the two subflows. */
    public static final String CATEGORY_STANDALONE_INTERVIEW = "INTERVIEW_RECORDING";

    private static final Duration PRESIGN_TTL = Duration.ofMinutes(30);

    private final DocumentRepository documentRepo;
    private final InternLifecycleRepository lifecycleRepo;
    private final S3StorageService s3;

    @Value("${app.interview.recording.max-bytes:2147483648}")
    private long maxBytes;

    @Transactional
    public StandaloneRecordingDtos.PresignResponse presignInterviewUpload(
            StandaloneRecordingDtos.InterviewPresignRequest req, User caller) {
        if (req == null) throw new BadRequestException("body required");
        if (req.internLifecycleId() == null) throw new BadRequestException("internLifecycleId required");
        InternLifecycle lc = lifecycleRepo.findById(req.internLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + req.internLifecycleId()));
        String fileName = req.fileName() == null ? "" : req.fileName().trim();
        String contentType = req.contentType() == null
                ? "" : req.contentType().trim().toLowerCase();
        Long fileSize = req.fileSize();
        if (fileName.isEmpty()) throw new BadRequestException("fileName required");
        if (!contentType.startsWith("video/")) {
            throw new BadRequestException(
                    "contentType must start with 'video/' (got '" + contentType + "')");
        }
        if (fileSize == null || fileSize <= 0L) {
            throw new BadRequestException("fileSize must be a positive number of bytes");
        }
        if (fileSize > maxBytes) {
            throw new BadRequestException(
                    "recording exceeds max size " + maxBytes + " bytes (got " + fileSize + ")");
        }
        if (!s3.isReady()) {
            throw new BadRequestException(
                    "S3 is not configured on this environment.");
        }

        String storageKey = s3.key(
                "interview-recordings",
                "standalone",
                lc.getUserId().toString(),
                UUID.randomUUID() + "-" + safeName(fileName));

        Document doc = Document.builder()
                // ownerUserId = INTERN so the gallery can key on it and
                // distinguish these from scorecard-flow uploads (which
                // set ownerUserId = ERM caller).
                .ownerUserId(lc.getUserId())
                .uploadedById(caller.getId())
                .fileName(fileName)
                .fileSize(fileSize)
                .mimeType(contentType)
                .storageKey(storageKey)
                .category(CATEGORY_STANDALONE_INTERVIEW)
                .sensitivity("NORMAL")
                .build();
        doc = documentRepo.save(doc);

        String uploadUrl = s3.presignPutUrl(storageKey, contentType, PRESIGN_TTL);
        Instant expiresAt = Instant.now().plus(PRESIGN_TTL);
        log.info("[StandaloneRecording] presigned interview upload lifecycle={} docId={} sizeBytes={}",
                lc.getId(), doc.getId(), fileSize);
        return new StandaloneRecordingDtos.PresignResponse(
                uploadUrl, doc.getId(), storageKey, expiresAt);
    }

    /** Presigned GET URL for playing back a standalone interview recording. */
    @Transactional(readOnly = true)
    public StandaloneRecordingDtos.DownloadUrlResponse presignInterviewDownload(UUID documentId) {
        Document doc = documentRepo.findById(documentId).orElseThrow(() ->
                new ResourceNotFoundException("Document not found: " + documentId));
        if (doc.getDeletedAt() != null || !CATEGORY_STANDALONE_INTERVIEW.equals(doc.getCategory())) {
            throw new ResourceNotFoundException("Recording is not available.");
        }
        if (!s3.isReady()) throw new BadRequestException("S3 not configured.");
        String url = s3.presignGetUrl(doc.getStorageKey(), PRESIGN_TTL);
        return new StandaloneRecordingDtos.DownloadUrlResponse(url, Instant.now().plus(PRESIGN_TTL));
    }

    private static String safeName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.length() > 100) cleaned = cleaned.substring(cleaned.length() - 100);
        return cleaned;
    }
}
