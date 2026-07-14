package com.anvicorp.api.trainer.meetings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WeeklyMeeting;
import com.anvicorp.api.intern.WeeklyMeetingService;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.repository.WeeklyMeetingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Trainer Phase 3 — sweeps {@code weekly_meetings} every day at 09:00 UTC
 * and auto-flips any {@code SCHEDULED} row whose {@code scheduled_for} is
 * more than {@link #GRACE} in the past to {@code NO_SHOW}. Fires the same
 * downstream notification fan-out + ExceptionRecord nudge that a manual
 * mark-missed does.
 *
 * <p>The 4-hour grace window covers meetings that ran long or where the
 * Trainer simply forgot to mark complete — adjust {@link #GRACE} if the
 * platform's typical meeting cadence changes.</p>
 *
 * <p>Each row is processed inside its own service-level transaction (via
 * {@link WeeklyMeetingService#markMissedSystem(UUID, String)}) so a single
 * bad row never poisons the whole batch.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MissedMeetingDetectorJob {

    /** Window past {@code scheduled_for} before a SCHEDULED meeting flips
     *  to NO_SHOW. Conservative — the manual complete + mark-missed paths
     *  cover any meeting the Trainer remembers to action sooner. */
    private static final Duration GRACE = Duration.ofHours(4);

    private static final String AUTO_REASON =
            "scheduled time elapsed + " + GRACE.toHours()
                    + "h grace without trainer action";

    private final WeeklyMeetingRepository meetingRepository;
    private final WeeklyMeetingService meetingService;
    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final TrainerMeetingNotificationDispatcher notifier;
    private final ObjectMapper objectMapper;

    /** Daily at 09:00 UTC — after a standard 8-hour US workday. */
    @Scheduled(cron = "0 0 9 * * *")
    public void sweep() {
        Instant cutoff = Instant.now().minus(GRACE);
        int flipped = 0;
        int failed = 0;
        try {
            // Pull every SCHEDULED row and filter Java-side — the table is
            // small enough that this is faster than indexing the predicate.
            for (WeeklyMeeting m : meetingRepository.findAll()) {
                if (!"SCHEDULED".equals(m.getStatus())) continue;
                if (m.getScheduledFor() == null) continue;
                if (m.getScheduledFor().isAfter(cutoff)) continue;
                try {
                    WeeklyMeeting flippedRow = meetingService
                            .markMissedSystem(m.getId(), AUTO_REASON);
                    handleAuto(flippedRow);
                    flipped++;
                } catch (Exception e) {
                    failed++;
                    log.warn("[MissedMeetingDetector] flip failed for {}: {}",
                            m.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[MissedMeetingDetector] sweep aborted (non-fatal): {}",
                    e.getMessage());
            return;
        }
        if (flipped > 0 || failed > 0) {
            log.info("[MissedMeetingDetector] sweep done — flipped={}, failed={}",
                    flipped, failed);
        }
    }

    private void handleAuto(WeeklyMeeting m) {
        if (m == null) return;
        InternLifecycle lc = lifecycleRepository.findById(m.getInternLifecycleId())
                .orElse(null);
        if (lc == null) return;
        User host = m.getHostUserId() != null
                ? userRepository.findById(m.getHostUserId()).orElse(null) : null;
        if (host != null) {
            try {
                notifier.dispatchMissed(m, host, AUTO_REASON);
            } catch (Exception e) {
                log.warn("[MissedMeetingDetector] notify failed for {}: {}",
                        m.getId(), e.getMessage());
            }
        }
        try {
            String after = objectMapper.writeValueAsString(Map.of(
                    "status", "NO_SHOW",
                    "scheduledFor", m.getScheduledFor(),
                    "reason", AUTO_REASON));
            AuditLog row = AuditLog.builder()
                    .userId(null)            // system
                    .subjectUserId(lc.getUserId())
                    .entityType("WeeklyMeeting")
                    .entityId(m.getId())
                    .action("WEEKLY_MEETING_AUTO_MISSED")
                    .afterJson(after)
                    .build();
            auditLogRepository.save(row);
        } catch (Exception e) {
            log.warn("[MissedMeetingDetector] audit write failed: {}", e.getMessage());
        }
    }
}
