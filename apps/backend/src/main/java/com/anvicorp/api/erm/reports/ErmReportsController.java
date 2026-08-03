package com.anvicorp.api.erm.reports;

import com.anvicorp.api.common.MonthRange;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.erm.reports.ErmReportsDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.util.UUID;

/** ERM Phase 7 — Reports HTTP surface. */
@RestController
@RequestMapping("/api/v1/erm/reports")
@RequiredArgsConstructor
public class ErmReportsController {

    private final ErmReportsService service;

    @GetMapping("/pipeline-funnel")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public PipelineFunnelData pipelineFunnel(
            @ModelAttribute("filters") FiltersForm f,
            @AuthenticationPrincipal User caller) {
        return service.pipelineFunnel(f.toFilters(), caller);
    }

    @GetMapping("/time-to-hire")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public TimeToHireData timeToHire(
            @ModelAttribute("filters") FiltersForm f,
            @AuthenticationPrincipal User caller) {
        return service.timeToHire(f.toFilters(), caller);
    }

    @GetMapping("/decision-funnel")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DecisionFunnelData decisionFunnel(
            @ModelAttribute("filters") FiltersForm f,
            @AuthenticationPrincipal User caller) {
        return service.decisionFunnel(f.toFilters(), caller);
    }

    @GetMapping("/completion-rate")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public CompletionRateData completionRate(
            @ModelAttribute("filters") FiltersForm f,
            @AuthenticationPrincipal User caller) {
        return service.completionRate(f.toFilters(), caller);
    }

    @GetMapping("/attrition")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public AttritionData attrition(
            @ModelAttribute("filters") FiltersForm f,
            @AuthenticationPrincipal User caller) {
        return service.attrition(f.toFilters(), caller);
    }

    @GetMapping("/evaluation-distribution")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public EvaluationDistributionData evaluationDistribution(
            @ModelAttribute("filters") FiltersForm f,
            @AuthenticationPrincipal User caller) {
        return service.evaluationDistribution(f.toFilters(), caller);
    }

    @GetMapping("/timesheet-compliance")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public TimesheetComplianceData timesheetCompliance(
            @ModelAttribute("filters") FiltersForm f,
            @AuthenticationPrincipal User caller) {
        return service.timesheetCompliance(f.toFilters(), caller);
    }

    @GetMapping("/{reportType}/csv")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<StreamingResponseBody> downloadCsv(
            @PathVariable String reportType,
            @ModelAttribute("filters") FiltersForm f,
            @AuthenticationPrincipal User caller) {
        StreamingResponseBody body = out -> service.exportCsv(
                reportType, f.toFilters(), caller, out);
        String fileName = reportType + "-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(body);
    }

    /** Form-style filter binder so query params map cleanly. */
    public static class FiltersForm {
        public LocalDate from;
        public LocalDate to;
        public String jobType;
        public UUID jobId;
        public UUID ermOwnerId;
        public UUID trainerId;
        public UUID evaluatorId;
        public UUID managerId;
        public String scope;
        /** {@code ?month=YYYY-MM} alias for from/to — when the caller
         *  supplies month AND either from or to is null, we translate
         *  month → firstDay..lastDayInclusive so the existing
         *  {@link ReportFilters} pipeline reads it as any other window. */
        public String month;

        public void setFrom(LocalDate from) { this.from = from; }
        public void setTo(LocalDate to) { this.to = to; }
        public void setJobType(String jobType) { this.jobType = jobType; }
        public void setJobId(UUID jobId) { this.jobId = jobId; }
        public void setErmOwnerId(UUID ermOwnerId) { this.ermOwnerId = ermOwnerId; }
        public void setTrainerId(UUID trainerId) { this.trainerId = trainerId; }
        public void setEvaluatorId(UUID evaluatorId) { this.evaluatorId = evaluatorId; }
        public void setManagerId(UUID managerId) { this.managerId = managerId; }
        public void setScope(String scope) { this.scope = scope; }
        public void setMonth(String month) { this.month = month; }

        ReportFilters toFilters() {
            LocalDate f = this.from;
            LocalDate t = this.to;
            if ((f == null || t == null) && month != null && !month.isBlank()) {
                MonthRange r = MonthRange.parse(month);
                if (f == null) f = r.monthStart();
                if (t == null) t = r.monthEnd().minusDays(1);
            }
            return new ReportFilters(f, t, jobType, jobId, ermOwnerId,
                    trainerId, evaluatorId, managerId, scope);
        }
    }
}
