package com.anvicorp.api.erm.documents;

import com.anvicorp.api.admin.onboardingtemplates.OnboardingDocumentTemplate;
import com.anvicorp.api.admin.onboardingtemplates.OnboardingDocumentTemplateRepository;
import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.DocumentPacket;
import com.anvicorp.api.entity.DocumentTask;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.intern.DocumentVaultService;
import com.anvicorp.api.repository.DocumentPacketRepository;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.DocumentTaskRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    /** Post-enum-widening — document_key is now a String (enum-seeded or
     *  admin-added). Title / category / sensitivity come from the DB
     *  template row instead of the SkyzenDocument enum. */
    private final OnboardingDocumentTemplateRepository templateRepository;
    private final DocumentVaultService documentVault;

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
        String key = t.getDocumentKey();
        OnboardingDocumentTemplate tpl = key == null ? null
                : templateRepository.findByKey(key).orElse(null);
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
                key,
                tpl != null ? tpl.getTitle() : key,
                tpl != null ? tpl.getCategory() : null,
                tpl != null ? tpl.getSensitivity() : null,
                t.getStatus(),
                t.getVersion(),
                fileRef,
                t.getSubmittedAt(),
                t.getReviewedAt(),
                t.getReviewReasonCode(),
                t.getReviewComments());
    }

    // ── Verified-documents ZIP export ────────────────────────────────────

    /**
     * Stream every VERIFIED (ACCEPTED-status) document for one intern
     * into a ZIP written synchronously over the response — no temp
     * files, no in-memory buffering of the whole archive.
     *
     * <p>Spec:</p>
     * <ul>
     *   <li>ZIP name is {@code "<Intern Name>.zip"}, with an ASCII
     *       fallback plus an RFC 5987 {@code filename*} for non-ASCII
     *       intern names.</li>
     *   <li>Entry names are {@code "<document title>.<original ext>"}.
     *       Duplicate names are suffixed {@code -2}, {@code -3}, … keyed
     *       on the name+extension pair so
     *       {@code Passport.pdf} and {@code Passport.jpg} coexist.</li>
     *   <li>Every entry name is sanitized against illegal characters —
     *       control chars + the Windows-illegal set + path separators
     *       are replaced with {@code _}.</li>
     *   <li>Files that fail to fetch are LISTED in
     *       {@code MISSING_FILES.txt} inside the ZIP instead of failing
     *       the whole download.</li>
     *   <li>Zero verified documents → clean 409 refusal BEFORE any
     *       response bytes go out; the frontend gates the button with a
     *       disabled tooltip so this only fires on a stale click.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public void streamVerifiedZip(UUID lifecycleId, User caller, HttpServletResponse response) {
        InternLifecycle lc = lifecycleRepository.findById(lifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Intern not found: " + lifecycleId));
        User u = lc.getUserId() != null
                ? userRepository.findById(lc.getUserId()).orElse(null) : null;
        String internName = (u != null && u.getFullName() != null
                && !u.getFullName().isBlank())
                ? u.getFullName().trim()
                : (lc.getEmployeeId() != null ? lc.getEmployeeId() : "Intern");

        // Collect every ACCEPTED task across all this intern's packets.
        List<DocumentPacket> packets = packetRepository
                .findByInternLifecycleIdOrderByAssignedAtDesc(lc.getId());
        List<VerifiedEntry> verified = new ArrayList<>();
        Set<UUID> fileIds = new HashSet<>();
        for (DocumentPacket pk : packets) {
            List<DocumentTask> tasks = taskRepository
                    .findByPacketIdOrderByCreatedAtAsc(pk.getId());
            for (DocumentTask t : tasks) {
                if (!"ACCEPTED".equals(t.getStatus())) continue;
                if (t.getUploadedFileId() == null) continue;
                fileIds.add(t.getUploadedFileId());
                verified.add(new VerifiedEntry(t.getId(), t.getDocumentKey(),
                        t.getUploadedFileId()));
            }
        }
        if (verified.isEmpty()) {
            // Clean refusal — the frontend disables the button in this
            // case, so this only fires on a stale/direct hit.
            throw new ConflictException(
                    "This intern has no verified documents yet.");
        }

        Map<UUID, Document> documentsById = fileIds.isEmpty()
                ? Map.of()
                : documentRepository.findAllById(fileIds).stream()
                        .filter(d -> d.getDeletedAt() == null)
                        .collect(Collectors.toMap(Document::getId, d -> d));

        String asciiZipName = sanitizeAscii(internName) + ".zip";
        String unicodeZipName = internName + ".zip";
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/zip");
        // RFC 5987: filename= for legacy clients, filename*= UTF-8 for
        // everything modern. Both are always emitted so a non-ASCII
        // intern name arrives with the right label.
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + asciiZipName + "\"; "
                        + "filename*=UTF-8''"
                        + URLEncoder.encode(unicodeZipName, StandardCharsets.UTF_8)
                                .replace("+", "%20"));

        // Track used names KEYED ON (name, extension) so
        // "Passport.pdf" and "Passport.jpg" don't collide with each
        // other while a second "Passport.pdf" gets "-2".
        Map<String, Integer> usedByKey = new HashMap<>();
        List<String> missing = new ArrayList<>();

        try (OutputStream out = response.getOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (VerifiedEntry v : verified) {
                Document doc = documentsById.get(v.uploadedFileId);
                String title = resolveTitle(v.documentKey);
                String origExt = doc != null ? extensionFor(doc) : "";
                String base = sanitizeFilename(title);
                String ext = sanitizeExtension(origExt);
                String key = (base + "|" + ext).toLowerCase(Locale.ROOT);
                int seq = usedByKey.merge(key, 1, Integer::sum);
                String entryName = (seq == 1 ? base : base + "-" + seq)
                        + (ext.isEmpty() ? "" : "." + ext);
                if (doc == null) {
                    missing.add(entryName + " — file record missing or deleted "
                            + "(task " + v.taskId + ")");
                    continue;
                }
                byte[] bytes;
                try {
                    bytes = documentVault.readDocument(v.uploadedFileId, caller);
                } catch (Exception e) {
                    missing.add(entryName + " — could not fetch bytes: "
                            + rootMessage(e));
                    continue;
                }
                try {
                    ZipEntry entry = new ZipEntry(entryName);
                    if (doc.getCreatedAt() != null) {
                        entry.setTime(doc.getCreatedAt().toEpochMilli());
                    }
                    zip.putNextEntry(entry);
                    zip.write(bytes);
                    zip.closeEntry();
                } catch (Exception e) {
                    missing.add(entryName + " — could not write ZIP entry: "
                            + rootMessage(e));
                }
            }
            if (!missing.isEmpty()) {
                ZipEntry manifest = new ZipEntry("MISSING_FILES.txt");
                zip.putNextEntry(manifest);
                StringBuilder sb = new StringBuilder();
                sb.append("The following files were skipped:\n\n");
                for (String line : missing) sb.append(" - ").append(line).append('\n');
                zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
        } catch (IOException e) {
            // Response is already committed once bytes flow — the client
            // will see a truncated ZIP. Log so operators can chase it
            // instead of a silent partial download.
            log.warn("[DocumentGallery] verified-zip stream failed for lifecycle {}: {}",
                    lifecycleId, e.getMessage());
        }
    }

    private String resolveTitle(String documentKey) {
        if (documentKey == null || documentKey.isBlank()) return "Untitled";
        OnboardingDocumentTemplate tpl = templateRepository.findByKey(documentKey).orElse(null);
        return tpl != null && tpl.getTitle() != null && !tpl.getTitle().isBlank()
                ? tpl.getTitle() : documentKey;
    }

    /** Extract an extension from a Document's file name (falls back to
     *  a MIME-driven guess for the common types when the file name is
     *  missing or extensionless). */
    private static String extensionFor(Document doc) {
        String name = doc.getFileName();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                return name.substring(dot + 1);
            }
        }
        String mime = doc.getMimeType() == null ? "" : doc.getMimeType().toLowerCase(Locale.ROOT);
        return switch (mime) {
            case "application/pdf" -> "pdf";
            case "image/png"       -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif"       -> "gif";
            case "image/webp"      -> "webp";
            default                -> "";
        };
    }

    /** Replace control chars + Windows-illegal set + path separators
     *  with {@code _} so entry names are safe on every extractor. */
    private static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) return "Untitled";
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c < 0x20 || c == 0x7F) { out.append('_'); continue; }
            switch (c) {
                case '/', '\\', ':', '*', '?', '"', '<', '>', '|' -> out.append('_');
                default -> out.append(c);
            }
        }
        String cleaned = out.toString().trim();
        while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned.isEmpty() ? "Untitled" : cleaned;
    }

    private static String sanitizeExtension(String ext) {
        if (ext == null) return "";
        String cleaned = ext.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return cleaned.length() > 12 ? cleaned.substring(0, 12) : cleaned;
    }

    /** ASCII fallback for Content-Disposition filename= header. Non-
     *  ASCII characters are stripped so legacy clients don't choke on
     *  them; the RFC 5987 filename* header carries the true name. */
    private static String sanitizeAscii(String raw) {
        StringBuilder out = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c >= 0x20 && c <= 0x7E && c != '"' && c != '\\') out.append(c);
        }
        String cleaned = sanitizeFilename(out.toString());
        return cleaned.isEmpty() ? "Intern" : cleaned;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }

    private record VerifiedEntry(UUID taskId, String documentKey, UUID uploadedFileId) {}

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
