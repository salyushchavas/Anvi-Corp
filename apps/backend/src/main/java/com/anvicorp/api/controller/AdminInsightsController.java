package com.anvicorp.api.controller;

import com.anvicorp.api.dto.admin.AdminOverviewResponse;
import com.anvicorp.api.dto.admin.PagedAuditLogResponse;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.service.AdminAuditLogService;
import com.anvicorp.api.service.AdminOverviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Read-only ADMIN insights: platform overview + paged audit log viewer +
 * CSV export.
 *
 * <h2>Role gates</h2>
 * <ul>
 *   <li>{@code overview} — SUPER_ADMIN or MANAGER (leadership oversight).</li>
 *   <li>Audit-log endpoints (paged read, actions list, entity-types list,
 *       CSV export) — SUPER_ADMIN ONLY. MANAGER and ERM are intentionally
 *       denied the full log; ERM's compliance feed lives on its own
 *       dashboard.</li>
 * </ul>
 *
 * No PageImpl on the wire — see {@link PagedAuditLogResponse}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminInsightsController {

    private final AdminOverviewService adminOverviewService;
    private final AdminAuditLogService adminAuditLogService;

    @GetMapping("/overview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MANAGER')")
    public AdminOverviewResponse overview() {
        return adminOverviewService.build();
    }

    @GetMapping("/audit-log")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PagedAuditLogResponse auditLog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorSearch,
            @RequestParam(required = false) UserRole actorRole,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return adminAuditLogService.search(
                page, size, action, actorSearch, actorRole, entityType, from, to);
    }

    @GetMapping("/audit-log/actions")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<String> auditLogActions() {
        return adminAuditLogService.distinctActions();
    }

    /** Distinct entity-type values present in the audit log — filter dropdown. */
    @GetMapping("/audit-log/entity-types")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<String> auditLogEntityTypes() {
        return adminAuditLogService.distinctEntityTypes();
    }

    /**
     * CSV export of the audit log honouring the supplied filters. Writes a
     * meta-audit {@code AUDIT_LOG_EXPORTED} row capturing who exported,
     * when, and which filters they used. SUPER_ADMIN only.
     */
    @GetMapping(value = "/audit-log/export", produces = "text/csv")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<byte[]> auditLogExport(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorSearch,
            @RequestParam(required = false) UserRole actorRole,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @AuthenticationPrincipal User caller) {
        byte[] body = adminAuditLogService.exportCsv(
                action, actorSearch, actorRole, entityType, from, to, caller);
        String filename = "audit-log-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .withZone(ZoneOffset.UTC)
                        .format(Instant.now())
                + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=utf-8"));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(body.length);
        return new ResponseEntity<>(body, headers, 200);
    }
}
