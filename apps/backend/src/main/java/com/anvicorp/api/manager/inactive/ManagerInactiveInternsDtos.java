package com.anvicorp.api.manager.inactive;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 4B-1 — DTOs for the Manager's read-only Inactive Interns list.
 * Mirrors the shape of {@code ExitDtos.ExitSummaryResponse} (intern's
 * own exit card) so the manager view + the intern's exit summary stay
 * consistent — same closure data, different scope.
 */
public final class ManagerInactiveInternsDtos {

    private ManagerInactiveInternsDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InactiveInternRow(
            UUID internLifecycleId,
            UUID internUserId,
            String fullName,
            String email,
            String employeeId,
            String technologyTitle,
            /** Lifecycle.active_status — COMPLETED | RESIGNED | TERMINATED.
             *  Falls back to ENDED when only user.lifecycle_status flipped. */
            String activeStatus,
            /** Lifecycle.ended_at — moment the intern was marked inactive
             *  (often distinct from ExitRecord.exitDate which is the
             *  calendar-date the ERM stamped). */
            Instant endedAt,
            LocalDate startDate,
            long durationDays,
            /** Phase A reporting structure for context. */
            String trainerName,
            String evaluatorName,
            String managerName,
            String ermName,

            // ── Exit record (may be null when ERM marked INACTIVE_INTERN
            //    without creating an ExitRecord — rare but tolerated.) ──
            UUID exitRecordId,
            String exitType,                  // COMPLETED | RESIGNED | TERMINATED | EXTENDED
            LocalDate exitDate,
            LocalDate lastWorkingDay,
            String exitReason,
            String reasonCode,
            Boolean rehireEligible,
            String finalTimesheetStatus,      // ALL_APPROVED | PENDING | WAIVED
            Boolean accessRevocationDone,
            Boolean finalDocumentsArchived,
            String internVisibleSummary,
            UUID finalEvaluationId,

            // ── Closure snapshot (cheap aggregates — mirrors ExitService.getInternSummary) ──
            long projectsCompleted,
            long evaluationsCount,
            Double averageEvaluationScore,
            BigDecimal totalApprovedHours,
            String lastProjectTitle,
            LocalDate lastProjectMonthYearAsDate   // first-of-month synthetic anchor
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InactiveInternsListResponse(
            List<InactiveInternRow> items,
            int totalElements,
            /** Optional period filter. Null when "all time". */
            String monthYear
    ) {}
}
