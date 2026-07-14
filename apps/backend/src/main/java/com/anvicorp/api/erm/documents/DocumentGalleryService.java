package com.anvicorp.api.erm.documents;

import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.DocumentPacket;
import com.anvicorp.api.entity.DocumentTask;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.DocumentPacketRepository;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.DocumentTaskRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ERM Document Gallery — read-only aggregation over the existing
 * document_packets / document_tasks / documents tables. No new storage.
 * Filterable intern roster + per-intern detail surface; downloads route
 * through the existing
 * {@code GET /api/v1/erm/document-review/tasks/{id}/file} endpoint that
 * already has the right ERM/SUPER_ADMIN gate + S3 dual-resolver.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentGalleryService {

    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final DocumentPacketRepository packetRepository;
    private final DocumentTaskRepository taskRepository;
    private final DocumentRepository documentRepository;

    /**
     * Per-intern gallery roster. {@code status} is a coarse filter:
     * {@code ALL} (default), {@code ACTIVE}, {@code INACTIVE} (anyone
     * who reached INACTIVE_INTERN), {@code PROSPECTIVE}, or any raw
     * {@code active_status} value. {@code search} matches against
     * employee id, full name, or email (case-insensitive substring).
     *
     * <p>Each row carries summary counters computed in-memory from the
     * pre-loaded packet / task / document maps so the wire payload is
     * actionable without a per-row drill-down round-trip.</p>
     */
    @Transactional(readOnly = true)
    public DocumentGalleryDtos.InternListResponse listInterns(
            String status, String search) {

        // Pull lifecycles by status filter — the gallery deliberately
        // includes past/inactive interns so the ERM can audit completed
        // engagements after the fact.
        List<InternLifecycle> lifecycles = loadLifecycles(status);

        // Bulk-load every packet for the displayed lifecycles in one
        // shot to avoid N+1 per-intern queries on the roster page.
        Map<UUID, List<DocumentPacket>> packetsByLifecycle = new HashMap<>();
        Set<UUID> allPacketIds = new HashSet<>();
        for (InternLifecycle lc : lifecycles) {
            List<DocumentPacket> packets = packetRepository
                    .findByInternLifecycleIdOrderByAssignedAtDesc(lc.getId());
            if (!packets.isEmpty()) {
                packetsByLifecycle.put(lc.getId(), packets);
                packets.forEach(p -> allPacketIds.add(p.getId()));
            }
        }
        Map<UUID, List<DocumentTask>> tasksByPacket = new HashMap<>();
        Set<UUID> allFileIds = new HashSet<>();
        for (UUID pid : allPacketIds) {
            List<DocumentTask> tasks = taskRepository
                    .findByPacketIdOrderByCreatedAtAsc(pid);
            if (!tasks.isEmpty()) {
                tasksByPacket.put(pid, tasks);
                for (DocumentTask t : tasks) {
                    if (t.getUploadedFileId() != null) {
                        allFileIds.add(t.getUploadedFileId());
                    }
                }
            }
        }
        Map<UUID, Document> documentsById = new HashMap<>();
        if (!allFileIds.isEmpty()) {
            for (Document d : documentRepository.findAllById(allFileIds)) {
                documentsById.put(d.getId(), d);
            }
        }
        // Bulk-load users so each row has name/email without per-row
        // round-trips. The lifecycle row's user_id is mandatory.
        Set<UUID> userIds = lifecycles.stream()
                .map(InternLifecycle::getUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, User> usersById = userIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

        String needle = search != null ? search.trim().toLowerCase(Locale.ROOT) : "";
        List<DocumentGalleryDtos.InternRow> rows = new ArrayList<>();
        for (InternLifecycle lc : lifecycles) {
            User u = lc.getUserId() != null ? usersById.get(lc.getUserId()) : null;
            String fullName = u != null ? u.getFullName() : null;
            String email = u != null ? u.getEmail() : null;
            if (!needle.isBlank()) {
                String empId = lc.getEmployeeId() != null
                        ? lc.getEmployeeId().toLowerCase(Locale.ROOT) : "";
                String nm = fullName != null ? fullName.toLowerCase(Locale.ROOT) : "";
                String em = email != null ? email.toLowerCase(Locale.ROOT) : "";
                if (!empId.contains(needle)
                        && !nm.contains(needle)
                        && !em.contains(needle)) {
                    continue;
                }
            }
            List<DocumentPacket> packets = packetsByLifecycle.getOrDefault(
                    lc.getId(), List.of());
            int packetCount = packets.size();
            int totalTasks = 0;
            int uploadedCount = 0;
            int pendingTasks = 0;
            int revisionRequestedTasks = 0;
            int acceptedTasks = 0;
            Instant lastUploadAt = null;
            for (DocumentPacket pk : packets) {
                List<DocumentTask> tasks = tasksByPacket.getOrDefault(
                        pk.getId(), List.of());
                totalTasks += tasks.size();
                for (DocumentTask t : tasks) {
                    String st = t.getStatus();
                    if ("PENDING".equals(st)) pendingTasks++;
                    if ("REJECTED".equals(st) || "RESEND_REQUESTED".equals(st)) {
                        revisionRequestedTasks++;
                    }
                    if ("ACCEPTED".equals(st)) acceptedTasks++;
                    if (t.getUploadedFileId() != null) {
                        uploadedCount++;
                        Document d = documentsById.get(t.getUploadedFileId());
                        if (d != null && d.getCreatedAt() != null
                                && (lastUploadAt == null
                                    || d.getCreatedAt().isAfter(lastUploadAt))) {
                            lastUploadAt = d.getCreatedAt();
                        }
                    }
                }
            }
            rows.add(new DocumentGalleryDtos.InternRow(
                    lc.getId(),
                    lc.getUserId(),
                    lc.getEmployeeId(),
                    fullName,
                    email,
                    lc.getActiveStatus(),
                    lc.getHiredAt(),
                    lc.getEndedAt(),
                    packetCount,
                    totalTasks,
                    uploadedCount,
                    pendingTasks,
                    revisionRequestedTasks,
                    acceptedTasks,
                    lastUploadAt));
        }
        // Sort: anyone with at least one upload bubbles up, then the
        // freshest last upload first; otherwise fall back to employee id.
        rows.sort(Comparator
                .comparing((DocumentGalleryDtos.InternRow r) -> r.uploadedCount() == 0)
                .thenComparing(
                        r -> r.lastUploadAt() == null
                                ? Instant.EPOCH : r.lastUploadAt(),
                        Comparator.reverseOrder())
                .thenComparing(
                        DocumentGalleryDtos.InternRow::employeeId,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return new DocumentGalleryDtos.InternListResponse(rows, rows.size());
    }

    /** Per-intern detail — all packets, all tasks, latest file metadata. */
    @Transactional(readOnly = true)
    public DocumentGalleryDtos.InternGalleryDetail getInternDetail(UUID lifecycleId) {
        InternLifecycle lc = lifecycleRepository.findById(lifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Intern not found: " + lifecycleId));
        User u = lc.getUserId() != null
                ? userRepository.findById(lc.getUserId()).orElse(null) : null;
        List<DocumentPacket> packets = packetRepository
                .findByInternLifecycleIdOrderByAssignedAtDesc(lc.getId());
        Set<UUID> fileIds = new HashSet<>();
        Map<UUID, List<DocumentTask>> tasksByPacket = new HashMap<>();
        for (DocumentPacket pk : packets) {
            List<DocumentTask> tasks = taskRepository
                    .findByPacketIdOrderByCreatedAtAsc(pk.getId());
            tasksByPacket.put(pk.getId(), tasks);
            for (DocumentTask t : tasks) {
                if (t.getUploadedFileId() != null) {
                    fileIds.add(t.getUploadedFileId());
                }
            }
        }
        Map<UUID, Document> documentsById = fileIds.isEmpty()
                ? Map.of()
                : documentRepository.findAllById(fileIds).stream()
                        .collect(Collectors.toMap(Document::getId, d -> d));

        List<DocumentGalleryDtos.PacketView> packetViews = packets.stream()
                .map(pk -> {
                    List<DocumentGalleryDtos.TaskView> taskViews =
                            tasksByPacket.getOrDefault(pk.getId(), List.of())
                                    .stream()
                                    .map(t -> toTaskView(t, documentsById))
                                    .toList();
                    return new DocumentGalleryDtos.PacketView(
                            pk.getId(),
                            pk.getStatus(),
                            pk.getAssignedAt(),
                            pk.getInternSubmittedAt(),
                            pk.getCompletedAt(),
                            pk.getCustomInstructions(),
                            taskViews);
                })
                .toList();
        return new DocumentGalleryDtos.InternGalleryDetail(
                lc.getId(),
                lc.getUserId(),
                lc.getEmployeeId(),
                u != null ? u.getFullName() : null,
                u != null ? u.getEmail() : null,
                lc.getActiveStatus(),
                packetViews);
    }

    private DocumentGalleryDtos.TaskView toTaskView(
            DocumentTask t, Map<UUID, Document> documentsById) {
        SkyzenDocument key = t.getDocumentKey();
        DocumentGalleryDtos.FileRef fileRef = null;
        if (t.getUploadedFileId() != null) {
            Document d = documentsById.get(t.getUploadedFileId());
            // The pure-overwrite path soft-deletes prior files. The
            // current uploaded_file_id should always point at a
            // non-deleted row; skip the file ref if it's been deleted
            // out of band (e.g. a SUPER_ADMIN /softDelete cleanup) so
            // the task still surfaces but flags "no file".
            if (d != null && d.getDeletedAt() == null) {
                fileRef = new DocumentGalleryDtos.FileRef(
                        d.getId(),
                        d.getFileName(),
                        d.getMimeType(),
                        d.getFileSize(),
                        d.getCreatedAt());
            }
        }
        return new DocumentGalleryDtos.TaskView(
                t.getId(),
                key != null ? key.name() : null,
                key != null ? key.getTitle() : null,
                key != null ? key.getCategory() : null,
                key != null ? key.getSensitivity() : null,
                t.getStatus(),
                t.getVersion(),
                fileRef,
                t.getSubmittedAt(),
                t.getReviewedAt(),
                t.getReviewReasonCode(),
                t.getReviewComments());
    }

    private List<InternLifecycle> loadLifecycles(String status) {
        String s = status == null ? "ALL" : status.trim().toUpperCase(Locale.ROOT);
        if (s.isBlank() || "ALL".equals(s)) {
            return lifecycleRepository.findAllByOrderByEmployeeIdAsc();
        }
        if ("ACTIVE".equals(s)) {
            return lifecycleRepository.findByActiveStatusInOrderByEmployeeIdAsc(
                    List.of("ACTIVE", "ACTIVE_INTERN"));
        }
        if ("INACTIVE".equals(s)) {
            return lifecycleRepository.findByActiveStatusInOrderByEmployeeIdAsc(
                    List.of("INACTIVE", "INACTIVE_INTERN", "EXITED", "TERMINATED"));
        }
        if ("PROSPECTIVE".equals(s)) {
            return lifecycleRepository.findByActiveStatusInOrderByEmployeeIdAsc(
                    List.of("PROSPECTIVE", "ONBOARDING", "ONBOARDING_ASSIGNED",
                            "ONBOARDING_ACCEPTED"));
        }
        // Any explicit raw active_status value passes through.
        return lifecycleRepository.findByActiveStatusOrderByEmployeeIdAsc(s);
    }
}
