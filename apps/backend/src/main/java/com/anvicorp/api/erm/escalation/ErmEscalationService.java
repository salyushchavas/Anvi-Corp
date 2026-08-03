package com.anvicorp.api.erm.escalation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.common.MonthRange;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.ExceptionEventLog;
import com.anvicorp.api.entity.ExceptionRecord;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.ReasonCode;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.ExceptionEventLogRepository;
import com.anvicorp.api.repository.ExceptionRecordRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ERM Phase 6 — Escalations queue. Wraps the
 * {@link ExceptionRecordRepository} with the ERM action surface
 * (assign / in-progress / resolve / dismiss / reopen / note + bulk
 * variants). Audit + notification fan-out per action.
 *
 * <p>State machine:</p>
 * <pre>
 *   OPEN ──assign──▶ ASSIGNED ──in-progress──▶ IN_PROGRESS
 *     └──resolve──▶ RESOLVED          ┐
 *     └──dismiss──▶ DISMISSED          │ within 7 days
 *                                      ▼
 *                                    reopen ──▶ OPEN
 *
 *   AUTO_RESOLVED is reserved for the scan job — never ERM-triggered.
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmEscalationService {

    private static final Set<String> ACTIVE = Set.of("OPEN", "ASSIGNED", "IN_PROGRESS");
    private static final Set<String> RESOLVED_DISMISSED =
            Set.of("RESOLVED", "DISMISSED");
    private static final Duration REOPEN_WINDOW = Duration.ofDays(7);
    private static final int BULK_MAX = 25;
    private static final int NOTE_MIN = 5;
    private static final int RESOLUTION_NOTE_MIN = 10;

    private final ExceptionRecordRepository recordRepository;
    private final ExceptionEventLogRepository eventLogRepository;
    private final UserRepository userRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserNotificationDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    // ── Reads ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ErmEscalationDtos.ExceptionListPage list(
            List<String> status, List<String> severity, List<String> exceptionType,
            UUID assignedToId, UUID internLifecycleId, String search,
            String scope, User caller, int page, int pageSize) {
        return list(status, severity, exceptionType, assignedToId,
                internLifecycleId, search, scope, caller, page, pageSize,
                MonthRange.parse(null));
    }

    /**
     * Month-scoped overload — non-current months return an empty page.
     * Current-month path is byte-identical.
     */
    @Transactional(readOnly = true)
    public ErmEscalationDtos.ExceptionListPage list(
            List<String> status, List<String> severity, List<String> exceptionType,
            UUID assignedToId, UUID internLifecycleId, String search,
            String scope, User caller, int page, int pageSize, MonthRange range) {
        MonthRange monthScope = range != null ? range : MonthRange.parse(null);
        int p = Math.max(0, page);
        int ps = Math.min(100, Math.max(1, pageSize));

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        // Past-month window: exceptions raised (opened) in that month.
        if (!monthScope.isCurrent()) {
            where.append(" AND er.opened_at >= ?::timestamptz AND er.opened_at < ?::timestamptz ");
            params.add(monthScope.startInclusive().toString());
            params.add(monthScope.endExclusive().toString());
        }

        if (status != null && !status.isEmpty()) {
            where.append(" AND er.status IN (")
                    .append(placeholders(status.size())).append(") ");
            params.addAll(status);
        }
        if (severity != null && !severity.isEmpty()) {
            where.append(" AND er.severity IN (")
                    .append(placeholders(severity.size())).append(") ");
            params.addAll(severity);
        }
        if (exceptionType != null && !exceptionType.isEmpty()) {
            where.append(" AND er.exception_type IN (")
                    .append(placeholders(exceptionType.size())).append(") ");
            params.addAll(exceptionType);
        }
        if (assignedToId != null) {
            where.append(" AND er.assigned_to_id = ? ");
            params.add(assignedToId);
        }
        if (internLifecycleId != null) {
            where.append(" AND er.intern_lifecycle_id = ? ");
            params.add(internLifecycleId);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ?) ");
            String s = "%" + search.trim().toLowerCase() + "%";
            params.add(s); params.add(s);
        }
        if ("mine".equalsIgnoreCase(scope) && caller != null) {
            where.append(" AND (il.erm_id IS NULL OR il.erm_id = ?) ");
            params.add(caller.getId());
        }

        long total;
        try {
            Long c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM exception_records er "
                            + "JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id "
                            + "JOIN users u ON u.id = er.subject_user_id" + where,
                    Long.class, params.toArray());
            total = c == null ? 0L : c;
        } catch (Exception e) {
            log.warn("[ErmEscalation] count failed: {}", e.getMessage());
            total = 0L;
        }

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(ps);
        pageParams.add(p * ps);
        List<ErmEscalationDtos.ExceptionRow> rows;
        try {
            rows = jdbc.query(
                    "SELECT er.id, er.exception_type, er.severity, er.status, "
                            + "       er.subject_user_id, u.full_name, u.employee_id, "
                            + "       er.intern_lifecycle_id, er.assigned_to_id, au.full_name AS assignee_name, "
                            + "       er.opened_at, er.last_seen_at, er.assigned_at, er.resolved_at, "
                            + "       er.subject_resource_type, er.subject_resource_id, er.payload_json, "
                            + "       EXTRACT(EPOCH FROM (NOW() - er.opened_at))/86400 AS age_days "
                            + "  FROM exception_records er "
                            + "  JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id "
                            + "  JOIN users u ON u.id = er.subject_user_id "
                            + "  LEFT JOIN users au ON au.id = er.assigned_to_id "
                            + where
                            + " ORDER BY CASE er.severity WHEN 'URGENT' THEN 0 "
                            + "                            WHEN 'WARN'   THEN 1 ELSE 2 END, "
                            + "          er.opened_at ASC "
                            + " LIMIT ? OFFSET ?",
                    pageParams.toArray(),
                    (rs, n) -> new ErmEscalationDtos.ExceptionRow(
                            nullableUuid(rs.getString("id")),
                            rs.getString("exception_type"),
                            rs.getString("severity"),
                            rs.getString("status"),
                            nullableUuid(rs.getString("subject_user_id")),
                            rs.getString("full_name"),
                            rs.getString("employee_id"),
                            nullableUuid(rs.getString("intern_lifecycle_id")),
                            nullableUuid(rs.getString("assigned_to_id")),
                            rs.getString("assignee_name"),
                            instantOf(rs.getTimestamp("opened_at")),
                            instantOf(rs.getTimestamp("last_seen_at")),
                            instantOf(rs.getTimestamp("assigned_at")),
                            instantOf(rs.getTimestamp("resolved_at")),
                            Math.max(0, (int) rs.getDouble("age_days")),
                            rs.getString("subject_resource_type"),
                            nullableUuid(rs.getString("subject_resource_id")),
                            rs.getString("payload_json")));
        } catch (Exception e) {
            log.warn("[ErmEscalation] page failed: {}", e.getMessage());
            rows = List.of();
        }

        int totalPages = ps == 0 ? 0 : (int) Math.ceil((double) total / ps);
        return new ErmEscalationDtos.ExceptionListPage(rows, p, ps, total, totalPages);
    }

    @Transactional(readOnly = true)
    public ErmEscalationDtos.ExceptionDetail get(UUID id) {
        ExceptionRecord r = recordRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Exception not found: " + id));
        return toDetail(r);
    }

    // ── State transitions ────────────────────────────────────────────────

    @Transactional
    public ErmEscalationDtos.ExceptionDetail assign(
            UUID id, ErmEscalationDtos.AssignRequest req, User caller) {
        requireStaff(caller);
        if (req == null || req.assigneeUserId() == null) {
            throw new BadRequestException("assigneeUserId is required");
        }
        User assignee = userRepository.findById(req.assigneeUserId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assignee not found: " + req.assigneeUserId()));
        if (!hasStaffRole(assignee)) {
            throw new BadRequestException(
                    "Assignee must be ERM, MANAGER, or SUPER_ADMIN");
        }
        ExceptionRecord rec = recordRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Exception not found: " + id));
        if (!ACTIVE.contains(rec.getStatus())) {
            throw new ConflictException(
                    "Cannot assign — status is " + rec.getStatus());
        }
        String previousStatus = rec.getStatus();
        String eventType = rec.getAssignedToId() == null ? "ASSIGNED" : "REASSIGNED";
        rec.setAssignedToId(assignee.getId());
        rec.setAssignedById(caller.getId());
        rec.setAssignedAt(Instant.now());
        if ("OPEN".equals(rec.getStatus())) rec.setStatus("ASSIGNED");
        ExceptionRecord saved = recordRepository.save(rec);
        appendLog(saved.getId(), caller.getId(), eventType,
                previousStatus, saved.getStatus(), null, null);
        writeAudit(saved.getId(), "EXCEPTION_" + eventType,
                caller.getId(), saved.getSubjectUserId(),
                Map.of("status", previousStatus, "assignedToId", rec.getAssignedToId()),
                Map.of("status", saved.getStatus(),
                        "assignedToId", saved.getAssignedToId()));
        notifyAssignee(saved, assignee, caller);
        return toDetail(saved);
    }

    @Transactional
    public ErmEscalationDtos.ExceptionDetail markInProgress(UUID id, User caller) {
        requireStaff(caller);
        ExceptionRecord rec = recordRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Exception not found: " + id));
        if (!ACTIVE.contains(rec.getStatus())) {
            throw new ConflictException(
                    "Cannot mark in-progress — status is " + rec.getStatus());
        }
        if ("IN_PROGRESS".equals(rec.getStatus())) return toDetail(rec);
        String previousStatus = rec.getStatus();
        rec.setStatus("IN_PROGRESS");
        if (rec.getAssignedToId() == null) {
            rec.setAssignedToId(caller.getId());
            rec.setAssignedById(caller.getId());
            rec.setAssignedAt(Instant.now());
        }
        ExceptionRecord saved = recordRepository.save(rec);
        appendLog(saved.getId(), caller.getId(), "IN_PROGRESS_SET",
                previousStatus, saved.getStatus(), null, null);
        writeAudit(saved.getId(), "EXCEPTION_IN_PROGRESS",
                caller.getId(), saved.getSubjectUserId(),
                Map.of("status", previousStatus),
                Map.of("status", saved.getStatus()));
        return toDetail(saved);
    }

    @Transactional
    public ErmEscalationDtos.ExceptionDetail addNote(
            UUID id, ErmEscalationDtos.NoteRequest req, User caller) {
        requireStaff(caller);
        if (req == null || req.note() == null
                || req.note().trim().length() < NOTE_MIN) {
            throw new BadRequestException(
                    "note must be at least " + NOTE_MIN + " characters");
        }
        ExceptionRecord rec = recordRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Exception not found: " + id));
        appendLog(rec.getId(), caller.getId(), "NOTE_ADDED",
                rec.getStatus(), rec.getStatus(), null, req.note().trim());
        return toDetail(rec);
    }

    @Transactional
    public ErmEscalationDtos.ExceptionDetail resolve(
            UUID id, ErmEscalationDtos.ResolutionRequest req, User caller) {
        requireStaff(caller);
        ReasonCode rc = validateResolution(req);
        ExceptionRecord rec = recordRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Exception not found: " + id));
        if (!ACTIVE.contains(rec.getStatus())) {
            throw new ConflictException(
                    "Cannot resolve — status is " + rec.getStatus());
        }
        String previousStatus = rec.getStatus();
        Instant now = Instant.now();
        rec.setStatus("RESOLVED");
        rec.setResolvedAt(now);
        rec.setResolvedById(caller.getId());
        rec.setResolutionReasonCode(rc.name());
        rec.setResolutionNote(req.resolutionNote().trim());
        ExceptionRecord saved = recordRepository.save(rec);
        appendLog(saved.getId(), caller.getId(), "RESOLVED",
                previousStatus, "RESOLVED", rc.name(), req.resolutionNote().trim());
        writeAudit(saved.getId(), "EXCEPTION_RESOLVED",
                caller.getId(), saved.getSubjectUserId(),
                Map.of("status", previousStatus),
                Map.of("status", "RESOLVED", "reasonCode", rc.name()));
        notifyResolution(saved, caller, "resolved");
        return toDetail(saved);
    }

    @Transactional
    public ErmEscalationDtos.ExceptionDetail dismiss(
            UUID id, ErmEscalationDtos.DismissalRequest req, User caller) {
        requireStaff(caller);
        ReasonCode rc = validateDismissal(req);
        ExceptionRecord rec = recordRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Exception not found: " + id));
        if (!ACTIVE.contains(rec.getStatus())) {
            throw new ConflictException(
                    "Cannot dismiss — status is " + rec.getStatus());
        }
        String previousStatus = rec.getStatus();
        Instant now = Instant.now();
        rec.setStatus("DISMISSED");
        rec.setResolvedAt(now);
        rec.setResolvedById(caller.getId());
        rec.setResolutionReasonCode(rc.name());
        rec.setResolutionNote(req.dismissalNote().trim());
        ExceptionRecord saved = recordRepository.save(rec);
        appendLog(saved.getId(), caller.getId(), "DISMISSED",
                previousStatus, "DISMISSED", rc.name(), req.dismissalNote().trim());
        writeAudit(saved.getId(), "EXCEPTION_DISMISSED",
                caller.getId(), saved.getSubjectUserId(),
                Map.of("status", previousStatus),
                Map.of("status", "DISMISSED", "reasonCode", rc.name()));
        notifyResolution(saved, caller, "dismissed");
        return toDetail(saved);
    }

    @Transactional
    public ErmEscalationDtos.ExceptionDetail reopen(UUID id, User caller) {
        requireStaff(caller);
        ExceptionRecord rec = recordRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Exception not found: " + id));
        if (!RESOLVED_DISMISSED.contains(rec.getStatus())
                && !"AUTO_RESOLVED".equals(rec.getStatus())) {
            throw new ConflictException(
                    "Cannot reopen — status is " + rec.getStatus());
        }
        if (rec.getResolvedAt() == null
                || Duration.between(rec.getResolvedAt(), Instant.now())
                        .compareTo(REOPEN_WINDOW) > 0) {
            throw new ConflictException(
                    "Reopen window (7 days) has passed");
        }
        // Re-opening a (subject, type) pair while another active row exists
        // would collide with the partial UNIQUE — defend in service layer too.
        recordRepository.findActiveBySubjectAndType(
                rec.getSubjectUserId(), rec.getExceptionType()).ifPresent(o -> {
            throw new ConflictException(
                    "Another active exception of this type already exists for this intern: " + o.getId());
        });

        String previousStatus = rec.getStatus();
        Instant now = Instant.now();
        rec.setStatus("OPEN");
        rec.setOpenedAt(now);
        rec.setLastSeenAt(now);
        rec.setResolvedAt(null);
        rec.setResolvedById(null);
        rec.setResolutionReasonCode(null);
        rec.setResolutionNote(null);
        ExceptionRecord saved = recordRepository.save(rec);
        appendLog(saved.getId(), caller.getId(), "REOPENED",
                previousStatus, "OPEN", null,
                "Re-opened within the 7-day window.");
        writeAudit(saved.getId(), "EXCEPTION_REOPENED",
                caller.getId(), saved.getSubjectUserId(),
                Map.of("status", previousStatus),
                Map.of("status", "OPEN"));
        return toDetail(saved);
    }

    // ── Bulk ─────────────────────────────────────────────────────────────

    @Transactional
    public ErmEscalationDtos.BulkActionResult bulkResolve(
            ErmEscalationDtos.BulkResolveRequest req, User caller) {
        requireStaff(caller);
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("ids is required");
        }
        if (req.ids().size() > BULK_MAX) {
            throw new BadRequestException("Bulk action limited to " + BULK_MAX);
        }
        validateResolution(new ErmEscalationDtos.ResolutionRequest(
                req.reasonCode(), req.reasonText(), req.resolutionNote()));
        int ok = 0;
        List<ErmEscalationDtos.BulkSkipReason> skipped = new ArrayList<>();
        for (UUID id : req.ids()) {
            try {
                resolve(id, new ErmEscalationDtos.ResolutionRequest(
                        req.reasonCode(), req.reasonText(), req.resolutionNote()), caller);
                ok++;
            } catch (RuntimeException e) {
                skipped.add(new ErmEscalationDtos.BulkSkipReason(id, e.getMessage()));
            }
        }
        return new ErmEscalationDtos.BulkActionResult(ok, skipped.size(), skipped);
    }

    @Transactional
    public ErmEscalationDtos.BulkActionResult bulkDismiss(
            ErmEscalationDtos.BulkDismissRequest req, User caller) {
        requireStaff(caller);
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("ids is required");
        }
        if (req.ids().size() > BULK_MAX) {
            throw new BadRequestException("Bulk action limited to " + BULK_MAX);
        }
        validateDismissal(new ErmEscalationDtos.DismissalRequest(
                req.reasonCode(), req.reasonText(), req.dismissalNote()));
        int ok = 0;
        List<ErmEscalationDtos.BulkSkipReason> skipped = new ArrayList<>();
        for (UUID id : req.ids()) {
            try {
                dismiss(id, new ErmEscalationDtos.DismissalRequest(
                        req.reasonCode(), req.reasonText(), req.dismissalNote()), caller);
                ok++;
            } catch (RuntimeException e) {
                skipped.add(new ErmEscalationDtos.BulkSkipReason(id, e.getMessage()));
            }
        }
        return new ErmEscalationDtos.BulkActionResult(ok, skipped.size(), skipped);
    }

    // ── Reason codes ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ErmEscalationDtos.ReasonCodeGroup> listReasonCodes() {
        Set<ReasonCode.Category> cats = EnumSet.of(
                ReasonCode.Category.EXCEPTION_RESOLVE,
                ReasonCode.Category.EXCEPTION_DISMISS);
        Map<ReasonCode.Category, List<ErmEscalationDtos.ReasonCodeOption>> bucket =
                new LinkedHashMap<>();
        for (ReasonCode rc : ReasonCode.values()) {
            if (!cats.contains(rc.category())) continue;
            bucket.computeIfAbsent(rc.category(), k -> new ArrayList<>())
                    .add(new ErmEscalationDtos.ReasonCodeOption(
                            rc.name(), rc.humanLabel(), rc.requiresFreeText()));
        }
        List<ErmEscalationDtos.ReasonCodeGroup> out = new ArrayList<>();
        for (var e : bucket.entrySet()) {
            out.add(new ErmEscalationDtos.ReasonCodeGroup(
                    e.getKey().name(), e.getValue()));
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private ReasonCode validateResolution(ErmEscalationDtos.ResolutionRequest req) {
        if (req == null || req.reasonCode() == null) {
            throw new BadRequestException("reasonCode is required");
        }
        ReasonCode rc;
        try { rc = ReasonCode.valueOf(req.reasonCode().trim()); }
        catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown reasonCode: " + req.reasonCode());
        }
        if (rc.category() != ReasonCode.Category.EXCEPTION_RESOLVE) {
            throw new BadRequestException(
                    "reasonCode must be in EXCEPTION_RESOLVE family");
        }
        if (rc.requiresFreeText()
                && (req.reasonText() == null || req.reasonText().trim().length() < 10)) {
            throw new BadRequestException(
                    "reasonText is required (≥10 chars) for " + rc.name());
        }
        if (req.resolutionNote() == null
                || req.resolutionNote().trim().length() < RESOLUTION_NOTE_MIN) {
            throw new BadRequestException(
                    "resolutionNote must be at least " + RESOLUTION_NOTE_MIN + " characters");
        }
        return rc;
    }

    private ReasonCode validateDismissal(ErmEscalationDtos.DismissalRequest req) {
        if (req == null || req.reasonCode() == null) {
            throw new BadRequestException("reasonCode is required");
        }
        ReasonCode rc;
        try { rc = ReasonCode.valueOf(req.reasonCode().trim()); }
        catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown reasonCode: " + req.reasonCode());
        }
        if (rc.category() != ReasonCode.Category.EXCEPTION_DISMISS) {
            throw new BadRequestException(
                    "reasonCode must be in EXCEPTION_DISMISS family");
        }
        if (rc.requiresFreeText()
                && (req.reasonText() == null || req.reasonText().trim().length() < 10)) {
            throw new BadRequestException(
                    "reasonText is required (≥10 chars) for " + rc.name());
        }
        if (req.dismissalNote() == null
                || req.dismissalNote().trim().length() < RESOLUTION_NOTE_MIN) {
            throw new BadRequestException(
                    "dismissalNote must be at least " + RESOLUTION_NOTE_MIN + " characters");
        }
        return rc;
    }

    private void requireStaff(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (!hasStaffRole(caller)) {
            throw new ForbiddenException("ERM or SUPER_ADMIN required");
        }
    }

    private static boolean hasStaffRole(User u) {
        return u.getRoles().contains(UserRole.ERM)
                || u.getRoles().contains(UserRole.MANAGER)
                || u.getRoles().contains(UserRole.SUPER_ADMIN);
    }

    private void appendLog(UUID recordId, UUID actorId,
                            String eventType, String previousStatus,
                            String newStatus, String reasonCode, String note) {
        try {
            eventLogRepository.save(ExceptionEventLog.builder()
                    .exceptionRecordId(recordId)
                    .actorUserId(actorId)
                    .eventType(eventType)
                    .previousStatus(previousStatus)
                    .newStatus(newStatus)
                    .reasonCode(reasonCode)
                    .note(note)
                    .build());
        } catch (Exception e) {
            log.warn("[ErmEscalation] event log append failed: {}", e.getMessage());
        }
    }

    private void writeAudit(UUID entityId, String action,
                             UUID actorId, UUID subjectUserId,
                             Map<String, Object> before, Map<String, Object> after) {
        try {
            AuditLog row = AuditLog.builder()
                    .userId(actorId)
                    .subjectUserId(subjectUserId)
                    .entityType("ExceptionRecord")
                    .entityId(entityId)
                    .action(action)
                    .beforeJson(before != null ? objectMapper.writeValueAsString(before) : null)
                    .afterJson(after != null ? objectMapper.writeValueAsString(after) : null)
                    .build();
            auditLogRepository.save(row);
        } catch (Exception e) {
            log.warn("[ErmEscalation] audit write failed: {}", e.getMessage());
        }
    }

    private void notifyAssignee(ExceptionRecord rec, User assignee, User caller) {
        try {
            dispatcher.dispatch(
                    assignee.getId(),
                    "EXCEPTION_ASSIGNED",
                    rec.getSubjectUserId(),
                    "Exception assigned: " + humanType(rec.getExceptionType()),
                    "Assigned to you by " + caller.getFullName() + ".",
                    "/careers/erm/escalations/" + rec.getId(),
                    false);
        } catch (Exception e) {
            log.debug("[ErmEscalation] assignee dispatch failed: {}", e.getMessage());
        }
    }

    private void notifyResolution(ExceptionRecord rec, User caller, String verb) {
        try {
            UUID ermId = lifecycleRepository.findById(rec.getInternLifecycleId())
                    .map(l -> l.getErmId()).orElse(null);
            if (ermId != null && !ermId.equals(caller.getId())) {
                dispatcher.dispatch(
                        ermId,
                        "EXCEPTION_" + verb.toUpperCase(),
                        rec.getSubjectUserId(),
                        "Exception " + verb + ": " + humanType(rec.getExceptionType()),
                        caller.getFullName() + " " + verb + " this exception.",
                        "/careers/erm/escalations/" + rec.getId(),
                        false);
            }
        } catch (Exception e) {
            log.debug("[ErmEscalation] resolve/dismiss dispatch failed: {}",
                    e.getMessage());
        }
    }

    private ErmEscalationDtos.ExceptionDetail toDetail(ExceptionRecord r) {
        User subject = userRepository.findById(r.getSubjectUserId()).orElse(null);
        User assignee = r.getAssignedToId() != null
                ? userRepository.findById(r.getAssignedToId()).orElse(null) : null;
        String employeeId = lifecycleRepository.findById(r.getInternLifecycleId())
                .map(l -> l.getEmployeeId()).orElse(null);
        List<ErmEscalationDtos.EventLogEntry> history = new ArrayList<>();
        try {
            for (ExceptionEventLog l : eventLogRepository
                    .findByExceptionRecordIdOrderByCreatedAtAsc(r.getId())) {
                User actor = l.getActorUserId() != null
                        ? userRepository.findById(l.getActorUserId()).orElse(null) : null;
                history.add(new ErmEscalationDtos.EventLogEntry(
                        l.getId(), l.getActorUserId(),
                        actor != null ? actor.getFullName() : null,
                        l.getEventType(), l.getPreviousStatus(), l.getNewStatus(),
                        l.getReasonCode(), l.getNote(), l.getCreatedAt()));
            }
        } catch (Exception ignored) {}

        int ageDays = r.getOpenedAt() == null ? 0
                : (int) Duration.between(r.getOpenedAt(), Instant.now()).toDays();

        return new ErmEscalationDtos.ExceptionDetail(
                r.getId(), r.getExceptionType(), r.getSeverity(), r.getStatus(),
                r.getSubjectUserId(),
                subject != null ? subject.getFullName() : null,
                subject != null ? subject.getEmail() : null,
                employeeId,
                r.getInternLifecycleId(),
                r.getAssignedToId(),
                assignee != null ? assignee.getFullName() : null,
                r.getAssignedById(),
                r.getResolvedById(),
                r.getResolutionReasonCode(),
                r.getResolutionNote(),
                r.getOpenedAt(),
                r.getLastSeenAt(),
                r.getAssignedAt(),
                r.getResolvedAt(),
                r.getSubjectResourceType(),
                r.getSubjectResourceId(),
                r.getPayloadJson(),
                ageDays,
                history);
    }

    private static String humanType(String type) {
        if (type == null) return "Exception";
        return type.replace('_', ' ').toLowerCase();
    }

    private static String placeholders(int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    private static UUID nullableUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private static Instant instantOf(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    @SuppressWarnings("unused")
    private static List<String> splitCsv(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList();
    }
}
