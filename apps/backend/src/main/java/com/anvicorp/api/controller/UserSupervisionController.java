package com.anvicorp.api.controller;

import com.anvicorp.api.dto.admin.PagedAuditLogResponse;
import com.anvicorp.api.dto.supervision.UserSupervisionResponse;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.AdminAuditLogService;
import com.anvicorp.api.service.UserAuditEntityResolver;
import com.anvicorp.api.service.UserSupervisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.Map;

/**
 * SUPER_ADMIN L3 — per-user consolidated supervision view + per-user audit feed.
 *
 * <h2>Role gate</h2>
 * {@code SUPER_ADMIN} only. OPERATIONS, HR, TECHNICAL_EVALUATOR,
 * EXECUTIVE, APPLICANT, INTERN all 403.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /supervision} — profile + role-contextual work + the
 *       headline activity strip.</li>
 *   <li>{@code GET /audit} — the COMPLETE per-user audit (actor OR subject),
 *       paginated, filterable by action / date range.</li>
 * </ul>
 *
 * <h2>Audit on view</h2>
 * Each successful GET writes a {@code SUPER_ADMIN_VIEWED_USER} audit row
 * (forensic record of who looked at whose data). Best-effort write — failure
 * logs and doesn't block the response. Page-flips on the audit endpoint
 * each write a new row; that's the intended forensic granularity.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class UserSupervisionController {

    private final UserSupervisionService userSupervisionService;
    private final AdminAuditLogService adminAuditLogService;
    private final UserAuditEntityResolver userAuditEntityResolver;

    @GetMapping("/{id}/supervision")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public UserSupervisionResponse supervision(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return userSupervisionService.build(id, caller);
    }

    /**
     * COMPLETE per-user audit feed — rows where the user is actor OR subject.
     * Paginated; filter by action and/or date range. Writes a
     * {@code SUPER_ADMIN_VIEWED_USER} meta-audit on every call (page-flips
     * included).
     */
    @GetMapping("/{id}/audit")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PagedAuditLogResponse userAudit(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @AuthenticationPrincipal User caller) {
        Map<String, Set<UUID>> buckets = userAuditEntityResolver.entityIdsForUser(id);
        PagedAuditLogResponse result = adminAuditLogService.userAudit(
                page, size, id, buckets, action, from, to);
        // Forensic record — who looked at whose audit log, when.
        userSupervisionService.writeAuditViewMeta(caller, id, "SUPER_ADMIN_VIEWED_USER");
        return result;
    }
}
