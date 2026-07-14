package com.anvicorp.api.scheduler;

import com.anvicorp.api.entity.ExceptionEventLog;
import com.anvicorp.api.entity.ExceptionRecord;
import com.anvicorp.api.erm.exception.DetectorHit;
import com.anvicorp.api.erm.exception.ExceptionDetectionService;
import com.anvicorp.api.erm.exception.ExceptionType;
import com.anvicorp.api.event.ExceptionAutoResolvedEvent;
import com.anvicorp.api.event.ExceptionOpenedEvent;
import com.anvicorp.api.repository.ExceptionEventLogRepository;
import com.anvicorp.api.repository.ExceptionRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * ERM Phase 6 — scheduled exception detection. Every 15 minutes:
 * <ol>
 *   <li>Run every detector. For each hit, UPSERT an
 *       {@code ExceptionRecord} keyed by (subject_user_id, exception_type)
 *       where status IN (OPEN, ASSIGNED, IN_PROGRESS). Fresh row ⇒
 *       fire {@code ExceptionOpenedEvent} (notify ERM once). Existing
 *       row ⇒ refresh {@code last_seen_at} silently.</li>
 *   <li>Auto-resolve pass: any active record whose {@code last_seen_at}
 *       is older than the 30-min grace window flips to AUTO_RESOLVED.
 *       Fire {@code ExceptionAutoResolvedEvent} so the ERM owner sees
 *       the self-healing in their feed.</li>
 * </ol>
 *
 * <p>First scan runs 2 minutes after boot so the schema fixup runner +
 * lazy bean wiring settle. Subsequent ticks at 15-min cadence.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExceptionScanJob {

    /** Grace window — record must be unseen this long to auto-resolve. */
    private static final Duration AUTO_RESOLVE_GRACE = Duration.ofMinutes(30);

    private final ExceptionDetectionService detectionService;
    private final ExceptionRecordRepository recordRepository;
    private final ExceptionEventLogRepository eventLogRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** First tick at boot+2min, then every 15 min. */
    @Scheduled(fixedDelay = 900_000L, initialDelay = 120_000L)
    @Transactional
    public void scan() {
        long t0 = System.currentTimeMillis();
        int opened = 0;
        int refreshed = 0;
        int autoResolved = 0;

        Map<ExceptionType, List<DetectorHit>> all;
        try {
            all = detectionService.scanAllAsHits();
        } catch (Exception e) {
            log.warn("[ExceptionScan] detector enumeration failed (non-fatal): {}",
                    e.getMessage());
            return;
        }

        Instant now = Instant.now();
        for (Map.Entry<ExceptionType, List<DetectorHit>> e : all.entrySet()) {
            ExceptionType type = e.getKey();
            String severity = detectionService.severityOf(type).name();
            for (DetectorHit hit : e.getValue()) {
                try {
                    boolean isNew = upsertHit(type, severity, hit, now);
                    if (isNew) opened++;
                    else refreshed++;
                } catch (Exception ex) {
                    log.warn("[ExceptionScan] UPSERT failed for type={} user={}: {}",
                            type, hit.subjectUserId(), ex.getMessage());
                }
            }
        }

        // Auto-resolve pass.
        Instant cutoff = now.minus(AUTO_RESOLVE_GRACE);
        for (ExceptionType type : ExceptionType.values()) {
            try {
                List<ExceptionRecord> stale = recordRepository
                        .findStaleActiveByType(type.name(), cutoff);
                for (ExceptionRecord rec : stale) {
                    String previousStatus = rec.getStatus();
                    rec.setStatus("AUTO_RESOLVED");
                    rec.setResolvedAt(now);
                    recordRepository.save(rec);
                    appendLog(rec.getId(), null, "AUTO_RESOLVED",
                            previousStatus, "AUTO_RESOLVED",
                            null, "Detector no longer reports this condition.");
                    try {
                        eventPublisher.publishEvent(new ExceptionAutoResolvedEvent(
                                rec.getId(), rec.getSubjectUserId(),
                                rec.getInternLifecycleId(), rec.getExceptionType()));
                    } catch (Exception ex) {
                        log.debug("[ExceptionScan] auto-resolve event publish failed: {}",
                                ex.getMessage());
                    }
                    autoResolved++;
                }
            } catch (Exception ex) {
                log.warn("[ExceptionScan] auto-resolve scan failed for type={}: {}",
                        type, ex.getMessage());
            }
        }

        long elapsedMs = System.currentTimeMillis() - t0;
        log.info("[ExceptionScan] opened {}, refreshed {}, auto-resolved {}, total scan time {}ms",
                opened, refreshed, autoResolved, elapsedMs);
    }

    /** Returns true if a fresh row was created. */
    private boolean upsertHit(ExceptionType type, String severity,
                               DetectorHit hit, Instant now) {
        var existing = recordRepository
                .findActiveBySubjectAndType(hit.subjectUserId(), type.name());
        if (existing.isPresent()) {
            ExceptionRecord rec = existing.get();
            rec.setLastSeenAt(now);
            rec.setPayloadJson(hit.payloadJson());
            recordRepository.save(rec);
            return false;
        }
        ExceptionRecord rec = ExceptionRecord.builder()
                .internLifecycleId(hit.internLifecycleId())
                .subjectUserId(hit.subjectUserId())
                .exceptionType(type.name())
                .severity(severity)
                .status("OPEN")
                .openedAt(now)
                .lastSeenAt(now)
                .subjectResourceType(hit.subjectResourceType())
                .subjectResourceId(hit.subjectResourceId())
                .payloadJson(hit.payloadJson())
                .build();
        ExceptionRecord saved = recordRepository.save(rec);
        appendLog(saved.getId(), null, "OPENED",
                null, "OPEN", null, "Detector opened this exception.");
        try {
            eventPublisher.publishEvent(new ExceptionOpenedEvent(
                    saved.getId(), saved.getSubjectUserId(),
                    saved.getInternLifecycleId(),
                    saved.getExceptionType(), saved.getSeverity()));
        } catch (Exception ex) {
            log.debug("[ExceptionScan] open event publish failed: {}",
                    ex.getMessage());
        }
        return true;
    }

    private void appendLog(java.util.UUID recordId, java.util.UUID actorId,
                            String eventType, String previousStatus,
                            String newStatus, String reasonCode, String note) {
        try {
            eventLogRepository.save(ExceptionEventLog.builder()
                    .exceptionRecordId(recordId)
                    .actorUserId(actorId)
                    .eventType(eventType)
                    .previousStatus(previousStatus)
                    .newStatus(newStatus)
                    .reasonCode(reasonCode)
                    .note(note)
                    .build());
        } catch (Exception e) {
            log.warn("[ExceptionScan] event log append failed for record {}: {}",
                    recordId, e.getMessage());
        }
    }
}
