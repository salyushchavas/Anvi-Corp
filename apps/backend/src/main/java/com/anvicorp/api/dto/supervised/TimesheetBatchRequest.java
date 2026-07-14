package com.anvicorp.api.dto.supervised;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Batch operation payload for ERM verify-all / Manager approve-all on
 * the weekly rollup. Capped at 200 ids per request — UI typically posts
 * one month of weeks per intern, so this is comfortably above the worst
 * case (≈ 5 weeks × N interns).
 */
public record TimesheetBatchRequest(
        @NotEmpty
        @Size(max = 200)
        List<UUID> ids
) {}
