package com.anvicorp.api.notification;

import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.repository.InterviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Fires the 24h-before interview reminder. Runs hourly; each pass picks up
 * SCHEDULED interviews whose {@code scheduledAt} sits in the
 * {@code (now+23h, now+25h]} window. The 2-hour-wide window keeps the
 * reminder reliable even if a run is delayed (boot, deploy, container
 * restart); the {@link NotificationService} idempotency table absorbs the
 * duplicates so each interview emails exactly once.
 *
 * <p>Powered by {@code @EnableScheduling} on {@code SkyzenCareersApplication}.
 * No env vars or external dependencies.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InterviewReminderScheduler {

    private final InterviewRepository interviewRepository;
    private final NotificationService notificationService;

    /**
     * Cron: top of every hour. Cheap enough to not warrant a dedicated job —
     * 60 reads/day against an indexed (status, scheduled_at) range, no writes
     * unless something actually fires.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void fireDueReminders() {
        Instant now = Instant.now();
        Instant windowStart = now.plus(23, ChronoUnit.HOURS);
        Instant windowEnd = now.plus(25, ChronoUnit.HOURS);

        List<Interview> due;
        try {
            due = interviewRepository.findScheduledBetweenWithGraph(windowStart, windowEnd);
        } catch (Exception e) {
            log.warn("Interview reminder scan failed (non-fatal): {}", e.getMessage());
            return;
        }
        if (due.isEmpty()) return;

        log.info("Interview reminder pass — {} candidate(s) in window [{}, {}]",
                due.size(), windowStart, windowEnd);
        for (Interview interview : due) {
            try {
                notificationService.sendInterviewReminder(interview);
            } catch (Exception e) {
                log.warn("Interview reminder send failed for {} (non-fatal): {}",
                        interview.getId(), e.getMessage());
            }
        }
    }
}
