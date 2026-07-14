package com.anvicorp.api.manager.reports;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.manager.reports.ManagerReportsService.Format;
import com.anvicorp.api.manager.reports.ManagerReportsService.ReportType;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Phase 4B-2 — Manager Reports HTTP surface. Single download endpoint
 * that streams CSV. Role gate MANAGER/SUPER_ADMIN; SQL scope by
 * {@code manager_id == caller.id} is enforced inside each delegated
 * service (the data services this reuses are the SAME ones the
 * dashboards/roster/inactive view call), so reports cannot leak
 * cross-Manager rows or recompute aggregates differently from the UI.
 */
@RestController
@RequestMapping("/api/v1/manager/reports")
@RequiredArgsConstructor
public class ManagerReportsController {

    private final ManagerReportsService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestParam("type") String typeRaw,
            @RequestParam(required = false, name = "y") Integer year,
            @RequestParam(required = false, name = "m") Integer month,
            @RequestParam(required = false, defaultValue = "csv") String formatRaw,
            @AuthenticationPrincipal User caller) {
        ReportType type;
        try {
            type = ReportType.valueOf(typeRaw.toUpperCase().replace('-', '_'));
        } catch (Exception e) {
            throw new BadRequestException(
                    "Unknown report type: " + typeRaw
                            + " (operations-roster | team-workload | training | evaluation"
                            + " | compliance-exception | inactive)");
        }
        Format format;
        try {
            format = Format.valueOf(formatRaw.toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("Unsupported format: " + formatRaw);
        }
        if (year != null && (year < 1900 || year > 2999)) {
            throw new BadRequestException("y (year) out of range");
        }
        if (month != null && (month < 1 || month > 12)) {
            throw new BadRequestException("m (month) must be 1-12");
        }
        if ((year == null) ^ (month == null)) {
            throw new BadRequestException("y and m must be supplied together");
        }

        String fileName = service.suggestFilename(type, year, month);
        StreamingResponseBody body = out -> service.export(
                type, year, month, format, caller, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(body);
    }
}
