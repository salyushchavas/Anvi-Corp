package com.anvicorp.api.erm.exit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.ExitChecklistItem;
import com.anvicorp.api.entity.ExitFeedback;
import com.anvicorp.api.entity.ExitRecord;
import com.anvicorp.api.entity.InternEvaluation;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.ReasonCode;
import com.anvicorp.api.event.ExitChecklistItemUpdatedEvent;
import com.anvicorp.api.event.ExitManagerOverrideEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.listener.GithubRevocationListener;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.ExitChecklistItemRepository;
import com.anvicorp.api.repository.ExitFeedbackRepository;
import com.anvicorp.api.repository.ExitRecordRepository;
import com.anvicorp.api.repository.InternEvaluationRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.ProjectAssignmentRepository;
import com.anvicorp.api.repository.TimesheetRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.anvicorp.api.erm.exit.ExitChecklistItemRegistry.*;

/**
 * ERM Phase 7 — Exit operations center. Wraps the Phase 8 intern
 * {@link com.anvicorp.api.service.ExitService} with the ERM-grade
 * checklist UI, manager override flow, link-final-evaluation, asset
 * status capture, retry-revocation, internal-note, and the
 * Ready-to-Exit detector list.
 *
 * <p>Intentionally delegates initiate to the existing intern ExitService
 * so all Phase 8 invariants (lifecycle flip, ExitInitiatedEvent,
 * audit) stay in one place. This service layers ERM operations on top.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmExitService {

    private static final int NOTE_MAX = 500;
    private static final int OVERRIDE_REASON_MIN = 30;

    private static final Set<String> VALID_STATUSES = Set.of(
            STATUS_PENDING, STATUS_COMPLETE, STATUS_NOT_APPLICABLE, STATUS_WAIVED);

    private final ExitRecordRepository exitRecordRepository;
    private final ExitChecklistItemRepository checklistRepository;
    private final ExitFeedbackRepository exitFeedbackRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final InternEvaluationRepository evaluationRepository;
    private final ProjectAssignmentRepository projectAssignmentRepository;
    private final TimesheetRepository timesheetRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final com.anvicorp.api.service.ExitService internExitService;
    private final GithubRevocationListener githubRevocationListener;
    private final ReadyToExitDetector readyDetector;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    // ── Reads ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ErmExitDtos.ErmExitListPage list(
            String state, String search, String scope, User caller,
            int page, int pageSize) {
        int p = Math.max(0, page);
        int ps = Math.min(100, Math.max(1, pageSize));

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();
        if ("mine".equalsIgnoreCase(scope) && caller != null) {
            where.append(" AND (il.erm_id IS NULL OR il.erm_id = ?) ");
            params.add(caller.getId());
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ?) ");
            String s = "%" + search.trim().toLowerCase() + "%";
            params.add(s); params.add(s);
        }
        // state filter is computed in Java since it depends on checklist counts
        // — but we can short-circuit at SQL for "CLOSED" via manager_override_at.

        long total;
        try {
            Long c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM exit_records er "
                            + "JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id "
                            + "JOIN users u ON u.id = er.intern_id" + where,
                    Long.class, params.toArray());
            total = c == null ? 0L : c;
        } catch (Exception e) {
            log.warn("[ErmExit] count failed: {}", e.getMessage());
            total = 0L;
        }

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(ps);
        pageParams.add(p * ps);
        List<ErmExitDtos.ErmExitRow> rows;
        try {
            rows = jdbc.query(
                    "SELECT er.id, er.intern_lifecycle_id, er.intern_id, "
                            + "       u.full_name, il.employee_id, er.exit_type, "
                            + "       er.exit_date, er.last_working_day, "
                            + "       er.manager_override_at, er.created_at "
                            + "  FROM exit_records er "
                            + "  JOIN intern_lifecycles il ON il.id = er.intern_lifecycle_id "
                            + "  JOIN users u ON u.id = er.intern_id "
                            + where
                            + " ORDER BY er.created_at DESC "
                            + " LIMIT ? OFFSET ?",
                    pageParams.toArray(),
                    (rs, n) -> mapRow(rs));
        } catch (Exception e) {
            log.warn("[ErmExit] page failed: {}", e.getMessage());
            rows = List.of();
        }

        // Java-side state filter — small page so this is fine.
        if (state != null && !state.isBlank() && !"all".equalsIgnoreCase(state)) {
            String want = state.trim().toUpperCase();
            rows = rows.stream().filter(r -> want.equals(r.overallState())).toList();
        }

        int totalPages = ps == 0 ? 0 : (int) Math.ceil((double) total / ps);
        return new ErmExitDtos.ErmExitListPage(rows, p, ps, total, totalPages);
    }

    private ErmExitDtos.ErmExitRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID exitId = uuid(rs.getString("id"));
        Map<String, Integer> counts = countByStatus(exitId);
        int total = ALL_ITEMS.size();
        int complete = counts.getOrDefault(STATUS_COMPLETE, 0);
        int pending = counts.getOrDefault(STATUS_PENDING, 0);
        int waived = counts.getOrDefault(STATUS_WAIVED, 0);
        int na = counts.getOrDefault(STATUS_NOT_APPLICABLE, 0);
        boolean overridden = rs.getTimestamp("manager_override_at") != null;
        String overall;
        if (overridden) overall = "CLOSED";
        else if (pending == 0) overall = "READY_TO_CLOSE";
        else overall = "ACTIVE";

        java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
        int daysSince = createdAt == null ? 0
                : (int) Duration.between(createdAt.toInstant(), Instant.now()).toDays();

        return new ErmExitDtos.ErmExitRow(
                exitId,
                uuid(rs.getString("intern_lifecycle_id")),
                uuid(rs.getString("intern_id")),
                rs.getString("full_name"),
                rs.getString("employee_id"),
                rs.getString("exit_type"),
                rs.getDate("exit_date") != null ? rs.getDate("exit_date").toLocalDate() : null,
                rs.getDate("last_working_day") != null
                        ? rs.getDate("last_working_day").toLocalDate() : null,
                total, complete, pending, waived, na,
                Math.max(0, daysSince),
                overridden,
                overall);
    }

    private Map<String, Integer> countByStatus(UUID exitRecordId) {
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT status, COUNT(*) AS cnt FROM exit_checklist_items "
                            + " WHERE exit_record_id = ? GROUP BY status",
                    exitRecordId);
            for (Map<String, Object> r : rows) {
                String s = String.valueOf(r.get("status"));
                int n = ((Number) r.getOrDefault("cnt", 0)).intValue();
                out.put(s, n);
            }
        } catch (Exception ignored) {}
        return out;
    }

    @Transactional(readOnly = true)
    public ErmExitDtos.ReadyToExitListPage listReady(
            String scope, User caller, int page, int pageSize) {
        int ps = Math.min(100, Math.max(1, pageSize));
        int p = Math.max(0, page);
        List<ReadyToExitDetector.Row> all = readyDetector.detect(
                scope, caller != null ? caller.getId() : null, 500);
        long total = all.size();
        int from = Math.min((int) total, p * ps);
        int to = Math.min((int) total, from + ps);
        List<ErmExitDtos.ReadyToExitRow> items = new ArrayList<>();
        for (var r : all.subList(from, to)) {
            items.add(new ErmExitDtos.ReadyToExitRow(
                    r.internLifecycleId(), r.internUserId(),
                    r.internName(), r.employeeId(),
                    r.daysActive(), r.suggestedExitType(), r.signals()));
        }
        int totalPages = ps == 0 ? 0 : (int) Math.ceil((double) total / ps);
        return new ErmExitDtos.ReadyToExitListPage(items, p, ps, total, totalPages);
    }

    @Transactional
    public ErmExitDtos.ErmExitDetail getDetail(UUID exitRecordId, User caller) {
        requireStaff(caller);
        ExitRecord r = exitRecordRepository.findById(exitRecordId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Exit record not found: " + exitRecordId));
        // Auto-flip read-time-computed items (outstanding timesheets / projects)
        autoFlipReadTimeItems(r);
        return toDetail(r, caller);
    }

    private void autoFlipReadTimeItems(ExitRecord r) {
        try {
            // OUTSTANDING_TIMESHEETS — COMPLETE iff no SUBMITTED/REJECTED rows
            // remain for this intern.
            Long openTs = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM timesheets t "
                            + " WHERE t.intern_id = (SELECT id FROM candidates WHERE user_id = ?) "
                            + "   AND t.status IN ('SUBMITTED','REJECTED')",
                    Long.class, r.getInternId());
            if (openTs != null && openTs == 0L) {
                internExitService.flipChecklistItem(r.getId(), OUTSTANDING_TIMESHEETS,
                        STATUS_COMPLETE, null,
                        "Auto-completed: no outstanding SUBMITTED/REJECTED timesheets.");
            }
            // OUTSTANDING_PROJECTS — COMPLETE iff no in-flight project_assignments.
            Long openProj = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM project_assignments "
                            + " WHERE intern_id = ? "
                            + "   AND status IN ('ASSIGNED','IN_PROGRESS','SUBMITTED','RETURNED')",
                    Long.class, r.getInternId());
            if (openProj != null && openProj == 0L) {
                internExitService.flipChecklistItem(r.getId(), OUTSTANDING_PROJECTS,
                        STATUS_COMPLETE, null,
                        "Auto-completed: no in-flight project assignments.");
            }
        } catch (Exception e) {
            log.debug("[ErmExit] read-time auto-flip failed (non-fatal): {}",
                    e.getMessage());
        }
    }

    // ── Writes ───────────────────────────────────────────────────────────

    @Transactional
    public ErmExitDtos.ErmExitDetail initiate(
            ErmExitDtos.InitiateExitRequest req, User caller) {
        requireStaff(caller);
        if (req == null) throw new BadRequestException("request body is required");
        ReasonCode rc = req.reasonCode() == null ? null
                : parseExitReason(req.reasonCode());

        // Delegate the heavy lifting to the existing intern Phase 8 service
        // (lifecycle flip + ExitInitiatedEvent + audit + checklist seed).
        var legacyReq = new com.anvicorp.api.dto.exit.ExitDtos.CreateExitRecordRequest(
                req.internLifecycleId(),
                req.exitType(),
                req.exitDate(),
                req.reasonText() != null ? req.reasonText() : "",
                req.internVisibleSummary(),
                req.rehireEligible());
        var resp = internExitService.initiate(legacyReq, caller);

        // Patch ERM-only columns the Phase 8 path doesn't know about.
        ExitRecord rec = exitRecordRepository.findById(resp.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ExitRecord not found after initiate"));
        if (rc != null) rec.setReasonCode(rc.name());
        if (req.lastWorkingDay() != null) rec.setLastWorkingDay(req.lastWorkingDay());
        if (req.finalTimesheetStatus() != null) {
            rec.setFinalTimesheetStatus(req.finalTimesheetStatus().trim().toUpperCase());
        }
        rec = exitRecordRepository.save(rec);

        Map<String, Object> exitAfter = new LinkedHashMap<>();
        exitAfter.put("reasonCode", rc != null ? rc.name() : null);
        exitAfter.put("exitType", rec.getExitType());
        exitAfter.put("exitDate", rec.getExitDate() != null ? rec.getExitDate().toString() : null);
        writeAudit(rec.getId(), "EXIT_ERM_INITIATE",
                caller.getId(), rec.getInternId(),
                null,
                exitAfter);
        return toDetail(rec, caller);
    }

    @Transactional
    public ErmExitDtos.ErmExitDetail updateChecklistItem(
            UUID exitRecordId, String itemKey,
            ErmExitDtos.ChecklistUpdateRequest req, User caller) {
        requireStaff(caller);
        if (req == null || req.status() == null) {
            throw new BadRequestException("status is required");
        }
        String newStatus = req.status().trim().toUpperCase();
        if (!VALID_STATUSES.contains(newStatus)) {
            throw new BadRequestException(
                    "status must be one of " + VALID_STATUSES);
        }
        if (!ALL_ITEMS.contains(itemKey)) {
            throw new BadRequestException("Unknown checklist itemKey: " + itemKey);
        }
        if (req.note() != null && req.note().length() > NOTE_MAX) {
            throw new BadRequestException(
                    "note cannot exceed " + NOTE_MAX + " characters");
        }
        ExitRecord rec = mustLoad(exitRecordId);
        ExitChecklistItem item = checklistRepository
                .findByExitRecordIdAndItemKey(exitRecordId, itemKey)
                .orElseGet(() -> ExitChecklistItem.builder()
                        .exitRecordId(exitRecordId)
                        .itemKey(itemKey)
                        .status(STATUS_PENDING)
                        .build());
        String previousStatus = item.getStatus();
        item.setStatus(newStatus);
        if (STATUS_COMPLETE.equals(newStatus)) {
            item.setCompletedAt(Instant.now());
            item.setCompletedById(caller.getId());
        } else if (STATUS_PENDING.equals(newStatus)) {
            item.setCompletedAt(null);
            item.setCompletedById(null);
        }
        if (req.note() != null && !req.note().isBlank()) item.setNote(req.note().trim());
        ExitChecklistItem saved = checklistRepository.save(item);

        // If DOCUMENTS_ARCHIVED flips COMPLETE, mirror into the legacy
        // exit_records.final_documents_archived flag so older readers see it.
        if (DOCUMENTS_ARCHIVED.equals(itemKey) && STATUS_COMPLETE.equals(newStatus)) {
            rec.setFinalDocumentsArchived(true);
            rec.setFinalDocumentsArchivedAt(Instant.now());
            exitRecordRepository.save(rec);
        }

        writeAudit(exitRecordId, "EXIT_CHECKLIST_UPDATE",
                caller.getId(), rec.getInternId(),
                Map.of("itemKey", itemKey, "status", previousStatus),
                Map.of("itemKey", itemKey, "status", newStatus));
        try {
            eventPublisher.publishEvent(new ExitChecklistItemUpdatedEvent(
                    exitRecordId, saved.getId(), itemKey,
                    previousStatus, newStatus, caller.getId()));
        } catch (Exception e) {
            log.debug("[ErmExit] checklist event publish failed: {}", e.getMessage());
        }
        return toDetail(rec, caller);
    }

    @Transactional
    public ErmExitDtos.ErmExitDetail linkFinalEvaluation(
            UUID exitRecordId, ErmExitDtos.LinkEvaluationRequest req, User caller) {
        requireStaff(caller);
        if (req == null || req.evaluationId() == null) {
            throw new BadRequestException("evaluationId is required");
        }
        ExitRecord rec = mustLoad(exitRecordId);
        InternEvaluation eval = evaluationRepository.findById(req.evaluationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Evaluation not found: " + req.evaluationId()));
        if (!rec.getInternLifecycleId().equals(eval.getInternLifecycleId())) {
            throw new ConflictException(
                    "Evaluation belongs to a different intern lifecycle");
        }
        if (!"FINAL".equals(eval.getEvaluationType())) {
            throw new ConflictException(
                    "Evaluation type must be FINAL (got " + eval.getEvaluationType() + ")");
        }
        if (!Set.of("PUBLISHED", "ACKNOWLEDGED", "AMENDED").contains(eval.getStatus())) {
            throw new ConflictException(
                    "Evaluation must be PUBLISHED to link (status: " + eval.getStatus() + ")");
        }
        rec.setFinalEvaluationId(eval.getId());
        exitRecordRepository.save(rec);
        internExitService.flipChecklistItem(exitRecordId, FINAL_EVALUATION,
                STATUS_COMPLETE, caller.getId(),
                "Linked FINAL evaluation " + eval.getId());
        writeAudit(exitRecordId, "EXIT_LINK_FINAL_EVALUATION",
                caller.getId(), rec.getInternId(),
                null, Map.of("finalEvaluationId", eval.getId().toString()));
        return toDetail(rec, caller);
    }

    @Transactional
    public ErmExitDtos.ErmExitDetail recordAssetStatus(
            UUID exitRecordId, ErmExitDtos.AssetStatusRequest req, User caller) {
        requireStaff(caller);
        if (req == null || req.status() == null) {
            throw new BadRequestException("status payload is required");
        }
        ExitRecord rec = mustLoad(exitRecordId);
        try {
            rec.setAssetStatusJson(objectMapper.writeValueAsString(req.status()));
        } catch (Exception e) {
            throw new BadRequestException("Asset status serialization failed: " + e.getMessage());
        }
        exitRecordRepository.save(rec);

        // If all hardware items returned OR explicitly marked NA, flip the
        // ASSETS_RETURNED checklist item to COMPLETE.
        var s = req.status();
        boolean allHandled = isHandled(s.laptopReturned())
                && isHandled(s.badgeReturned())
                && isHandled(s.buildingAccessRemoved())
                && isHandled(s.parkingPassReturned())
                && isHandled(s.keysReturned());
        if (allHandled) {
            internExitService.flipChecklistItem(exitRecordId, ASSETS_RETURNED,
                    STATUS_COMPLETE, caller.getId(),
                    "Auto-completed: all asset rows checked off.");
        }
        writeAudit(exitRecordId, "EXIT_ASSETS_RECORDED",
                caller.getId(), rec.getInternId(), null,
                Map.of("allHandled", allHandled));
        return toDetail(rec, caller);
    }

    private static boolean isHandled(Boolean b) {
        // null = pending; false = returned/removed status not satisfied;
        // true = handled. To match the UI semantics ("Yes, returned" = true),
        // we treat true as handled and null/false as outstanding.
        return Boolean.TRUE.equals(b);
    }

    @Transactional
    public ErmExitDtos.ErmExitDetail managerOverride(
            UUID exitRecordId, ErmExitDtos.ManagerOverrideRequest req, User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (req == null || req.reasonCode() == null) {
            throw new BadRequestException("reasonCode is required");
        }
        ReasonCode rc;
        try { rc = ReasonCode.valueOf(req.reasonCode().trim()); }
        catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown reasonCode: " + req.reasonCode());
        }
        if (rc.category() != ReasonCode.Category.EXIT_OVERRIDE) {
            throw new BadRequestException(
                    "reasonCode must be in EXIT_OVERRIDE family");
        }
        if (req.reasonText() == null
                || req.reasonText().trim().length() < OVERRIDE_REASON_MIN) {
            throw new BadRequestException(
                    "reasonText must be at least " + OVERRIDE_REASON_MIN + " characters");
        }
        ExitRecord rec = mustLoad(exitRecordId);
        // Authorisation: SUPER_ADMIN always allowed; MANAGER must be the
        // manager of this lifecycle.
        boolean isSuper = caller.getRoles().contains(UserRole.SUPER_ADMIN);
        boolean isManagerForLifecycle = false;
        if (!isSuper) {
            InternLifecycle lc = lifecycleRepository.findById(rec.getInternLifecycleId())
                    .orElse(null);
            isManagerForLifecycle = lc != null
                    && caller.getId().equals(lc.getManagerId())
                    && caller.getRoles().contains(UserRole.MANAGER);
        }
        if (!isSuper && !isManagerForLifecycle) {
            throw new ForbiddenException(
                    "Only the lifecycle's MANAGER or SUPER_ADMIN may override");
        }
        if (rec.getManagerOverrideAt() != null) {
            throw new ConflictException("Exit has already been manager-overridden");
        }
        Instant now = Instant.now();
        rec.setManagerOverrideId(caller.getId());
        rec.setManagerOverrideReason(req.reasonText().trim());
        rec.setManagerOverrideAt(now);
        exitRecordRepository.save(rec);

        int waived = 0;
        for (ExitChecklistItem it : checklistRepository
                .findByExitRecordIdOrderByItemKeyAsc(exitRecordId)) {
            if (!STATUS_PENDING.equals(it.getStatus())) continue;
            it.setStatus(STATUS_WAIVED);
            it.setCompletedAt(now);
            it.setCompletedById(caller.getId());
            it.setNote(("Manager override: " + req.reasonText().trim()));
            checklistRepository.save(it);
            waived++;
        }
        writeAudit(exitRecordId, "EXIT_MANAGER_OVERRIDE",
                caller.getId(), rec.getInternId(),
                null,
                Map.of("reasonCode", rc.name(),
                        "itemsWaived", waived));
        try {
            eventPublisher.publishEvent(new ExitManagerOverrideEvent(
                    rec.getId(), rec.getInternLifecycleId(),
                    rec.getInternId(), caller.getId(),
                    rc.name(), waived));
        } catch (Exception e) {
            log.debug("[ErmExit] override event publish failed: {}", e.getMessage());
        }
        log.info("[ErmExit] manager override on record {} — {} items waived by {}",
                exitRecordId, waived, caller.getId());
        return toDetail(rec, caller);
    }

    @Transactional
    public ErmExitDtos.ErmExitDetail retryRevocation(UUID exitRecordId, User caller) {
        requireStaff(caller);
        ExitRecord rec = mustLoad(exitRecordId);
        try {
            githubRevocationListener.onExitInitiated(
                    new com.anvicorp.api.event.ExitInitiatedEvent(
                            rec.getId(), rec.getInternLifecycleId(),
                            rec.getInternId(), caller.getId(),
                            rec.getExitType(), rec.getExitDate()));
        } catch (Exception e) {
            log.warn("[ErmExit] retry revocation failed: {}", e.getMessage());
        }
        writeAudit(exitRecordId, "EXIT_RETRY_REVOCATION",
                caller.getId(), rec.getInternId(), null, null);
        return toDetail(rec, caller);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ErmExitDtos.FeedbackView> getFeedback(
            UUID exitRecordId, User caller) {
        requireStaff(caller);
        ExitRecord rec = mustLoad(exitRecordId);
        return exitFeedbackRepository.findByExitRecordId(rec.getId())
                .map(fb -> new ErmExitDtos.FeedbackView(
                        fb.getId(), fb.getExitRecordId(), fb.getInternId(),
                        fb.getOverallRating(), fb.getLearningRating(),
                        fb.getMentorshipRating(), fb.getWorkEnvironmentRating(),
                        fb.getWhatWentWell(), fb.getWhatCouldImprove(),
                        fb.getWouldRecommend(), fb.getAdditionalComments(),
                        fb.getSubmittedAt()));
    }

    @Transactional
    public void appendInternalNote(UUID exitRecordId, String note, User caller) {
        requireStaff(caller);
        if (note == null || note.trim().length() < 5) {
            throw new BadRequestException(
                    "note must be at least 5 characters");
        }
        ExitRecord rec = mustLoad(exitRecordId);
        String existing = rec.getInternalNotes() == null ? "" : rec.getInternalNotes();
        String stamp = Instant.now().toString();
        String composed = existing.isEmpty()
                ? "[" + stamp + " " + caller.getId() + "] " + note.trim()
                : existing + "\n[" + stamp + " " + caller.getId() + "] " + note.trim();
        rec.setInternalNotes(composed);
        exitRecordRepository.save(rec);
        writeAudit(exitRecordId, "EXIT_INTERNAL_NOTE_ADDED",
                caller.getId(), rec.getInternId(), null, null);
    }

    // ── Reason codes ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ErmExitDtos.ReasonCodeGroup> listReasonCodes() {
        var cats = EnumSet.of(
                ReasonCode.Category.EXIT,
                ReasonCode.Category.EXIT_OVERRIDE,
                ReasonCode.Category.ASSET);
        Map<ReasonCode.Category, List<ErmExitDtos.ReasonCodeOption>> bucket =
                new LinkedHashMap<>();
        for (ReasonCode rc : ReasonCode.values()) {
            if (!cats.contains(rc.category())) continue;
            bucket.computeIfAbsent(rc.category(), k -> new ArrayList<>())
                    .add(new ErmExitDtos.ReasonCodeOption(
                            rc.name(), rc.humanLabel(), rc.requiresFreeText()));
        }
        List<ErmExitDtos.ReasonCodeGroup> out = new ArrayList<>();
        for (var e : bucket.entrySet()) {
            out.add(new ErmExitDtos.ReasonCodeGroup(e.getKey().name(), e.getValue()));
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private ExitRecord mustLoad(UUID exitRecordId) {
        return exitRecordRepository.findById(exitRecordId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Exit record not found: " + exitRecordId));
    }

    private void requireStaff(User caller) {
        if (caller == null) throw new ForbiddenException("Caller required");
        if (!caller.getRoles().contains(UserRole.ERM)
                && !caller.getRoles().contains(UserRole.SUPER_ADMIN)) {
            throw new ForbiddenException("ERM or SUPER_ADMIN required");
        }
    }

    private ReasonCode parseExitReason(String code) {
        ReasonCode rc;
        try { rc = ReasonCode.valueOf(code.trim()); }
        catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown reasonCode: " + code);
        }
        if (rc.category() != ReasonCode.Category.EXIT) {
            throw new BadRequestException("reasonCode must be in EXIT family");
        }
        return rc;
    }

    private void writeAudit(UUID entityId, String action,
                             UUID actorId, UUID subjectUserId,
                             Map<String, Object> before, Map<String, Object> after) {
        try {
            AuditLog row = AuditLog.builder()
                    .userId(actorId)
                    .subjectUserId(subjectUserId)
                    .entityType("ExitRecord")
                    .entityId(entityId)
                    .action(action)
                    .beforeJson(before != null ? objectMapper.writeValueAsString(before) : null)
                    .afterJson(after != null ? objectMapper.writeValueAsString(after) : null)
                    .build();
            auditLogRepository.save(row);
        } catch (Exception e) {
            log.warn("[ErmExit] audit write failed: {}", e.getMessage());
        }
    }

    private ErmExitDtos.ErmExitDetail toDetail(ExitRecord r, User caller) {
        User intern = userRepository.findById(r.getInternId()).orElse(null);
        InternLifecycle lc = lifecycleRepository.findById(r.getInternLifecycleId()).orElse(null);
        User initiator = userRepository.findById(r.getInitiatedById()).orElse(null);

        List<ErmExitDtos.ChecklistItemRow> checklist = new ArrayList<>();
        try {
            for (ExitChecklistItem it : checklistRepository
                    .findByExitRecordIdOrderByItemKeyAsc(r.getId())) {
                User by = it.getCompletedById() != null
                        ? userRepository.findById(it.getCompletedById()).orElse(null) : null;
                checklist.add(new ErmExitDtos.ChecklistItemRow(
                        it.getId(), it.getItemKey(), it.getStatus(),
                        it.getCompletedAt(), it.getCompletedById(),
                        by != null ? by.getFullName() : null,
                        it.getNote(),
                        it.getUpdatedAt()));
            }
        } catch (Exception ignored) {}

        boolean ready = checklist.stream().noneMatch(
                ci -> STATUS_PENDING.equals(ci.status()));
        boolean feedback = exitFeedbackRepository.existsByExitRecordId(r.getId());
        int days = r.getCreatedAt() == null ? 0
                : (int) Duration.between(r.getCreatedAt(), Instant.now()).toDays();

        return new ErmExitDtos.ErmExitDetail(
                r.getId(),
                r.getInternLifecycleId(),
                r.getInternId(),
                intern != null ? intern.getFullName() : null,
                intern != null ? intern.getEmail() : null,
                lc != null ? lc.getEmployeeId() : null,
                r.getExitType(),
                r.getExitDate(),
                r.getLastWorkingDay(),
                r.getReasonCode(),
                r.getExitReason(),
                r.getInternVisibleSummary(),
                r.getInternalNotes(),
                r.getAssetStatusJson(),
                r.getFinalTimesheetStatus(),
                r.getRehireEligible(),
                r.getAccessRevocationDone(),
                r.getAccessRevocationSummary(),
                r.getAccessRevocationCompletedAt(),
                r.getFinalDocumentsArchived(),
                r.getFinalDocumentsArchivedAt(),
                r.getFinalEvaluationId(),
                r.getManagerOverrideId(),
                r.getManagerOverrideReason(),
                r.getManagerOverrideAt(),
                r.getInitiatedById(),
                initiator != null ? initiator.getFullName() : null,
                r.getCreatedAt(),
                r.getUpdatedAt(),
                Math.max(0, days),
                checklist,
                ready,
                feedback);
    }

    private static UUID uuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    // Suppress unused-import warnings while keeping repo deps available
    // for later expansion (project + timesheet repositories are referenced
    // via JdbcTemplate currently).
    @SuppressWarnings("unused")
    private void unused() {
        var a = projectAssignmentRepository.findAll();
        var b = timesheetRepository.findAll();
        var c = (ExitFeedback) null;
        if (a == null || b == null || c == null) return;
    }
}
