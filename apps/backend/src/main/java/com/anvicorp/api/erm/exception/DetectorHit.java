package com.anvicorp.api.erm.exception;

import java.util.UUID;

/**
 * ERM Phase 6 — what every detector method returns, one row per
 * affected intern. Used by both the on-demand path (Phase 1 dashboard
 * reads, now from the persisted table) and the scheduled
 * {@code ExceptionScanJob} UPSERT loop.
 *
 * <p>{@code payloadJson} must be PII-free — the detector is the choke
 * point for sanitisation.</p>
 *
 * @param subjectUserId      the intern this hit is about
 * @param internLifecycleId  the lifecycle row id (denorm so the scan
 *                           job doesn't have to look it up)
 * @param subjectResourceType e.g. PROJECT, TIMESHEET — see
 *                            {@link ExceptionType} doc
 * @param subjectResourceId  id of the offending resource (nullable)
 * @param payloadJson        type-specific, sanitised, JSON-encoded
 *                           (e.g. {@code {"daysOverdue":7}})
 */
public record DetectorHit(
        UUID subjectUserId,
        UUID internLifecycleId,
        String subjectResourceType,
        UUID subjectResourceId,
        String payloadJson
) {}
