package com.anvicorp.api.manager.reports;

import com.anvicorp.api.dto.supervised.TimesheetDayResponse;
import com.anvicorp.api.dto.supervised.TimesheetMonthRollupResponse;
import com.anvicorp.api.entity.InternEvaluation;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.reports.CsvExporter;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.manager.ManagerDtos;
import com.anvicorp.api.manager.ManagerRiskCenterService;
import com.anvicorp.api.manager.inactive.ManagerInactiveInternsDtos;
import com.anvicorp.api.manager.inactive.ManagerInactiveInternsService;
import com.anvicorp.api.repository.InternEvaluationRepository;
import com.anvicorp.api.service.timesheet.TimesheetRollupService;
import com.anvicorp.api.trainer.active.ActiveInternsDtos;
import com.anvicorp.api.trainer.active.ActiveInternsService;
import com.anvicorp.api.trainer.active.ActiveInternsService.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 4B-2 — Manager Reports. ONE export entry-point that delegates
 * to the existing data services per report type so the numbers in the
 * exported file reconcile exactly with what the Manager sees on screen:
 *
 * <ul>
 *   <li><b>operations-roster</b> → {@link ActiveInternsService} with
 *       {@code Scope.MANAGER_OWNED} (same hydration as the Phase C
 *       Manager Active Interns page).</li>
 *   <li><b>team-workload</b> → {@link TimesheetRollupService} with
 *       {@code Scope.MANAGER} (same per-week rollup as the Phase B2
 *       Manager Timesheet Approvals page).</li>
 *   <li><b>training</b> → {@link ActiveInternsService} (same roster,
 *       different column projection — project + KT status only).</li>
 *   <li><b>evaluation</b> → {@link ActiveInternsService} (per-row
 *       evaluation block) + {@link InternEvaluationRepository} for
 *       per-eval detail rows.</li>
 *   <li><b>compliance-exception</b> → {@link ManagerRiskCenterService}
 *       (same Risk Center query the Manager Risk page uses).</li>
 *   <li><b>inactive</b> → {@link ManagerInactiveInternsService} (same
 *       4B-1 closure aggregates).</li>
 * </ul>
 *
 * <p>No parallel aggregation. The only thing this service does on top
 * of the source services is project the result into a CSV writer.
 * Format support: CSV only in this phase — there's no Apache POI / PDF
 * dependency in the project and adding one is out of scope.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerReportsService {

    public enum ReportType {
        OPERATIONS_ROSTER,
        TEAM_WORKLOAD,
        TRAINING,
        EVALUATION,
        COMPLIANCE_EXCEPTION,
        INACTIVE
    }

    public enum Format { CSV }

    private final ActiveInternsService activeInternsService;
    private final TimesheetRollupService timesheetRollupService;
    private final ManagerInactiveInternsService inactiveService;
    private final ManagerRiskCenterService riskCenterService;
    private final InternEvaluationRepository evaluationRepository;

    /**
     * Generate a report. Streams CSV bytes into {@code out}. Period is
     * required for everything except {@code INACTIVE}, which allows
     * "all time" (year/month null).
     */
    @Transactional(readOnly = true)
    public void export(ReportType type, Integer year, Integer month, Format format,
                       User caller, OutputStream out) throws IOException {
        if (caller == null) throw new ForbiddenException("Authentication required");
        requireManagerOrSuperAdmin(caller);
        if (format != Format.CSV) {
            throw new BadRequestException("Only CSV format is supported (XLSX/PDF deferred)");
        }
        boolean periodRequired = type != ReportType.INACTIVE;
        if (periodRequired && (year == null || month == null)) {
            throw new BadRequestException("y + m are required for report type " + type);
        }
        YearMonth period = (year != null && month != null) ? YearMonth.of(year, month) : null;

        CsvExporter.writeBom(out);
        writePreamble(out, type, period, caller);

        switch (type) {
            case OPERATIONS_ROSTER     -> writeOperationsRoster(out, period, caller);
            case TEAM_WORKLOAD         -> writeTeamWorkload(out, period, caller);
            case TRAINING              -> writeTraining(out, period, caller);
            case EVALUATION            -> writeEvaluation(out, period, caller);
            case COMPLIANCE_EXCEPTION  -> writeCompliance(out, caller);
            case INACTIVE              -> writeInactive(out, year, month, caller);
        }
    }

    /** File name component — kept short, safe for browser downloads. */
    public String suggestFilename(ReportType type, Integer year, Integer month) {
        String periodPart = (year != null && month != null)
                ? "-" + String.format("%04d-%02d", year, month)
                : "-all-time";
        return "manager-" + type.name().toLowerCase().replace('_', '-')
                + periodPart + "-" + java.time.LocalDate.now() + ".csv";
    }

    // ── Per-type writers ─────────────────────────────────────────────────

    private void writeOperationsRoster(OutputStream out, YearMonth period, User caller)
            throws IOException {
        ActiveInternsDtos.ActiveInternListPage page = activeInternsService.list(
                caller, null, null, null, null, null,
                period.getYear(), period.getMonthValue(),
                0, 500, Scope.MANAGER_OWNED);
        CsvExporter.writeRow(out, List.of(
                "Employee ID", "Full name", "Email", "Technology",
                "Start date", "Days active",
                "Project (overall)", "Project 1", "Project 2",
                "KT P1", "KT P2",
                "Evaluation", "Timesheet",
                "Submitted weeks", "Verified weeks", "Approved weeks",
                "Rejected weeks", "Missing weeks", "Expected weeks",
                "Trainer", "Evaluator", "Manager", "ERM"
        ));
        if (page.items().isEmpty()) {
            writeNoDataRow(out, 23);
            return;
        }
        for (ActiveInternsDtos.ActiveInternRow r : page.items()) {
            ActiveInternsDtos.CurrentMonthProjectsBlock pr = r.currentMonthProjects();
            ActiveInternsDtos.ProjectSlot p1 = pr != null ? pr.project1() : null;
            ActiveInternsDtos.ProjectSlot p2 = pr != null ? pr.project2() : null;
            ActiveInternsDtos.ReportingStructure rs = r.reportingStructure();
            ActiveInternsDtos.TimesheetStateBlock ts = r.timesheet();
            CsvExporter.writeRow(out, java.util.Arrays.asList(
                    nz(r.employeeId()), nz(r.fullName()), nz(r.email()),
                    nz(r.technologyTitle()),
                    r.startDate() != null ? r.startDate().toString() : "",
                    r.daysActive() != null ? r.daysActive().toString() : "0",
                    pr != null ? pr.overallState() : "NO_PROJECTS",
                    p1 != null ? slotLabel(p1) : "",
                    p2 != null ? slotLabel(p2) : "",
                    p1 != null ? nz(p1.ktStatus()) : "",
                    p2 != null ? nz(p2.ktStatus()) : "",
                    r.evaluation() != null ? nz(r.evaluation().state()) : "NONE",
                    ts != null ? nz(ts.state()) : "MISSING",
                    ts != null ? String.valueOf(ts.submittedCount()) : "0",
                    ts != null ? String.valueOf(ts.verifiedCount()) : "0",
                    ts != null ? String.valueOf(ts.approvedCount()) : "0",
                    ts != null ? String.valueOf(ts.rejectedCount()) : "0",
                    ts != null ? String.valueOf(ts.missingCount()) : "0",
                    ts != null ? String.valueOf(ts.expectedWeeks()) : "0",
                    rs != null ? nz(rs.trainerName()) : "",
                    rs != null ? nz(rs.evaluatorName()) : "",
                    rs != null ? nz(rs.managerName()) : "",
                    rs != null ? nz(rs.ermName()) : ""
            ));
        }
    }

    private void writeTeamWorkload(OutputStream out, YearMonth period, User caller)
            throws IOException {
        TimesheetMonthRollupResponse roll = timesheetRollupService.getRollup(
                TimesheetRollupService.Scope.MANAGER, period, caller);
        List<TimesheetMonthRollupResponse.MonthColumn> cols = roll.columns();

        List<Object> header = new ArrayList<>();
        header.add("Employee ID");
        header.add("Full name");
        header.add("Manager");
        for (TimesheetMonthRollupResponse.MonthColumn c : cols) {
            header.add("W" + c.weekNumber() + " (" + c.weekStart() + ") hours");
            header.add("W" + c.weekNumber() + " status");
        }
        header.add("Month total hours");
        CsvExporter.writeRow(out, header);

        if (roll.interns().isEmpty()) {
            writeNoDataRow(out, header.size());
            return;
        }
        for (TimesheetMonthRollupResponse.InternRow ir : roll.interns()) {
            List<Object> row = new ArrayList<>();
            row.add(nz(ir.employeeId()));
            row.add(nz(ir.fullName()));
            row.add(nz(ir.managerName()));
            // Index by weekStart so we never depend on cell ordering.
            for (TimesheetMonthRollupResponse.MonthColumn c : cols) {
                TimesheetMonthRollupResponse.WeekCell cell = ir.weeks().stream()
                        .filter(w -> w.weekStart().equals(c.weekStart()))
                        .findFirst().orElse(null);
                if (cell == null) {
                    row.add("0.00");
                    row.add("MISSING");
                } else {
                    row.add(cell.totalHours() != null
                            ? cell.totalHours().toPlainString() : "0.00");
                    row.add(cell.status() != null ? cell.status().name() : "MISSING");
                }
            }
            row.add(ir.monthTotalHours() != null
                    ? ir.monthTotalHours().toPlainString() : "0.00");
            CsvExporter.writeRow(out, row);
        }
    }

    private void writeTraining(OutputStream out, YearMonth period, User caller)
            throws IOException {
        ActiveInternsDtos.ActiveInternListPage page = activeInternsService.list(
                caller, null, null, null, null, null,
                period.getYear(), period.getMonthValue(),
                0, 500, Scope.MANAGER_OWNED);
        CsvExporter.writeRow(out, List.of(
                "Employee ID", "Full name", "Technology",
                "P1 title", "P1 status", "P1 due", "P1 KT", "P1 KT completed",
                "P2 title", "P2 status", "P2 due", "P2 KT", "P2 KT completed"
        ));
        if (page.items().isEmpty()) {
            writeNoDataRow(out, 13);
            return;
        }
        for (ActiveInternsDtos.ActiveInternRow r : page.items()) {
            ActiveInternsDtos.CurrentMonthProjectsBlock pr = r.currentMonthProjects();
            ActiveInternsDtos.ProjectSlot p1 = pr != null ? pr.project1() : null;
            ActiveInternsDtos.ProjectSlot p2 = pr != null ? pr.project2() : null;
            CsvExporter.writeRow(out, java.util.Arrays.asList(
                    nz(r.employeeId()), nz(r.fullName()), nz(r.technologyTitle()),
                    p1 != null ? nz(p1.title()) : "",
                    p1 != null ? nz(p1.status()) : "",
                    p1 != null && p1.dueDate() != null ? p1.dueDate().toString() : "",
                    p1 != null ? nz(p1.ktStatus()) : "",
                    p1 != null && p1.ktCompletedAt() != null ? p1.ktCompletedAt().toString() : "",
                    p2 != null ? nz(p2.title()) : "",
                    p2 != null ? nz(p2.status()) : "",
                    p2 != null && p2.dueDate() != null ? p2.dueDate().toString() : "",
                    p2 != null ? nz(p2.ktStatus()) : "",
                    p2 != null && p2.ktCompletedAt() != null ? p2.ktCompletedAt().toString() : ""
            ));
        }
    }

    private void writeEvaluation(OutputStream out, YearMonth period, User caller)
            throws IOException {
        // Roster summary row per intern (rollup state) + one row per
        // monthly evaluation that overlaps the period for detail. Two
        // sections keeps the export self-describing without making the
        // header row 30 cols wide.
        ActiveInternsDtos.ActiveInternListPage page = activeInternsService.list(
                caller, null, null, null, null, null,
                period.getYear(), period.getMonthValue(),
                0, 500, Scope.MANAGER_OWNED);
        CsvExporter.writeRow(out, List.of(
                "Employee ID", "Full name",
                "Evaluation state", "Last published",
                "Total approved hours (context)"
        ));
        if (page.items().isEmpty()) {
            writeNoDataRow(out, 5);
            return;
        }
        for (ActiveInternsDtos.ActiveInternRow r : page.items()) {
            ActiveInternsDtos.EvaluationStateBlock ev = r.evaluation();
            CsvExporter.writeRow(out, java.util.Arrays.asList(
                    nz(r.employeeId()), nz(r.fullName()),
                    ev != null ? nz(ev.state()) : "NONE",
                    ev != null && ev.lastPublishedAt() != null
                            ? ev.lastPublishedAt().toString() : "",
                    ""   // placeholder; managers usually want this in workload report
            ));
        }
        // Detail block — per-evaluation rows for the month.
        CsvExporter.writeRow(out, List.of(""));
        CsvExporter.writeRow(out, List.of("Evaluation detail (PUBLISHED / ACKNOWLEDGED / AMENDED, overlapping the month)"));
        CsvExporter.writeRow(out, List.of(
                "Employee ID", "Full name",
                "Evaluation type", "Status", "Period start", "Period end",
                "Overall score", "Published at"
        ));
        for (ActiveInternsDtos.ActiveInternRow r : page.items()) {
            if (r.internLifecycleId() == null) continue;
            List<InternEvaluation> evs = evaluationRepository
                    .findByInternLifecycleIdOrderByCreatedAtDesc(r.internLifecycleId());
            for (InternEvaluation e : evs) {
                if (e.getStatus() == null) continue;
                if (!"PUBLISHED".equals(e.getStatus())
                        && !"ACKNOWLEDGED".equals(e.getStatus())
                        && !"AMENDED".equals(e.getStatus())) continue;
                // Period overlap with the requested month.
                if (e.getPeriodStart() != null && e.getPeriodStart().isAfter(period.atEndOfMonth())) continue;
                if (e.getPeriodEnd() != null && e.getPeriodEnd().isBefore(period.atDay(1))) continue;
                CsvExporter.writeRow(out, java.util.Arrays.asList(
                        nz(r.employeeId()), nz(r.fullName()),
                        nz(e.getEvaluationType()),
                        nz(e.getStatus()),
                        e.getPeriodStart() != null ? e.getPeriodStart().toString() : "",
                        e.getPeriodEnd() != null ? e.getPeriodEnd().toString() : "",
                        e.getOverallScore() != null ? e.getOverallScore().toString() : "",
                        e.getPublishedAt() != null ? e.getPublishedAt().toString() : ""
                ));
            }
        }
    }

    private void writeCompliance(OutputStream out, User caller) throws IOException {
        // Risk Center is intern-keyed but state-current (not month-scoped)
        // since exceptions are open-ended. Period filter not applied —
        // matches the screen behaviour where the Risk Center shows what
        // is OPEN right now.
        ManagerDtos.RiskListResponse rl = riskCenterService.list(
                caller, null, null, null, null,
                caller.getRoles() != null && caller.getRoles().contains(UserRole.SUPER_ADMIN)
                        ? null : caller.getId(),
                null, 0, 500);
        CsvExporter.writeRow(out, List.of(
                "Intern", "Employee ID",
                "Exception type", "Severity", "Status",
                "Opened at", "Age (days)",
                "Assigned to", "ERM owner",
                "Manager", "Last seen at", "Resolved at"
        ));
        if (rl.items().isEmpty()) {
            writeNoDataRow(out, 12);
            return;
        }
        for (ManagerDtos.RiskRow r : rl.items()) {
            CsvExporter.writeRow(out, java.util.Arrays.asList(
                    nz(r.subjectName()), nz(r.subjectEmployeeId()),
                    nz(r.exceptionType()), nz(r.severity()), nz(r.status()),
                    r.openedAt() != null ? r.openedAt().toString() : "",
                    String.valueOf(r.ageDays()),
                    nz(r.assignedToName()), nz(r.ermOwnerName()),
                    nz(r.managerName()),
                    r.lastSeenAt() != null ? r.lastSeenAt().toString() : "",
                    r.resolvedAt() != null ? r.resolvedAt().toString() : ""
            ));
        }
    }

    private void writeInactive(OutputStream out, Integer year, Integer month, User caller)
            throws IOException {
        ManagerInactiveInternsDtos.InactiveInternsListResponse resp =
                inactiveService.list(caller, year, month);
        CsvExporter.writeRow(out, List.of(
                "Employee ID", "Full name", "Email", "Technology",
                "Exit type", "Exit date", "Last working day",
                "Active status", "Ended at", "Duration (days)",
                "Exit reason", "Reason code",
                "Rehire eligible", "Final timesheet status",
                "Access revocation done", "Final documents archived",
                "Projects completed", "Evaluations count",
                "Avg evaluation score", "Total approved hours",
                "Last project", "Trainer", "Evaluator", "Manager", "ERM"
        ));
        if (resp.items().isEmpty()) {
            writeNoDataRow(out, 25);
            return;
        }
        for (ManagerInactiveInternsDtos.InactiveInternRow r : resp.items()) {
            CsvExporter.writeRow(out, java.util.Arrays.asList(
                    nz(r.employeeId()), nz(r.fullName()), nz(r.email()),
                    nz(r.technologyTitle()),
                    nz(r.exitType()),
                    r.exitDate() != null ? r.exitDate().toString() : "",
                    r.lastWorkingDay() != null ? r.lastWorkingDay().toString() : "",
                    nz(r.activeStatus()),
                    r.endedAt() != null ? r.endedAt().toString() : "",
                    String.valueOf(r.durationDays()),
                    nz(r.exitReason()),
                    nz(r.reasonCode()),
                    r.rehireEligible() != null ? r.rehireEligible().toString() : "",
                    nz(r.finalTimesheetStatus()),
                    r.accessRevocationDone() != null ? r.accessRevocationDone().toString() : "",
                    r.finalDocumentsArchived() != null ? r.finalDocumentsArchived().toString() : "",
                    String.valueOf(r.projectsCompleted()),
                    String.valueOf(r.evaluationsCount()),
                    r.averageEvaluationScore() != null
                            ? String.format("%.2f", r.averageEvaluationScore()) : "",
                    r.totalApprovedHours() != null
                            ? r.totalApprovedHours().toPlainString() : "0.00",
                    nz(r.lastProjectTitle()),
                    nz(r.trainerName()),
                    nz(r.evaluatorName()),
                    nz(r.managerName()),
                    nz(r.ermName())
            ));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Preamble rows so a downloaded file is self-describing: report
     * type, period, the Manager's scope, and generated-at. Followed by
     * a blank row so spreadsheet apps don't merge it with the data
     * header.
     */
    private void writePreamble(OutputStream out, ReportType type,
                                YearMonth period, User caller) throws IOException {
        String periodLabel = period != null ? period.toString() : "all time";
        String scopeLabel = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN)
                ? "ALL (SUPER_ADMIN)"
                : "Manager: " + (caller.getFullName() != null
                    ? caller.getFullName() : caller.getEmail());
        String ts = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        CsvExporter.writeRow(out, List.of("Report", type.name()));
        CsvExporter.writeRow(out, List.of("Period", periodLabel));
        CsvExporter.writeRow(out, List.of("Scope", scopeLabel));
        CsvExporter.writeRow(out, List.of("Generated at", ts));
        CsvExporter.writeRow(out, List.of(""));
    }

    private static void writeNoDataRow(OutputStream out, int cols) throws IOException {
        List<Object> row = new ArrayList<>(cols);
        row.add("(no data)");
        for (int i = 1; i < cols; i++) row.add("");
        CsvExporter.writeRow(out, row);
    }

    private static String slotLabel(ActiveInternsDtos.ProjectSlot s) {
        StringBuilder sb = new StringBuilder();
        if (s.title() != null) sb.append(s.title()).append(" — ");
        sb.append(nz(s.state()));
        if (s.dueDate() != null) sb.append(" (due ").append(s.dueDate()).append(")");
        return sb.toString();
    }

    private static String nz(Object v) {
        return v != null ? v.toString() : "";
    }

    @SuppressWarnings("unused")
    private static BigDecimal bd(BigDecimal b) { return b != null ? b : BigDecimal.ZERO; }

    private void requireManagerOrSuperAdmin(User caller) {
        if (caller.getRoles() == null
                || (!caller.getRoles().contains(UserRole.MANAGER)
                    && !caller.getRoles().contains(UserRole.SUPER_ADMIN))) {
            throw new ForbiddenException("MANAGER or SUPER_ADMIN required");
        }
    }

    // Reference: unused day projection helper kept for parity with
    // ExitService.getInternSummary, in case a future report wants the
    // intern's daily breakdown surfaced.
    @SuppressWarnings("unused")
    private static String describeDay(TimesheetDayResponse d) {
        return d.dayOfWeek() + ":" + d.hours();
    }
}
