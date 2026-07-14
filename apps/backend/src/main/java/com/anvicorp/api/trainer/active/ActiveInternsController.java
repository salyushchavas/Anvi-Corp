package com.anvicorp.api.trainer.active;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.erm.reports.CsvExporter;
import com.anvicorp.api.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Trainer Phase 1 — Active Interns HTTP surface. */
@RestController
@RequestMapping("/api/v1/trainer/active-interns")
@RequiredArgsConstructor
public class ActiveInternsController {

    private static final int SEARCH_MAX = 100;

    private final ActiveInternsService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ActiveInternsDtos.ActiveInternListPage list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false, name = "projectState") List<String> projectFilter,
            @RequestParam(required = false, name = "meetingState") List<String> meetingFilter,
            @RequestParam(required = false, name = "evaluationState") List<String> evaluationFilter,
            @RequestParam(required = false, name = "timesheetState") List<String> timesheetFilter,
            @RequestParam(required = false, name = "y") Integer year,
            @RequestParam(required = false, name = "m") Integer month,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        if (search != null && search.length() > SEARCH_MAX) {
            throw new BadRequestException("search cannot exceed " + SEARCH_MAX + " characters");
        }
        if (year != null && (year < 1900 || year > 2999)) {
            throw new BadRequestException("y (year) out of range");
        }
        if (month != null && (month < 1 || month > 12)) {
            throw new BadRequestException("m (month) must be 1-12");
        }
        return service.list(caller, search,
                projectFilter, meetingFilter, evaluationFilter, timesheetFilter,
                year, month, page, pageSize);
    }

    @GetMapping("/{lifecycleId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ActiveInternsDtos.ActiveInternDetail detail(
            @PathVariable UUID lifecycleId,
            @AuthenticationPrincipal User caller) {
        return service.getDetail(lifecycleId, caller);
    }

    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ResponseEntity<StreamingResponseBody> exportCsv(
            @RequestParam(required = false) String search,
            @AuthenticationPrincipal User caller) {
        StreamingResponseBody body = out -> {
            CsvExporter.writeBom(out);
            for (List<Object> row : service.exportCsvRows(caller, search)) {
                CsvExporter.writeRow(out, row);
            }
        };
        String fileName = "active-interns-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(body);
    }
}
