package com.anvicorp.api.erm.exception;

import java.util.List;
import java.util.Map;

/**
 * Composite output of {@link ExceptionDetectionService#detect}. Carries
 * per-type counts plus the top 5 most-urgent rows (across all types)
 * sorted by severity then daysOverdue.
 */
public record ExceptionDetectionResult(
        Map<ExceptionType, Integer> counts,
        List<ExceptionRow> topUrgent
) {}
