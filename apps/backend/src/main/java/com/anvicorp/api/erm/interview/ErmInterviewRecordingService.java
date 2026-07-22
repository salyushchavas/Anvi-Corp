package com.anvicorp.api.erm.interview;

import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.s3.S3StorageService;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.InterviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * ERM interview-recording upload flow. Videos are too large for the
 * 10 MB {@code spring.servlet.multipart.max-file-size} cap, so uploads
 * bypass the backend entirely: the browser PUTs bytes straight to S3
 * against a presigned URL minted here.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>ERM picks a video file in the scorecard modal.</li>
 *   <li>Browser calls {@link #presignUpload} → we validate mime + size,
 *       reserve a {@link Document} row (so the ERM has a stable
 *       {@code documentId}), and sign a PUT URL for the reserved
 *       {@code storage_key}.</li>
 *   <li>Browser {@code fetch(PUT, presignedUrl)} the bytes directly.
 *       Backend is not in the byte path.</li>
 *   <li>ERM submits the scorecard with {@code recordingDocumentId} —
 *       {@code ErmInterviewService.complete} sets
 *       {@code Interview.recordingDocumentId} which the manager view
 *       resolves back into a presigned GET URL for playback.</li>
 * </ol>
 *
 * <h2>Orphans</h2>
 * If the browser abandons after step 2 (upload fails, ERM closes the
 * tab), the {@link Document} row exists but no interview references it
 * and no bytes were ever uploaded. Harmless — a future cleanup job can
 * sweep old {@code INTERVIEW_RECORDING} rows that no interview points at.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmInterviewRecordingService {

    public static final String CATEGORY = "INTERVIEW_RECORDING";

    /**
     * Presigned URL lifetime for the PUT — long enough to survive a slow
     * upload of a large file over a mediocre connection, short enough
     * that a leaked URL stops working before an attacker notices.
     */
    private static final Duration PRESIGN_UPLOAD_TTL = Duration.ofMinutes(30);

    /** Presigned URL lifetime for the manager GET playback URL. */
    static final Duration PRESIGN_DOWNLOAD_TTL = Duration.ofMinutes(30);

    private final InterviewRepository interviewRepository;
    private final DocumentRepository documentRepository;
    private final S3StorageService s3;

    /**
     * Max upload size in bytes. Overridable via
     * {@code app.interview.recording.max-bytes} — defaults to 2 GiB.
     */
    @Value("${app.interview.recording.max-bytes:2147483648}")
    private long maxBytes;

    @Transactional
    public ErmInterviewDtos.RecordingPresignUploadResponse presignUpload(
            UUID interviewId,
            ErmInterviewDtos.RecordingPresignUploadRequest req,
            User caller) {
        if (req == null) throw new BadRequestException("body required");
        String fileName = req.fileName() == null ? "" : req.fileName().trim();
        String contentType = req.contentType() == null ? "" : req.contentType().trim().toLowerCase();
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
                    "S3 is not configured on this environment — set AWS_S3_* to enable "
                            + "presigned uploads.");
        }

        // Existence check — surfaces a 404 for a bad interviewId instead
        // of letting the upload succeed against an orphan Document row.
        Interview iv = interviewRepository.findById(interviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Interview not found: " + interviewId));

        String storageKey = s3.key(
                "interview-recordings",
                iv.getId().toString(),
                UUID.randomUUID() + "-" + safeName(fileName));

        // Create the Document row FIRST so we can hand back a stable id.
        // ownerUserId is the ERM — matches the "who uploaded it" answer;
        // manager visibility is gated by the row-level check in the
        // manager hire-approval view (via Interview.recordingDocumentId),
        // not by Document.ownerUserId.
        Document doc = Document.builder()
                .ownerUserId(caller.getId())
                .fileName(fileName)
                .fileSize(fileSize)
                .mimeType(contentType)
                .storageKey(storageKey)
                .category(CATEGORY)
                .sensitivity("NORMAL")
                .uploadedById(caller.getId())
                .build();
        doc = documentRepository.save(doc);

        String uploadUrl = s3.presignPutUrl(storageKey, contentType, PRESIGN_UPLOAD_TTL);
        Instant expiresAt = Instant.now().plus(PRESIGN_UPLOAD_TTL);

        log.info("[ErmInterviewRecording] presigned upload for interview={} docId={} sizeBytes={} contentType={}",
                iv.getId(), doc.getId(), fileSize, contentType);

        return new ErmInterviewDtos.RecordingPresignUploadResponse(
                uploadUrl, doc.getId(), storageKey, expiresAt);
    }

    /**
     * Presigned GET URL for streaming the recording in an HTML5
     * {@code <video>} tag. Manager screen calls this via the hire-approval
     * detail response builder — no dedicated frontend fetch needed.
     *
     * <p>Null-safe: returns {@code null} if no recording is attached OR
     * S3 is not configured OR the Document row was deleted since. Any
     * of those cases surfaces on the manager UI as the "no recording
     * uploaded" empty state.</p>
     */
    public String presignDownloadUrlFor(UUID recordingDocumentId) {
        if (recordingDocumentId == null) return null;
        if (!s3.isReady()) return null;
        Document doc = documentRepository.findById(recordingDocumentId).orElse(null);
        if (doc == null) return null;
        if (doc.getDeletedAt() != null) return null;
        try {
            return s3.presignGetUrl(doc.getStorageKey(), PRESIGN_DOWNLOAD_TTL);
        } catch (Exception e) {
            log.warn("[ErmInterviewRecording] presign-download failed for docId={} key={}: {}",
                    recordingDocumentId, doc.getStorageKey(), e.getMessage());
            return null;
        }
    }

    /**
     * Strip anything that isn't ASCII alphanumeric / dot / dash / underscore
     * so the S3 key stays URL-safe. The extension survives (the last dot
     * is preserved) so mp4 / mov / webm still render correctly when the
     * browser probes the URL.
     */
    private static String safeName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.length() > 100) cleaned = cleaned.substring(cleaned.length() - 100);
        return cleaned;
    }
}
