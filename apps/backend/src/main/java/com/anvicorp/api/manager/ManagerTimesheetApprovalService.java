package com.anvicorp.api.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.supervised.RejectTimesheetRequest;
import com.anvicorp.api.dto.supervised.TimesheetResponse;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.service.TimesheetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manager Phase 3B — write side. Wraps the existing
 * {@link TimesheetService#approve(UUID, User)} and
 * {@link TimesheetService#reject(UUID, RejectTimesheetRequest, User)} with
 * a role-only gate: any MANAGER (and SUPER_ADMIN) can act. The original
 * per-manager {@code manager_id == caller} fence was dropped — Manager
 * is an organization-level oversight role in the canonical design, and
 * the rest of the Manager surfaces (overview, pipeline, onboarding,
 * risk-center, timesheet list) are already org-wide. Per-intern fencing
 * here had created a deadlock: newly-active interns whose
 * {@code manager_id} is null (manual-only assignment) had VERIFIED
 * timesheets nobody could approve.
 *
 * <p>The actual VERIFIED → APPROVED / REJECTED transition is delegated
 * to {@code TimesheetService} unchanged, preserving the single state
 * machine + approver-id wiring + (any future) notification path. An
 * audit row is still written before delegation so Manager actions
 * surface in the audit log.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerTimesheetApprovalService {

    private final TimesheetService timesheetService;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public TimesheetResponse approve(UUID timesheetId, User caller) {
        gate(timesheetId, caller, "approve");
        TimesheetResponse out = timesheetService.approve(timesheetId, caller);
        writeAudit(caller, timesheetId, "TIMESHEET_APPROVED_BY_MANAGER", null);
        return out;
    }

    @Transactional
    public TimesheetResponse reject(UUID timesheetId,
                                     RejectTimesheetRequest req,
                                     User caller) {
        gate(timesheetId, caller, "reject");
        TimesheetResponse out = timesheetService.reject(timesheetId, req, caller);
        writeAudit(caller, timesheetId, "TIMESHEET_REJECTED_BY_MANAGER",
                req != null ? req.getReason() : null);
        return out;
    }

    /**
     * Phase B2 — batch-approve a list of VERIFIED timesheets. Manager
     * scope is org-wide, so the caller can batch over any MANAGER's
     * verified timesheets (typically the rows the UI's filter just
     * returned). Wrong-state rows are skipped silently (returns a
     * per-id outcome map). Each row goes through the per-row service so
     * the state-machine gate + audit + AFTER_COMMIT notify chain run
     * identically to the single-action path.
     */
    @Transactional
    public java.util.Map<UUID, String> approveBatch(java.util.List<UUID> ids, User caller) {
        java.util.Map<UUID, String> out = new java.util.LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        for (UUID id : ids) {
            try {
                approve(id, caller);
                out.put(id, "APPROVED");
            } catch (ForbiddenException fe) {
                out.put(id, "FORBIDDEN");
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "FAILED";
                // Cap so the response doesn't carry pages of stack traces.
                out.put(id, msg.length() > 200 ? msg.substring(0, 200) : msg);
            }
        }
        return out;
    }

    // ── Role gate ────────────────────────────────────────────────────────

    /**
     * Role-only gate. Any MANAGER (or SUPER_ADMIN) may act. No per-intern
     * ownership fence — Manager is portfolio-wide. The {@code timesheetId}
     * + {@code action} parameters are retained so the signature can carry
     * extra context in the future (and so we can swap in stricter checks
     * without re-threading the call-sites if the policy ever changes).
     */
    private void gate(UUID timesheetId, User caller, String action) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        boolean superAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (superAdmin) return;
        if (caller.getRoles() == null
                || !caller.getRoles().contains(UserRole.MANAGER)) {
            throw new ForbiddenException("MANAGER role required to " + action + " a timesheet");
        }
    }

    // ── Audit ────────────────────────────────────────────────────────────

    private void writeAudit(User caller, UUID timesheetId,
                             String action, String reason) {
        try {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("timesheetId", timesheetId.toString());
            if (reason != null) after.put("reason", reason);
            AuditLog row = AuditLog.builder()
                    .userId(caller != null ? caller.getId() : null)
                    .entityType("Timesheet")
                    .entityId(timesheetId)
                    .action(action)
                    .afterJson(objectMapper.writeValueAsString(after))
                    .build();
            auditLogRepository.save(row);
        } catch (Exception e) {
            log.warn("[ManagerTimesheetApproval] audit write failed: {}", e.getMessage());
        }
    }
}
