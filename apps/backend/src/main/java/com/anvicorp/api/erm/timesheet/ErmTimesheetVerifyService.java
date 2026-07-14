package com.anvicorp.api.erm.timesheet;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase B2 — ERM-side verification, the middle stage of the two-stage
 * approval chain. Mirrors {@code ManagerTimesheetApprovalService}:
 * thin gate around {@link TimesheetService#verify}/{@link TimesheetService#reject}
 * + audit. ERM scope is org-wide — any ERM (or SUPER_ADMIN) may verify
 * or reject any intern's SUBMITTED week.
 *
 * <p>Per-intern routing happens upstream (the submit notification fires
 * to {@code lifecycle.erm_id} when set), but verification itself is not
 * gated on ownership of the lifecycle — keeps the queue shared so a
 * week never gets stuck behind one ERM's vacation.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmTimesheetVerifyService {

    private final TimesheetService timesheetService;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * Self-injected proxy reference so {@link #verifyBatch} can invoke
     * {@link #verify} VIA Spring's AOP proxy. A direct {@code verify(...)}
     * call from {@code verifyBatch} bypasses the proxy (self-invocation),
     * which means the inner method runs INSIDE the outer @Transactional —
     * any per-row RuntimeException marks the whole batch tx as
     * rollback-only and the commit throws UnexpectedRollbackException
     * (the 500 with framework-only stack frames the user saw on
     * traceId 9187e3be / 325d7764). Routing through the proxy with no
     * outer transaction means each row gets its OWN tx; a single
     * malformed row no longer poisons the batch.
     */
    @Autowired
    @Lazy
    private ErmTimesheetVerifyService self;

    @Transactional
    public TimesheetResponse verify(UUID timesheetId, User caller) {
        gate(caller, "verify");
        TimesheetResponse out = timesheetService.verify(timesheetId, caller);
        writeAudit(caller, timesheetId, "TIMESHEET_VERIFIED_BY_ERM", null);
        return out;
    }

    @Transactional
    public TimesheetResponse reject(UUID timesheetId, RejectTimesheetRequest req, User caller) {
        gate(caller, "reject");
        TimesheetResponse out = timesheetService.reject(timesheetId, req, caller);
        writeAudit(caller, timesheetId, "TIMESHEET_REJECTED_BY_ERM",
                req != null ? req.getReason() : null);
        return out;
    }

    /**
     * Batch-verify every SUBMITTED row in the supplied id list. Skips
     * wrong-state rows (returns a per-id outcome map). Each row passes
     * through {@link #verify} VIA the self-injected proxy so it runs in
     * its OWN transaction; a malformed/wrong-state row rolls back only
     * itself, the rest succeed, and the batch wrapper commits the
     * response cleanly. No outer @Transactional on the batch method —
     * that's the whole point.
     */
    public Map<UUID, String> verifyBatch(List<UUID> ids, User caller) {
        Map<UUID, String> out = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        for (UUID id : ids) {
            try {
                self.verify(id, caller);
                out.put(id, "VERIFIED");
            } catch (ForbiddenException fe) {
                out.put(id, "FORBIDDEN");
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "FAILED";
                out.put(id, msg.length() > 200 ? msg.substring(0, 200) : msg);
            }
        }
        return out;
    }

    private void gate(User caller, String action) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        boolean superAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (superAdmin) return;
        if (caller.getRoles() == null
                || !caller.getRoles().contains(UserRole.ERM)) {
            throw new ForbiddenException("ERM role required to " + action + " a timesheet");
        }
    }

    private void writeAudit(User caller, UUID timesheetId, String action, String reason) {
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
            log.warn("[ErmTimesheetVerify] audit write failed: {}", e.getMessage());
        }
    }
}
