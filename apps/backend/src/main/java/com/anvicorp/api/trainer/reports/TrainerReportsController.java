package com.anvicorp.api.trainer.reports;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.trainer.reports.TrainerReportsDtos.FilterOptions;
import com.anvicorp.api.trainer.reports.TrainerReportsDtos.MonthlyProgressReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;

/** Trainer Phase 4 — Reports HTTP surface (JSON + CSV stream). */
@RestController
@RequestMapping("/api/v1/trainer/reports")
@RequiredArgsConstructor
@Slf4j
public class TrainerReportsController {

    private final TrainerReportsService service;

    @GetMapping("/monthly-progress")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public MonthlyProgressReport monthlyProgress(
            @RequestParam(required = false) String monthYear,
            @AuthenticationPrincipal User caller) {
        return service.getMonthlyProgressReport(monthYear, caller);
    }

    @GetMapping("/monthly-progress.csv")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public ResponseEntity<StreamingResponseBody> monthlyProgressCsv(
            @RequestParam(required = false) String monthYear,
            @AuthenticationPrincipal User caller) {
        StreamingResponseBody body = out -> {
            try {
                service.exportCsv(monthYear, caller, out);
                out.flush();
            } catch (Exception e) {
                log.warn("[TrainerReportsCtl] CSV stream failed: {}", e.getMessage());
                throw e;
            }
        };
        String fileName = "monthly-progress-"
                + (monthYear != null && !monthYear.isBlank()
                    ? monthYear : LocalDate.now().toString())
                + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(body);
    }

    @GetMapping("/filters")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public FilterOptions filters(@AuthenticationPrincipal User caller) {
        return service.getFilterOptions(caller);
    }
}
