package com.anvicorp.api.service.timesheet;

import com.anvicorp.api.dto.supervised.TimesheetDayResponse;
import com.anvicorp.api.dto.supervised.TimesheetMonthRollupResponse;
import com.anvicorp.api.dto.supervised.TimesheetMonthRollupResponse.InternRow;
import com.anvicorp.api.dto.supervised.TimesheetMonthRollupResponse.MonthColumn;
import com.anvicorp.api.dto.supervised.TimesheetMonthRollupResponse.Summary;
import com.anvicorp.api.dto.supervised.TimesheetMonthRollupResponse.WeekCell;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Timesheet;
import com.anvicorp.api.entity.TimesheetDay;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.TimesheetStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.TimesheetDayRepository;
import com.anvicorp.api.repository.TimesheetRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared read-side for the ERM verify + Manager approve weekly rollups.
 * Returns one row per intern in scope, one column per Mon–Fri work-week
 * of the requested month — each cell carries the week total + status +
 * timesheet id + day breakdown.
 *
 * <p>Single source of truth so both staff surfaces render the same
 * shape; per-row gating happens on the action endpoints (this is read).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TimesheetRollupService {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");

    private final JdbcTemplate jdbc;
    private final TimesheetRepository timesheetRepository;
    private final TimesheetDayRepository timesheetDayRepository;
    private final CandidateRepository candidateRepository;
    private final UserRepository userRepository;

    public enum Scope {
        /** ERM verify queue — every intern active during the month. */
        ERM,
        /** Manager approve queue — interns where lifecycle.manager_id = caller. */
        MANAGER
    }

    @Transactional(readOnly = true)
    public TimesheetMonthRollupResponse getRollup(Scope scope, YearMonth period, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        if (period == null) throw new BadRequestException("year + month are required");
        requireScopeRole(scope, caller);

        List<MonthWeeks.WorkWeek> weeks = MonthWeeks.workWeeksOf(period);
        List<MonthColumn> columns = weeks.stream()
                .map(w -> new MonthColumn(w.weekStart(), w.weekNumber(), w.daysInMonth()))
                .toList();

        LocalDate periodStart = period.atDay(1);
        LocalDate periodEndExclusive = period.plusMonths(1).atDay(1);
        Timestamp tsStart = Timestamp.from(periodStart.atStartOfDay(ZONE).toInstant());
        Timestamp tsEndExclusive = Timestamp.from(periodEndExclusive.atStartOfDay(ZONE).toInstant());

        List<InternBasic> interns = loadInternsForScope(scope, caller, tsStart, tsEndExclusive);
        List<InternRow> rows = new ArrayList<>(interns.size());

        // Per-intern: bulk-fetch the timesheets that overlap the month
        // (cheaper than per-week lookups since most interns have 4–5
        // rows in the month) + their day rows.
        LocalDate fromMonday = weeks.isEmpty() ? periodStart : weeks.get(0).weekStart();
        LocalDate toMondayExclusive = fromMonday.plusDays(weeks.size() * 7L);
        int submittedAcc = 0, verifiedAcc = 0, approvedAcc = 0, rejectedAcc = 0, missingAcc = 0;

        for (InternBasic ib : interns) {
            UUID candidateId = ib.candidateId;
            Map<LocalDate, Timesheet> byWeek = new HashMap<>();
            Map<UUID, List<TimesheetDay>> daysByTs = new HashMap<>();
            if (candidateId != null) {
                for (Timesheet t : timesheetRepository.findForIntern(candidateId)) {
                    if (t.getWeekStart() != null
                            && !t.getWeekStart().isBefore(fromMonday)
                            && t.getWeekStart().isBefore(toMondayExclusive)) {
                        byWeek.put(t.getWeekStart(), t);
                    }
                }
                if (!byWeek.isEmpty()) {
                    for (Timesheet t : byWeek.values()) {
                        daysByTs.put(t.getId(),
                                timesheetDayRepository.findByTimesheetIdOrderByDayOfWeekAsc(t.getId()));
                    }
                }
            }

            List<WeekCell> cells = new ArrayList<>(weeks.size());
            BigDecimal internMonth = BigDecimal.ZERO;
            for (MonthWeeks.WorkWeek w : weeks) {
                Timesheet t = byWeek.get(w.weekStart());
                if (t == null) {
                    cells.add(new WeekCell(w.weekStart(), w.weekNumber(),
                            null, null, BigDecimal.ZERO, List.of(),
                            null, null, null, null, null, null));
                    missingAcc++;
                    continue;
                }
                Set<DayOfWeek> inScope = MonthWeeks.asSet(w.daysInMonth());
                List<TimesheetDay> daysAll = daysByTs.getOrDefault(t.getId(), List.of());
                List<TimesheetDayResponse> dayDtos = new ArrayList<>(daysAll.size());
                BigDecimal cellTotal = BigDecimal.ZERO;
                for (TimesheetDay d : daysAll) {
                    if (!inScope.contains(d.getDayOfWeek())) continue;
                    BigDecimal h = d.getHours() != null ? d.getHours() : BigDecimal.ZERO;
                    cellTotal = cellTotal.add(h);
                    dayDtos.add(new TimesheetDayResponse(d.getId(), d.getDayOfWeek(), h, d.getNotes()));
                }
                cellTotal = cellTotal.setScale(2, RoundingMode.HALF_UP);
                internMonth = internMonth.add(cellTotal);
                String verifiedByName = t.getVerifiedBy() != null
                        ? t.getVerifiedBy().getFullName() : null;
                String approvedByName = t.getApprovedBy() != null
                        ? t.getApprovedBy().getFullName() : null;
                cells.add(new WeekCell(
                        w.weekStart(), w.weekNumber(),
                        t.getId(), t.getStatus(), cellTotal, dayDtos,
                        // Phase B1 didn't add a submitted_at column —
                        // approximate via createdAt for now; B2 readers
                        // only need *some* timestamp for "submitted on…".
                        t.getCreatedAt(),
                        verifiedByName, t.getVerifiedAt(),
                        approvedByName, t.getApprovedAt(),
                        t.getReviewNote()));
                switch (t.getStatus()) {
                    case SUBMITTED -> submittedAcc++;
                    case VERIFIED -> verifiedAcc++;
                    case APPROVED -> approvedAcc++;
                    case REJECTED -> rejectedAcc++;
                    default -> {}
                }
            }
            rows.add(new InternRow(
                    ib.lifecycleId, ib.userId, ib.fullName, ib.employeeId,
                    ib.technologyTitle, ib.managerId, ib.managerName,
                    internMonth.setScale(2, RoundingMode.HALF_UP), cells));
        }

        // Order by name so the grid is stable across reloads.
        rows.sort(Comparator.comparing(r -> r.fullName() != null ? r.fullName() : "",
                String.CASE_INSENSITIVE_ORDER));
        return new TimesheetMonthRollupResponse(
                period.toString(), columns,
                new Summary(rows.size(), submittedAcc, verifiedAcc,
                        approvedAcc, rejectedAcc, missingAcc),
                rows);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void requireScopeRole(Scope scope, User caller) {
        boolean superAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (superAdmin) return;
        if (scope == Scope.ERM) {
            if (caller.getRoles() == null || !caller.getRoles().contains(UserRole.ERM)) {
                throw new ForbiddenException("ERM role required for the verify rollup");
            }
        } else if (scope == Scope.MANAGER) {
            if (caller.getRoles() == null || !caller.getRoles().contains(UserRole.MANAGER)) {
                throw new ForbiddenException("MANAGER role required for the approve rollup");
            }
        }
    }

    /**
     * Pull the in-scope intern roster for the requested period. ERM sees
     * every intern whose lifecycle was active during the month;
     * MANAGER sees only their managed interns. SUPER_ADMIN bypasses the
     * manager_id filter so they can see whichever scope was requested.
     */
    private List<InternBasic> loadInternsForScope(
            Scope scope, User caller, Timestamp periodStart, Timestamp periodEndExclusive) {
        boolean superAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        StringBuilder where = new StringBuilder(
                " WHERE COALESCE(il.started_at, il.hired_at) IS NOT NULL "
                        + "   AND COALESCE(il.started_at, il.hired_at) < ? "
                        + "   AND (il.ended_at IS NULL OR il.ended_at >= ?) ");
        List<Object> params = new ArrayList<>();
        params.add(periodEndExclusive);
        params.add(periodStart);
        if (scope == Scope.MANAGER && !superAdmin) {
            where.append(" AND il.manager_id = ? ");
            params.add(caller.getId());
        }
        try {
            return jdbc.query(
                    "SELECT il.id AS lifecycle_id, il.user_id, u.full_name, u.email, "
                            + "       il.employee_id, c.id AS candidate_id, c.skillset, "
                            + "       il.manager_id, mu.full_name AS manager_name "
                            + "  FROM intern_lifecycles il "
                            + "  JOIN users u ON u.id = il.user_id "
                            + "  LEFT JOIN candidates c ON c.user_id = il.user_id "
                            + "  LEFT JOIN users mu ON mu.id = il.manager_id "
                            + where
                            + " ORDER BY u.full_name ASC",
                    params.toArray(),
                    (rs, n) -> {
                        InternBasic ib = new InternBasic();
                        ib.lifecycleId = uuidOf(rs.getString("lifecycle_id"));
                        ib.userId = uuidOf(rs.getString("user_id"));
                        ib.fullName = rs.getString("full_name");
                        ib.employeeId = rs.getString("employee_id");
                        ib.candidateId = uuidOf(rs.getString("candidate_id"));
                        ib.technologyTitle = rs.getString("skillset");
                        ib.managerId = uuidOf(rs.getString("manager_id"));
                        ib.managerName = rs.getString("manager_name");
                        return ib;
                    });
        } catch (Exception e) {
            log.warn("[TimesheetRollup] roster load failed (non-fatal): {}", e.getMessage());
            return List.of();
        }
    }

    private static UUID uuidOf(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    /** Internal row carrier between SQL + DTO assembly. */
    private static final class InternBasic {
        UUID lifecycleId;
        UUID userId;
        String fullName;
        String employeeId;
        UUID candidateId;
        String technologyTitle;
        UUID managerId;
        String managerName;
    }

    // Unused imports kept to satisfy any future expansion — referenced
    // symbolically below so the compiler doesn't strip the imports.
    @SuppressWarnings("unused")
    private static void touch(Candidate c, User u, Instant i) {}
}
