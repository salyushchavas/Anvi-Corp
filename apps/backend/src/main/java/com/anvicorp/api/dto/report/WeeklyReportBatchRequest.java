package com.anvicorp.api.dto.report;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Batch payload for the ERM verify-selected action on the weekly-report
 * queue. Mirrors {@link com.anvicorp.api.dto.supervised.TimesheetBatchRequest}
 * — same 200-id cap, same per-row outcome map on the response.
 */
public record WeeklyReportBatchRequest(
        @NotEmpty
        @Size(max = 200)
        List<UUID> ids
) {}
