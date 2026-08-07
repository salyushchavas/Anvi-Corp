package com.anvicorp.api.erm.documents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.admin.onboardingtemplates.OnboardingDocumentTemplate;
import com.anvicorp.api.admin.onboardingtemplates.OnboardingDocumentTemplateRepository;
import com.anvicorp.api.admin.onboardingtemplates.OnboardingTemplateAdminService;
import com.anvicorp.api.entity.*;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.ReasonCode;
import com.anvicorp.api.erm.documents.DocumentDtos.*;
import com.anvicorp.api.event.DocumentPacketAssignedEvent;
import com.anvicorp.api.event.DocumentPacketCompletedEvent;
import com.anvicorp.api.event.DocumentTaskReviewedEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.intern.InternLifecycleService;
import com.anvicorp.api.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * ERM Phase 8 — atomic per-intern packet assignment + review state
 * machine. Replaces the Phase 5 OnboardingService surface.
 *
 * <p>Lifecycle invariants:</p>
 * <ul>
 *   <li>One active packet per intern (partial UNIQUE +
 *       {@code findActiveByLifecycle} guard).</li>
 *   <li>Reporting structure must be complete before assign (Trainer +
 *       Evaluator + Manager all set on {@code intern_lifecycles}).</li>
 *   <li>Lifecycle advances {@code EMPLOYEE_ID_CREATED →
 *       ONBOARDING_ASSIGNED} on assign, and {@code ONBOARDING_ASSIGNED
 *       → ONBOARDING_ACCEPTED} when the last task accepts.</li>
 *   <li>{@code reviewTask} uses SELECT FOR UPDATE so two ERMs cannot
 *       race to flip the same task.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentPacketService {

    private static final int ERM_COMMENTS_MIN = 20;
    private static final int MAX_TEMPLATES_PER_PACKET = 30;

    private final DocumentPacketRepository packetRepository;
    private final DocumentTaskRepository taskRepository;
    private final DocumentTaskReviewLogRepository reviewLogRepository;
    private final DocumentRepository documentRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final InternLifecycleService internLifecycleService;
    private final com.anvicorp.api.intern.InternActivationJob internActivationJob;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    /** Post-enum-widening resolver — every documentKey (enum-seeded OR
     *  admin-added custom) is validated against this table before it can
     *  be written to a task, and used for title/category/sensitivity in
     *  DTOs. Kept optional-lookup: legacy rows whose template was
     *  soft-deleted still render with an "(unknown)" title fallback. */
    private final OnboardingDocumentTemplateRepository templateRepository;
    private final OnboardingTemplateAdminService templateAdminService;

    /** ERM Phase 8.9 — resolve + validate a documentKey against the
     *  {@code onboarding_document_templates} table (enum-seeded rows +
     *  admin-added custom rows). Returns the row for downstream title /
     *  category / sensitivity lookups; throws BadRequestException when
     *  the key isn't in the DB (safer than accepting arbitrary strings —
     *  matches the prior enum whitelist behavior while allowing custom
     *  keys). */
    private OnboardingDocumentTemplate requireTemplate(String key) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException("documentKey required");
        }
        return templateRepository.findByKey(key.trim())
                .orElseThrow(() -> new BadRequestException(
                        "Unknown document key: " + key));
    }

    /** Look up a template row for read-only DTO rendering. Returns null
     *  when the row was deleted out-of-band; callers fall back to the
     *  raw key so old tasks still surface. */
    private OnboardingDocumentTemplate lookupTemplate(String key) {
        if (key == null || key.isBlank()) return null;
        return templateRepository.findByKey(key).orElse(null);
    }

    private String resolveTitleForKey(String key) {
        if (key == null || key.isBlank()) return "(unknown)";
        OnboardingDocumentTemplate t = lookupTemplate(key);
        return t != null ? t.getTitle() : key;
    }

    // ── List reads ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DocumentPacketListPage listPackets(
            String status, String search, int page, int pageSize) {
        int p = Math.max(0, page);
        int ps = Math.min(100, Math.max(1, pageSize));
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND pk.status = ? ");
            params.add(status.trim().toUpperCase());
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ? "
                    + "OR LOWER(il.employee_id) LIKE ?) ");
            String s = "%" + search.trim().toLowerCase() + "%";
            params.add(s); params.add(s); params.add(s);
        }
        long total = countOrZero(
                "SELECT COUNT(*) FROM document_packets pk "
                        + "  JOIN intern_lifecycles il ON il.id = pk.intern_lifecycle_id "
                        + "  JOIN users u ON u.id = il.user_id" + where,
                params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(ps); pageParams.add(p * ps);
        List<DocumentPacketRow> rows = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT pk.id, pk.intern_lifecycle_id, il.user_id, "
                            + "       u.full_name, il.employee_id, "
                            + "       pk.status, pk.assigned_at, pk.completed_at, "
                            + "       pk.intern_locked, pk.intern_submitted_at, "
                            + "       (SELECT COUNT(*) FROM document_tasks t WHERE t.packet_id = pk.id) AS total_tasks, "
                            + "       (SELECT COUNT(*) FROM document_tasks t WHERE t.packet_id = pk.id AND t.status='ACCEPTED') AS accepted, "
                            + "       (SELECT COUNT(*) FROM document_tasks t WHERE t.packet_id = pk.id AND t.status='SUBMITTED') AS submitted, "
                            + "       (SELECT COUNT(*) FROM document_tasks t WHERE t.packet_id = pk.id AND t.status='PENDING') AS pending, "
                            + "       (SELECT COUNT(*) FROM document_tasks t WHERE t.packet_id = pk.id AND t.status IN ('REJECTED','RESEND_REQUESTED')) AS rejected, "
                            + "       (SELECT COUNT(*) FROM document_tasks t WHERE t.packet_id = pk.id AND t.status='WAIVED') AS waived "
                            + "  FROM document_packets pk "
                            + "  JOIN intern_lifecycles il ON il.id = pk.intern_lifecycle_id "
                            + "  JOIN users u ON u.id = il.user_id "
                            + where + " ORDER BY pk.assigned_at DESC LIMIT ? OFFSET ?",
                    pageParams.toArray())) {
                rows.add(new DocumentPacketRow(
                        uuid(r.get("id")), uuid(r.get("intern_lifecycle_id")),
                        uuid(r.get("user_id")), (String) r.get("full_name"),
                        (String) r.get("employee_id"), (String) r.get("status"),
                        intVal(r.get("total_tasks")), intVal(r.get("accepted")),
                        intVal(r.get("submitted")), intVal(r.get("pending")),
                        intVal(r.get("rejected")), intVal(r.get("waived")),
                        instantOf((java.sql.Timestamp) r.get("assigned_at")),
                        instantOf((java.sql.Timestamp) r.get("completed_at")),
                        Boolean.TRUE.equals(r.get("intern_locked")),
                        instantOf((java.sql.Timestamp) r.get("intern_submitted_at"))));
            }
        } catch (Exception e) {
            log.warn("[DocumentPacket] list query failed: {}", e.getMessage());
        }
        int totalPages = ps == 0 ? 0 : (int) Math.ceil((double) total / ps);
        return new DocumentPacketListPage(rows, p, ps, total, totalPages);
    }

    @Transactional(readOnly = true)
    public DocumentPacketDetail getPacket(UUID id, User caller) {
        requireErm(caller);
        DocumentPacket pk = mustLoadPacket(id);
        return toPacketDetail(pk);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<DocumentPacketDetail> findActiveForLifecycle(
            UUID lifecycleId, User caller) {
        requireErm(caller);
        return packetRepository.findActiveByLifecycle(lifecycleId).map(this::toPacketDetail);
    }

    // ── Assign packet ────────────────────────────────────────────────────

    @Transactional
    public DocumentPacketDetail assignPacket(AssignPacketRequest req, User caller) {
        requireErm(caller);
        if (req == null || req.internLifecycleId() == null
                || req.selectedDocumentKeys() == null
                || req.selectedDocumentKeys().isEmpty()) {
            throw new BadRequestException(
                    "internLifecycleId + selectedDocumentKeys (≥1) are required");
        }
        // ERM Phase 8.2 — de-dupe + null/blank-strip + cap. Post-widening,
        // keys are String (enum-seeded OR admin-added custom) — each is
        // validated against the DB template table.
        List<String> keys = new ArrayList<>(
                new LinkedHashSet<>(req.selectedDocumentKeys()));
        keys.removeIf(k -> k == null || k.isBlank());
        if (keys.isEmpty()) {
            throw new BadRequestException(
                    "selectedDocumentKeys must contain ≥1 valid document key");
        }
        if (keys.size() > MAX_TEMPLATES_PER_PACKET) {
            throw new BadRequestException(
                    "Cannot assign more than " + MAX_TEMPLATES_PER_PACKET + " documents per packet");
        }
        List<OnboardingDocumentTemplate> docs = new ArrayList<>(keys.size());
        for (String k : keys) docs.add(requireTemplate(k));

        InternLifecycle lc = lifecycleRepository.findById(req.internLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + req.internLifecycleId()));

        User intern = userRepository.findById(lc.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Intern user not found: " + lc.getUserId()));
        InternLifecycleStatus s = intern.getLifecycleStatus();
        if (s != InternLifecycleStatus.EMPLOYEE_ID_CREATED
                && s != InternLifecycleStatus.ONBOARDING_ASSIGNED) {
            throw new ConflictException(
                    "Packet assignment requires lifecycle_status IN "
                            + "(EMPLOYEE_ID_CREATED, ONBOARDING_ASSIGNED) — current: " + s);
        }

        // Phase 8.6.4 (revised) — document onboarding runs end-to-end
        // between ERM and the intern only: ERM sends, intern downloads +
        // fills + uploads, ERM reviews + requests re-uploads until all
        // documents are approved. Trainer + Evaluator have no role in
        // this loop, so the prior reporting-structure gate is dropped.
        // T/E auto-link still happens at offer sign (when env vars are
        // set) for downstream training workflows; it's no longer a
        // prerequisite for document assignment.

        // One active packet per lifecycle.
        if (packetRepository.findActiveByLifecycle(lc.getId()).isPresent()) {
            throw new ConflictException(
                    "An active document packet already exists for this intern; "
                            + "cancel it before assigning a new one");
        }

        Instant now = Instant.now();
        DocumentPacket pk = DocumentPacket.builder()
                .internLifecycleId(lc.getId())
                .assignedById(caller.getId())
                .status("ASSIGNED")
                .customInstructions(safeTrim(req.customInstructions()))
                .assignedAt(now)
                .build();
        pk = packetRepository.save(pk);

        Map<String, String> perInstr = req.perDocumentInstructions() != null
                ? req.perDocumentInstructions() : Map.of();
        List<String> titles = new ArrayList<>();
        for (OnboardingDocumentTemplate d : docs) {
            DocumentTask task = DocumentTask.builder()
                    .packetId(pk.getId())
                    .documentKey(d.getKey())
                    .taskInstructions(perInstr.get(d.getKey()))
                    .status("PENDING")
                    .version(1)
                    .build();
            task = taskRepository.save(task);
            appendLog(task.getId(), caller.getId(), "TEMPLATE_ASSIGNED",
                    null, "PENDING", null, null);
            titles.add(d.getTitle());
        }

        // Lifecycle advance if needed.
        if (s == InternLifecycleStatus.EMPLOYEE_ID_CREATED) {
            try {
                internLifecycleService.advance(intern,
                        InternLifecycleStatus.ONBOARDING_ASSIGNED, caller.getId());
            } catch (Exception e) {
                log.warn("[DocumentPacket] lifecycle advance to ONBOARDING_ASSIGNED failed (non-fatal): {}",
                        e.getMessage());
            }
        }

        writeAudit(pk.getId(), "DOCUMENT_PACKET_ASSIGNED",
                caller.getId(), intern.getId(),
                null,
                Map.of("documentCount", docs.size(),
                        "documentKeys", docs.stream()
                                .map(OnboardingDocumentTemplate::getKey).toList()));
        try {
            eventPublisher.publishEvent(new DocumentPacketAssignedEvent(
                    pk.getId(), lc.getId(), intern.getId(), caller.getId(), titles));
        } catch (Exception e) {
            log.warn("[DocumentPacket] packet-assigned event publish failed: {}",
                    e.getMessage());
        }
        return toPacketDetail(pk);
    }

    // ── Assign additional / forgotten documents ──────────────────────────

    /**
     * ERM add-more-docs path. Layered on top of the existing packet:
     *
     * <ul>
     *   <li>Docs not yet on the packet → create a fresh PENDING task
     *       exactly like {@link #assignPacket}.</li>
     *   <li>Docs already on the packet in a terminal-good status
     *       (ACCEPTED / WAIVED) → reopen: bump version, flip to
     *       RESEND_REQUESTED, clear the prior review/submit metadata,
     *       and unlock the packet so the intern can act.</li>
     *   <li>Docs already on the packet in any in-flight status
     *       (PENDING / SUBMITTED / UNDER_REVIEW / REJECTED /
     *       RESEND_REQUESTED) → keep the row untouched but include the
     *       title in the re-notification so the intern is nudged.</li>
     * </ul>
     *
     * <p>If the packet had already COMPLETED, adding a new or reopened
     * task rolls its status back to IN_PROGRESS. Lifecycle status is
     * intentionally NOT rolled back — an already-ACTIVE intern stays
     * active while the supplemental doc is outstanding.</p>
     *
     * <p>Fires {@link DocumentPacketAssignedEvent} with the affected
     * titles so the intern gets the same email + in-app notification
     * they got for the original packet.</p>
     */
    @Transactional
    public DocumentPacketDetail addDocumentsToPacket(
            UUID packetId, AddDocumentsRequest req, User caller) {
        requireErm(caller);
        if (req == null || req.selectedDocumentKeys() == null
                || req.selectedDocumentKeys().isEmpty()) {
            throw new BadRequestException(
                    "selectedDocumentKeys (≥1) are required");
        }
        List<String> keys = new ArrayList<>(
                new LinkedHashSet<>(req.selectedDocumentKeys()));
        keys.removeIf(k -> k == null || k.isBlank());
        if (keys.isEmpty()) {
            throw new BadRequestException(
                    "selectedDocumentKeys must contain ≥1 valid document key");
        }
        List<OnboardingDocumentTemplate> docs = new ArrayList<>(keys.size());
        for (String k : keys) docs.add(requireTemplate(k));

        DocumentPacket pk = mustLoadPacket(packetId);
        if ("CANCELLED".equals(pk.getStatus())) {
            throw new ConflictException(
                    "Packet is CANCELLED; cannot add documents");
        }

        InternLifecycle lc = lifecycleRepository.findById(pk.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + pk.getInternLifecycleId()));
        User intern = userRepository.findById(lc.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Intern user not found: " + lc.getUserId()));

        Map<String, String> perInstr = req.perDocumentInstructions() != null
                ? req.perDocumentInstructions() : Map.of();
        List<String> notifyTitles = new ArrayList<>();
        List<String> addedKeys = new ArrayList<>();
        List<String> reopenedKeys = new ArrayList<>();
        List<String> renotifiedKeys = new ArrayList<>();
        boolean anyReopenedFromTerminal = false;

        for (OnboardingDocumentTemplate d : docs) {
            String key = d.getKey();
            Optional<DocumentTask> existing =
                    taskRepository.findByPacketIdAndDocumentKey(packetId, key);
            if (existing.isEmpty()) {
                DocumentTask task = DocumentTask.builder()
                        .packetId(packetId)
                        .documentKey(key)
                        .taskInstructions(perInstr.get(key))
                        .status("PENDING")
                        .version(1)
                        .build();
                task = taskRepository.save(task);
                appendLog(task.getId(), caller.getId(), "TEMPLATE_ASSIGNED",
                        null, "PENDING", null, null);
                notifyTitles.add(d.getTitle());
                addedKeys.add(key);
                continue;
            }

            DocumentTask t = existing.get();
            String previous = t.getStatus();
            if (Set.of("ACCEPTED", "WAIVED").contains(previous)) {
                // Reopen — bump version, clear prior review/submit state
                // so the intern sees a fresh task to re-upload.
                t.setStatus("RESEND_REQUESTED");
                t.setVersion(t.getVersion() == null ? 2 : t.getVersion() + 1);
                t.setSubmittedAt(null);
                t.setReviewedAt(null);
                t.setReviewedById(null);
                t.setReviewReasonCode(null);
                t.setReviewComments(null);
                t.setUploadedFileId(null);
                t.setDownloadCount(0);
                t.setLastDownloadedAt(null);
                t.setDownloadedById(null);
                // Preserve waived_* on the row for audit; status flip
                // makes it re-active regardless.
                String instr = perInstr.get(key);
                if (instr != null && !instr.isBlank()) {
                    t.setTaskInstructions(instr);
                }
                taskRepository.save(t);
                appendLog(t.getId(), caller.getId(), "REASSIGNED",
                        previous, "RESEND_REQUESTED", null,
                        "ERM re-assigned an already-closed document");
                notifyTitles.add(d.getTitle());
                reopenedKeys.add(key);
                anyReopenedFromTerminal = true;
            } else {
                // In-flight — just re-notify. Don't clobber intern's
                // in-progress upload or ERM's pending review.
                appendLog(t.getId(), caller.getId(), "RENOTIFIED",
                        previous, previous, null,
                        "ERM re-sent notification for an in-flight document");
                notifyTitles.add(d.getTitle());
                renotifiedKeys.add(key);
            }
        }

        // If we added new tasks OR reopened terminal ones, a COMPLETED
        // packet must roll back to IN_PROGRESS so the ERM listing +
        // completion checks work again. Pure re-notify against in-flight
        // tasks leaves the packet status alone.
        if (!addedKeys.isEmpty() || anyReopenedFromTerminal) {
            if ("COMPLETED".equals(pk.getStatus())) {
                pk.setStatus("IN_PROGRESS");
                pk.setCompletedAt(null);
                if (Boolean.TRUE.equals(pk.getInternLocked())) {
                    pk.setInternLocked(Boolean.FALSE);
                }
                packetRepository.save(pk);
            } else if (Boolean.TRUE.equals(pk.getInternLocked())) {
                // Adding fresh work reopens the packet for the intern.
                pk.setInternLocked(Boolean.FALSE);
                packetRepository.save(pk);
            }
        }

        // Optional custom-instructions append (kept simple: overwrite
        // only if caller supplied a non-blank value).
        String extra = safeTrim(req.customInstructions());
        if (extra != null) {
            String cur = pk.getCustomInstructions();
            pk.setCustomInstructions(cur == null || cur.isBlank()
                    ? extra : cur + "\n\n" + extra);
            packetRepository.save(pk);
        }

        writeAudit(pk.getId(), "DOCUMENT_PACKET_ADD_DOCUMENTS",
                caller.getId(), intern.getId(),
                Map.of("previousPacketStatus", pk.getStatus()),
                Map.of("added", addedKeys,
                        "reopened", reopenedKeys,
                        "renotified", renotifiedKeys));
        try {
            eventPublisher.publishEvent(new DocumentPacketAssignedEvent(
                    pk.getId(), lc.getId(), intern.getId(),
                    caller.getId(), notifyTitles));
        } catch (Exception e) {
            log.warn("[DocumentPacket] add-documents event publish failed: {}",
                    e.getMessage());
        }
        return toPacketDetail(pk);
    }

    // ── Cancel + waive (SUPER_ADMIN) ─────────────────────────────────────

    @Transactional
    public DocumentPacketDetail cancelPacket(UUID packetId, String reason, User caller) {
        if (caller == null || !caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            throw new ForbiddenException("SUPER_ADMIN required to cancel a packet");
        }
        DocumentPacket pk = mustLoadPacket(packetId);
        if ("CANCELLED".equals(pk.getStatus()) || "COMPLETED".equals(pk.getStatus())) {
            throw new ConflictException(
                    "Packet is " + pk.getStatus() + "; cannot cancel");
        }
        pk.setStatus("CANCELLED");
        pk.setCancelledAt(Instant.now());
        pk.setCancellationReason(safeTrim(reason));
        packetRepository.save(pk);
        writeAudit(pk.getId(), "DOCUMENT_PACKET_CANCELLED",
                caller.getId(), null,
                Map.of("previousStatus", pk.getStatus()),
                Map.of("reason", reason));
        return toPacketDetail(pk);
    }

    @Transactional
    public DocumentPacketDetail waivePendingTasks(UUID packetId, String reason, User caller) {
        if (caller == null || !caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            throw new ForbiddenException("SUPER_ADMIN required to waive tasks");
        }
        if (reason == null || reason.trim().length() < 10) {
            throw new BadRequestException(
                    "reason ≥10 chars required to waive pending tasks");
        }
        DocumentPacket pk = mustLoadPacket(packetId);
        Instant now = Instant.now();
        int waivedCount = 0;
        for (DocumentTask t : taskRepository.findByPacketIdOrderByCreatedAtAsc(packetId)) {
            if (!Set.of("PENDING", "SUBMITTED", "UNDER_REVIEW",
                    "REJECTED", "RESEND_REQUESTED").contains(t.getStatus())) continue;
            String previous = t.getStatus();
            t.setStatus("WAIVED");
            t.setWaivedAt(now);
            t.setWaivedById(caller.getId());
            t.setWaivedReason(reason.trim());
            taskRepository.save(t);
            appendLog(t.getId(), caller.getId(), "WAIVED", previous, "WAIVED",
                    null, reason.trim());
            waivedCount++;
        }
        writeAudit(packetId, "DOCUMENT_PACKET_WAIVE_PENDING",
                caller.getId(), null, null,
                Map.of("waivedCount", waivedCount, "reason", reason));
        checkPacketCompletion(packetId, caller);
        return toPacketDetail(mustLoadPacket(packetId));
    }

    // ── Review queue + task ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DocumentTaskListPage listReviewQueue(
            String category, String search, int page, int pageSize) {
        return listReviewQueue(category, search, null, page, pageSize);
    }

    /**
     * {@code internLifecycleId}, when non-null, scopes the queue to
     * one intern. Used by the per-intern detail page that opens when
     * an ERM clicks a name on the by-intern queue.
     */
    @Transactional(readOnly = true)
    public DocumentTaskListPage listReviewQueue(
            String category, String search,
            UUID internLifecycleId, int page, int pageSize) {
        return listReviewQueue(category, search, internLifecycleId, page, pageSize, null);
    }

    /**
     * Month-scoped overload — past-month view filters to tasks that were
     * assigned (task.created_at) in that month. Current-month path is
     * byte-identical to the legacy overload.
     */
    @Transactional(readOnly = true)
    public DocumentTaskListPage listReviewQueue(
            String category, String search,
            UUID internLifecycleId, int page, int pageSize,
            com.anvicorp.api.common.MonthRange range) {
        int p = Math.max(0, page);
        int ps = Math.min(100, Math.max(1, pageSize));
        // ERM Phase 8.9 — post-widening the whitelist of keys per category
        // lives on {@code onboarding_document_templates}, so we resolve
        // the category filter against that table (covers both enum-seeded
        // rows and admin-added custom rows).
        //
        // Past months are pure history: every task created in the window,
        // regardless of current status. Otherwise the past list would
        // silently shrink as tasks advance out of SUBMITTED. Current
        // month keeps the live "waiting on ERM" queue semantics.
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (range == null || range.isCurrent()) {
            where.append(" AND t.status = 'SUBMITTED' ");
        } else {
            where.append(" AND t.created_at >= ?::timestamptz "
                    + "  AND t.created_at <  ?::timestamptz ");
            params.add(range.startInclusive().toString());
            params.add(range.endExclusive().toString());
        }
        if (internLifecycleId != null) {
            where.append(" AND pk.intern_lifecycle_id = ? ");
            params.add(internLifecycleId);
        }
        if (category != null && !category.isBlank()) {
            String cat = category.trim().toUpperCase();
            List<String> keys = templateRepository
                    .findAllByOrderBySortOrderAscTitleAsc().stream()
                    .filter(t -> cat.equals(t.getCategory()))
                    .map(OnboardingDocumentTemplate::getKey)
                    .toList();
            if (keys.isEmpty()) {
                // No template row matches this category — return empty page.
                return new DocumentTaskListPage(List.of(), p, ps, 0L, 0);
            }
            where.append(" AND t.document_key IN (")
                    .append(String.join(",", java.util.Collections.nCopies(keys.size(), "?")))
                    .append(") ");
            params.addAll(keys);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND LOWER(u.full_name) LIKE ? ");
            String s = "%" + search.trim().toLowerCase() + "%";
            params.add(s);
        }
        long total = countOrZero(
                "SELECT COUNT(*) FROM document_tasks t "
                        + "  JOIN document_packets pk ON pk.id = t.packet_id "
                        + "  JOIN intern_lifecycles il ON il.id = pk.intern_lifecycle_id "
                        + "  JOIN users u ON u.id = il.user_id " + where,
                params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(ps); pageParams.add(p * ps);
        List<DocumentTaskRow> rows = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT t.id, t.packet_id, pk.intern_lifecycle_id, il.user_id, "
                            + "       u.full_name, t.document_key, "
                            + "       t.status, t.version, t.submitted_at "
                            + "  FROM document_tasks t "
                            + "  JOIN document_packets pk ON pk.id = t.packet_id "
                            + "  JOIN intern_lifecycles il ON il.id = pk.intern_lifecycle_id "
                            + "  JOIN users u ON u.id = il.user_id "
                            + where + " ORDER BY t.submitted_at ASC NULLS LAST LIMIT ? OFFSET ?",
                    pageParams.toArray())) {
                Instant submittedAt = instantOf((java.sql.Timestamp) r.get("submitted_at"));
                long hoursWaiting = submittedAt == null ? 0
                        : Duration.between(submittedAt, Instant.now()).toHours();
                String key = (String) r.get("document_key");
                OnboardingDocumentTemplate d = key == null ? null
                        : templateRepository.findByKey(key).orElse(null);
                rows.add(new DocumentTaskRow(
                        uuid(r.get("id")), uuid(r.get("packet_id")),
                        uuid(r.get("intern_lifecycle_id")), uuid(r.get("user_id")),
                        (String) r.get("full_name"),
                        key,
                        d != null ? d.getTitle() : (key != null ? key : "(unknown)"),
                        d != null ? d.getCategory() : null,
                        (String) r.get("status"),
                        intVal(r.get("version")), submittedAt, hoursWaiting));
            }
        } catch (Exception e) {
            log.warn("[DocumentPacket] review queue query failed: {}", e.getMessage());
        }
        int totalPages = ps == 0 ? 0 : (int) Math.ceil((double) total / ps);
        return new DocumentTaskListPage(rows, p, ps, total, totalPages);
    }

    /**
     * Person-first variant of the review queue: one row per intern
     * with at least one SUBMITTED task, ordered by who's been waiting
     * longest. Backs the {@code /erm/document-review} top-level list;
     * the per-document detail page calls {@link #listReviewQueue} with
     * an {@code internLifecycleId} filter.
     */
    @Transactional(readOnly = true)
    public InternReviewQueuePage listReviewQueueByIntern(
            String search, int page, int pageSize) {
        return listReviewQueueByIntern(search, page, pageSize, null);
    }

    /**
     * Month-scoped overload — past-month view filters to interns whose
     * packet was assigned (document_packets.assigned_at) in that month.
     * Current-month path is byte-identical to the legacy overload.
     */
    @Transactional(readOnly = true)
    public InternReviewQueuePage listReviewQueueByIntern(
            String search, int page, int pageSize,
            com.anvicorp.api.common.MonthRange range) {
        int p = Math.max(0, page);
        int ps = Math.min(100, Math.max(1, pageSize));
        // Same pure-history rule as listReviewQueue: past months include
        // every intern whose packet was assigned in the window, all
        // task statuses. Current month keeps the SUBMITTED-only queue.
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if (range == null || range.isCurrent()) {
            where.append(" AND t.status = 'SUBMITTED' ");
        } else {
            where.append(" AND pk.assigned_at >= ?::timestamptz "
                    + "  AND pk.assigned_at <  ?::timestamptz ");
            params.add(range.startInclusive().toString());
            params.add(range.endExclusive().toString());
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND LOWER(u.full_name) LIKE ? ");
            params.add("%" + search.trim().toLowerCase() + "%");
        }
        // Count distinct interns (= group cardinality) for pagination.
        long total = countOrZero(
                "SELECT COUNT(DISTINCT pk.intern_lifecycle_id) "
                        + "  FROM document_tasks t "
                        + "  JOIN document_packets pk ON pk.id = t.packet_id "
                        + "  JOIN intern_lifecycles il ON il.id = pk.intern_lifecycle_id "
                        + "  JOIN users u ON u.id = il.user_id " + where,
                params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(ps);
        pageParams.add(p * ps);
        List<InternReviewQueueRow> rows = new ArrayList<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT pk.intern_lifecycle_id, il.user_id, u.full_name, "
                            + "       COUNT(t.id) AS pending_count, "
                            + "       MIN(t.submitted_at) AS oldest_submitted_at "
                            + "  FROM document_tasks t "
                            + "  JOIN document_packets pk ON pk.id = t.packet_id "
                            + "  JOIN intern_lifecycles il ON il.id = pk.intern_lifecycle_id "
                            + "  JOIN users u ON u.id = il.user_id "
                            + where
                            + " GROUP BY pk.intern_lifecycle_id, il.user_id, u.full_name "
                            + " ORDER BY MIN(t.submitted_at) ASC NULLS LAST "
                            + " LIMIT ? OFFSET ?",
                    pageParams.toArray())) {
                Instant oldest = instantOf((java.sql.Timestamp) r.get("oldest_submitted_at"));
                long hoursWaiting = oldest == null ? 0
                        : Duration.between(oldest, Instant.now()).toHours();
                rows.add(new InternReviewQueueRow(
                        uuid(r.get("intern_lifecycle_id")),
                        uuid(r.get("user_id")),
                        (String) r.get("full_name"),
                        ((Number) r.get("pending_count")).longValue(),
                        oldest,
                        hoursWaiting));
            }
        } catch (Exception e) {
            log.warn("[DocumentPacket] by-intern review queue query failed: {}",
                    e.getMessage());
        }
        int totalPages = ps == 0 ? 0 : (int) Math.ceil((double) total / ps);
        return new InternReviewQueuePage(rows, p, ps, total, totalPages);
    }

    @Transactional
    public DocumentTaskDetail getTaskDetail(UUID taskId, User caller) {
        requireErm(caller);
        DocumentTask t = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Task not found: " + taskId));
        // Audit-log the open if the upload exists (ERM is about to view PII).
        if (t.getUploadedFileId() != null) {
            appendLog(taskId, caller.getId(), "ERM_VIEWED_UPLOAD",
                    t.getStatus(), t.getStatus(), null, null);
        }
        return toTaskDetail(t);
    }

    @Transactional
    public DocumentTaskDetail reviewTask(
            UUID taskId, ReviewTaskRequest req, User caller) {
        requireErm(caller);
        if (req == null || req.decision() == null) {
            throw new BadRequestException("decision is required");
        }
        String decision = req.decision().trim().toUpperCase();
        if (!Set.of("ACCEPT", "REJECT", "RESEND_REQUEST").contains(decision)) {
            throw new BadRequestException("decision must be ACCEPT | REJECT | RESEND_REQUEST");
        }
        DocumentTask t = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Task not found: " + taskId));
        if (!Set.of("SUBMITTED", "UNDER_REVIEW").contains(t.getStatus())) {
            throw new ConflictException(
                    "Task is " + t.getStatus() + "; cannot review");
        }

        // Pass 2 — verify-after-download. ACCEPT is server-gated on the
        // existence of a recorded download for this task. REJECT and
        // RESEND_REQUEST stay unconditional: rejecting an unread upload
        // is a legitimate ERM action (e.g. "wrong document attached" can
        // be decided from the file name + intent without opening the
        // bytes). The download endpoint stamps last_downloaded_at +
        // downloaded_by_id on every successful fetch.
        if ("ACCEPT".equals(decision) && t.getLastDownloadedAt() == null) {
            throw new ConflictException(
                    "Download the submitted file before verifying.");
        }

        ReasonCode rc = null;
        if (!"ACCEPT".equals(decision)) {
            if (req.reasonCode() == null) {
                throw new BadRequestException(
                        "reasonCode is required on REJECT / RESEND_REQUEST");
            }
            try { rc = ReasonCode.valueOf(req.reasonCode().trim()); }
            catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown reasonCode: " + req.reasonCode());
            }
            if (rc.category() != ReasonCode.Category.DOCUMENT_REJECT) {
                throw new BadRequestException(
                        "reasonCode must be in DOCUMENT_REJECT family");
            }
            if (rc.requiresFreeText()
                    && (req.reasonText() == null || req.reasonText().trim().length() < 10)) {
                throw new BadRequestException(
                        "reasonText (≥10 chars) is required for reasonCode " + rc.name());
            }
            if (req.ermComments() == null
                    || req.ermComments().trim().length() < ERM_COMMENTS_MIN) {
                throw new BadRequestException(
                        "ermComments must be at least " + ERM_COMMENTS_MIN
                                + " characters on REJECT / RESEND_REQUEST");
            }
        }

        String previous = t.getStatus();
        Instant now = Instant.now();
        switch (decision) {
            case "ACCEPT" -> t.setStatus("ACCEPTED");
            case "REJECT" -> {
                t.setStatus("REJECTED");
                t.setVersion(t.getVersion() == null ? 2 : t.getVersion() + 1);
            }
            case "RESEND_REQUEST" -> {
                t.setStatus("RESEND_REQUESTED");
                t.setVersion(t.getVersion() == null ? 2 : t.getVersion() + 1);
            }
        }
        t.setReviewedAt(now);
        t.setReviewedById(caller.getId());
        t.setReviewReasonCode(rc != null ? rc.name() : null);
        t.setReviewComments(safeTrim(req.ermComments()));
        if (req.internalNote() != null) t.setInternalNote(req.internalNote().trim());
        DocumentTask saved = taskRepository.save(t);

        appendLog(saved.getId(), caller.getId(),
                "ACCEPT".equals(decision) ? "ACCEPTED"
                        : "REJECT".equals(decision) ? "REJECTED" : "RESEND_REQUESTED",
                previous, saved.getStatus(),
                rc != null ? rc.name() : null,
                req.ermComments());

        // Map.of() rejects null values; reasonCode is null on the ACCEPT
        // path, so build the after-map with put() to preserve the null.
        java.util.Map<String, Object> auditAfter = new java.util.LinkedHashMap<>();
        auditAfter.put("status", saved.getStatus());
        auditAfter.put("reasonCode", rc != null ? rc.name() : null);
        writeAudit(saved.getId(), "DOCUMENT_TASK_" + decision,
                caller.getId(), null,
                Map.of("previousStatus", previous),
                auditAfter);

        String templateTitle = resolveTitleForKey(saved.getDocumentKey());
        UUID internUserId = lifecycleRepository.findById(
                packetRepository.findById(saved.getPacketId())
                        .map(DocumentPacket::getInternLifecycleId).orElse(null))
                .map(InternLifecycle::getUserId).orElse(null);
        try {
            eventPublisher.publishEvent(new DocumentTaskReviewedEvent(
                    saved.getId(), saved.getPacketId(), internUserId,
                    caller.getId(), decision,
                    rc != null ? rc.name() : null,
                    req.ermComments(), templateTitle));
        } catch (Exception e) {
            log.warn("[DocumentPacket] reviewed event publish failed: {}", e.getMessage());
        }

        if ("ACCEPT".equals(decision)) {
            checkPacketCompletion(saved.getPacketId(), caller);
        } else {
            // Phase 1.6 — REJECT / RESEND_REQUEST reopens this task for the
            // intern. Clear the packet's intern_locked flag so the intern
            // can re-upload + re-submit; other already-accepted tasks stay
            // accepted (per-task semantics unchanged). If the lock was
            // never set (legacy packet), this is a no-op.
            try {
                packetRepository.findById(saved.getPacketId()).ifPresent(pk -> {
                    if (Boolean.TRUE.equals(pk.getInternLocked())) {
                        pk.setInternLocked(Boolean.FALSE);
                        packetRepository.save(pk);
                    }
                });
            } catch (Exception e) {
                log.warn("[DocumentPacket] clear intern_locked on reject failed: {}",
                        e.getMessage());
            }
        }
        return toTaskDetail(saved);
    }

    // Bulk-review removed — documents are reviewed individually only.

    // ── Completion check ─────────────────────────────────────────────────

    @Transactional
    public void checkPacketCompletion(UUID packetId, User actor) {
        DocumentPacket pk = packetRepository.findById(packetId).orElse(null);
        if (pk == null) return;
        long outstanding = taskRepository.countByPacketIdAndStatusNotIn(
                packetId, List.of("ACCEPTED", "WAIVED"));
        // Side-effect: bump packet status to IN_PROGRESS / ALL_SUBMITTED.
        long submitted = taskRepository.countByPacketIdAndStatus(packetId, "SUBMITTED");
        long pending = taskRepository.countByPacketIdAndStatus(packetId, "PENDING");
        Instant now = Instant.now();
        boolean changed = false;
        if (outstanding == 0 && !"COMPLETED".equals(pk.getStatus())
                && !"CANCELLED".equals(pk.getStatus())) {
            pk.setStatus("COMPLETED");
            pk.setCompletedAt(now);
            changed = true;
        } else if (submitted > 0 && pending == 0
                && !"ALL_SUBMITTED".equals(pk.getStatus())
                && !"COMPLETED".equals(pk.getStatus())) {
            pk.setStatus("ALL_SUBMITTED");
            if (pk.getAllSubmittedAt() == null) pk.setAllSubmittedAt(now);
            changed = true;
        } else if (submitted > 0 && "ASSIGNED".equals(pk.getStatus())) {
            pk.setStatus("IN_PROGRESS");
            if (pk.getFirstSubmissionAt() == null) pk.setFirstSubmissionAt(now);
            changed = true;
        }
        if (changed) packetRepository.save(pk);

        if ("COMPLETED".equals(pk.getStatus()) && outstanding == 0) {
            // Lifecycle advance + event.
            UUID lifecycleId = pk.getInternLifecycleId();
            InternLifecycle lc = lifecycleRepository.findById(lifecycleId).orElse(null);
            if (lc != null) {
                User intern = userRepository.findById(lc.getUserId()).orElse(null);
                if (intern != null
                        && intern.getLifecycleStatus() == InternLifecycleStatus.ONBOARDING_ASSIGNED) {
                    try {
                        internLifecycleService.advance(intern,
                                InternLifecycleStatus.ONBOARDING_ACCEPTED,
                                actor != null ? actor.getId() : null);
                    } catch (Exception e) {
                        log.warn("[DocumentPacket] lifecycle advance to ONBOARDING_ACCEPTED failed: {}",
                                e.getMessage());
                    }
                    // Phase 8.9 — instant single-user activation kick. Reuses
                    // the exact start-date gate the scheduled scan applies, so
                    // an intern whose start date has already arrived flips to
                    // ACTIVE_INTERN within seconds of the last doc being
                    // accepted instead of waiting up to 10 min for the scan.
                    // Future start date → no-op (correct).
                    try {
                        internActivationJob.tryActivateIfReady(intern);
                    } catch (Exception e) {
                        log.warn("[DocumentPacket] activation kick failed (non-fatal): {}",
                                e.getMessage());
                    }
                }
                try {
                    eventPublisher.publishEvent(new DocumentPacketCompletedEvent(
                            packetId, lifecycleId, lc.getUserId()));
                } catch (Exception e) {
                    log.warn("[DocumentPacket] completed event publish failed: {}",
                            e.getMessage());
                }
            }
        }
    }

    // ── Reason codes for ERM ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ReasonCodeGroup> listReasonCodes() {
        Set<ReasonCode.Category> cats = EnumSet.of(ReasonCode.Category.DOCUMENT_REJECT);
        Map<ReasonCode.Category, List<ReasonCodeOption>> bucket = new LinkedHashMap<>();
        for (ReasonCode rc : ReasonCode.values()) {
            if (!cats.contains(rc.category())) continue;
            bucket.computeIfAbsent(rc.category(), k -> new ArrayList<>())
                    .add(new ReasonCodeOption(rc.name(), rc.humanLabel(), rc.requiresFreeText()));
        }
        List<ReasonCodeGroup> out = new ArrayList<>();
        for (var e : bucket.entrySet()) {
            out.add(new ReasonCodeGroup(e.getKey().name(), e.getValue()));
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private DocumentPacket mustLoadPacket(UUID id) {
        return packetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "DocumentPacket not found: " + id));
    }

    private void requireErm(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (!caller.getRoles().contains(UserRole.ERM)
                && !caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            throw new ForbiddenException("ERM or SUPER_ADMIN required");
        }
    }

    private void appendLog(UUID taskId, UUID actorId, String eventType,
                            String previous, String next, String reasonCode, String comments) {
        try {
            reviewLogRepository.save(DocumentTaskReviewLog.builder()
                    .taskId(taskId)
                    .actorUserId(actorId)
                    .eventType(eventType)
                    .previousStatus(previous)
                    .newStatus(next)
                    .reasonCode(reasonCode)
                    .comments(comments)
                    .build());
        } catch (Exception e) {
            log.warn("[DocumentPacket] review log append failed for task {}: {}",
                    taskId, e.getMessage());
        }
    }

    private void writeAudit(UUID entityId, String action,
                             UUID actorId, UUID subjectUserId,
                             Map<String, Object> before, Map<String, Object> after) {
        try {
            AuditLog row = AuditLog.builder()
                    .userId(actorId)
                    .subjectUserId(subjectUserId)
                    .entityType("DocumentPacket")
                    .entityId(entityId)
                    .action(action)
                    .beforeJson(before != null ? objectMapper.writeValueAsString(before) : null)
                    .afterJson(after != null ? objectMapper.writeValueAsString(after) : null)
                    .build();
            auditLogRepository.save(row);
        } catch (Exception e) {
            log.warn("[DocumentPacket] audit write failed: {}", e.getMessage());
        }
    }

    private DocumentPacketDetail toPacketDetail(DocumentPacket pk) {
        InternLifecycle lc = lifecycleRepository.findById(pk.getInternLifecycleId()).orElse(null);
        User intern = lc != null ? userRepository.findById(lc.getUserId()).orElse(null) : null;
        List<TaskSummary> tasks = new ArrayList<>();
        boolean readyToClose = true;
        for (DocumentTask t : taskRepository.findByPacketIdOrderByCreatedAtAsc(pk.getId())) {
            String key = t.getDocumentKey();
            OnboardingDocumentTemplate d = lookupTemplate(key);
            String fileName = t.getUploadedFileId() != null
                    ? documentRepository.findById(t.getUploadedFileId())
                            .map(Document::getFileName).orElse(null)
                    : null;
            String publicUrl = key != null
                    ? templateAdminService.resolveDownloadUrlOrNull(key) : null;
            tasks.add(new TaskSummary(
                    t.getId(), key,
                    d != null ? d.getTitle() : (key != null ? key : "(unknown)"),
                    d != null ? d.getCategory() : null,
                    d != null ? d.getSensitivity() : null,
                    publicUrl,
                    t.getStatus(),
                    t.getVersion(), t.getSubmittedAt(), t.getReviewedAt(),
                    t.getReviewReasonCode(), t.getReviewComments(),
                    t.getUploadedFileId(), fileName, t.getTaskInstructions()));
            if (!Set.of("ACCEPTED", "WAIVED").contains(t.getStatus())) readyToClose = false;
        }
        return new DocumentPacketDetail(
                pk.getId(), pk.getInternLifecycleId(),
                lc != null ? lc.getUserId() : null,
                intern != null ? intern.getFullName() : null,
                intern != null ? intern.getEmail() : null,
                lc != null ? lc.getEmployeeId() : null,
                pk.getStatus(), pk.getCustomInstructions(),
                pk.getAssignedAt(), pk.getFirstSubmissionAt(),
                pk.getAllSubmittedAt(), pk.getCompletedAt(),
                pk.getCancelledAt(), pk.getCancellationReason(),
                tasks, readyToClose);
    }

    private DocumentTaskDetail toTaskDetail(DocumentTask t) {
        String key = t.getDocumentKey();
        OnboardingDocumentTemplate d = lookupTemplate(key);
        Document uploaded = t.getUploadedFileId() != null
                ? documentRepository.findById(t.getUploadedFileId()).orElse(null) : null;
        User reviewer = t.getReviewedById() != null
                ? userRepository.findById(t.getReviewedById()).orElse(null) : null;
        DocumentPacket pk = packetRepository.findById(t.getPacketId()).orElse(null);
        UUID lifecycleId = pk != null ? pk.getInternLifecycleId() : null;
        InternLifecycle lc = lifecycleId != null
                ? lifecycleRepository.findById(lifecycleId).orElse(null) : null;
        User intern = lc != null ? userRepository.findById(lc.getUserId()).orElse(null) : null;
        List<ReviewEventEntry> history = new ArrayList<>();
        for (DocumentTaskReviewLog l : reviewLogRepository
                .findByTaskIdOrderByCreatedAtAsc(t.getId())) {
            User actor = l.getActorUserId() != null
                    ? userRepository.findById(l.getActorUserId()).orElse(null) : null;
            history.add(new ReviewEventEntry(
                    l.getId(), l.getActorUserId(),
                    actor != null ? actor.getFullName() : null,
                    l.getEventType(), l.getPreviousStatus(), l.getNewStatus(),
                    l.getReasonCode(), l.getComments(), l.getCreatedAt()));
        }
        String publicUrl = key != null
                ? templateAdminService.resolveDownloadUrlOrNull(key) : null;
        return new DocumentTaskDetail(
                t.getId(), t.getPacketId(),
                key,
                d != null ? d.getTitle() : (key != null ? key : null),
                d != null ? d.getCategory() : null,
                d != null ? d.getSensitivity() : null,
                publicUrl,
                t.getStatus(), t.getVersion(), t.getTaskInstructions(),
                t.getUploadedFileId(),
                uploaded != null ? uploaded.getFileName() : null,
                uploaded != null ? uploaded.getFileSize() : null,
                uploaded != null ? uploaded.getMimeType() : null,
                t.getSubmittedAt(), t.getReviewedAt(),
                t.getReviewedById(),
                reviewer != null ? reviewer.getFullName() : null,
                t.getReviewReasonCode(), t.getReviewComments(),
                t.getInternalNote(),                     // ERM DTO — included
                lifecycleId,
                lc != null ? lc.getUserId() : null,
                intern != null ? intern.getFullName() : null,
                history,
                t.getLastDownloadedAt(),
                t.getDownloadCount());
    }

    private long countOrZero(String sql, Object... params) {
        try {
            Long c = jdbc.queryForObject(sql, Long.class, params);
            return c == null ? 0L : c;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static int intVal(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        return 0;
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        try { return UUID.fromString(String.valueOf(o)); }
        catch (Exception e) { return null; }
    }

    private static Instant instantOf(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String safeTrim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
