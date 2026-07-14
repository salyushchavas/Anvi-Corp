package com.anvicorp.api.notification;

import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.I983Plan;
import com.anvicorp.api.entity.I9Form;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.I983PlanRepository;
import com.anvicorp.api.repository.I9FormRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Daily scan for work-authorization expiry. Fires a reminder when an intern's
 * earliest expiry date sits exactly N days from today, for each
 * {@code N ∈ {90, 60, 30, 14, 7}}.
 *
 * <p><b>Expiry source.</b> We use the earliest of {@code I9Form
 * .workAuthExpirationDate} and {@code I983Plan.optEndDate} — same source the
 * compliance / executive dashboards use. The earlier of the two is the
 * binding date.</p>
 *
 * <p><b>Threshold semantics.</b> "Exactly N days" — not "within N days" —
 * paired with one distinct {@link NotificationEventType} per threshold gives
 * each (engagement, threshold) tuple exactly one email. The sent_notifications
 * unique constraint on {@code (event_type, target_id)} enforces it even if a
 * timezone wobble re-runs the scan twice.</p>
 *
 * <p><b>PII.</b> Reads dates only; never the document number, SSN, or address.
 * The {@code NotificationService} send method gets only the date + authType
 * label + dashboard URL.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkAuthExpiryScheduler {

    private static final int[] THRESHOLDS = {90, 60, 30, 14, 7};

    private final EngagementRepository engagementRepository;
    private final I9FormRepository i9FormRepository;
    private final I983PlanRepository i983PlanRepository;
    private final NotificationService notificationService;

    /** 06:00 UTC every day. Cheap (one read per active engagement). */
    @Scheduled(cron = "0 0 6 * * *")
    public void fireDueReminders() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Engagement> active;
        try {
            active = engagementRepository.findByStatus(EngagementStatus.ACTIVE);
        } catch (Exception e) {
            log.warn("Work-auth expiry scan failed (non-fatal): {}", e.getMessage());
            return;
        }
        if (active.isEmpty()) return;

        int sends = 0;
        for (Engagement engagement : active) {
            try {
                sends += scanOne(engagement, today);
            } catch (Exception e) {
                log.warn("Work-auth expiry scan failed for engagement {} (non-fatal): {}",
                        engagement.getId(), e.getMessage());
            }
        }
        if (sends > 0) {
            log.info("Work-auth expiry pass — fired {} reminder(s) on {}", sends, today);
        }
    }

    /** Returns the number of reminders fired for this engagement. */
    private int scanOne(Engagement engagement, LocalDate today) {
        if (engagement == null || engagement.getCandidate() == null) return 0;
        UUID candidateId = engagement.getCandidate().getId();

        // I-9 work-auth expiration.
        LocalDate i9Expiry = i9FormRepository.findByCandidateId(candidateId)
                .map(I9Form::getWorkAuthExpirationDate)
                .orElse(null);
        // I-983 OPT end date — newest plan wins.
        LocalDate optEnd = i983PlanRepository
                .findByCandidateIdOrderByCreatedAtDesc(candidateId).stream()
                .findFirst()
                .map(I983Plan::getOptEndDate)
                .orElse(null);

        // Earliest of the two; null means no expiry to track.
        LocalDate earliest = earliest(i9Expiry, optEnd);
        if (earliest == null) return 0;

        long days = ChronoUnit.DAYS.between(today, earliest);
        if (days < 0) return 0; // already expired — separate flow

        int sends = 0;
        for (int threshold : THRESHOLDS) {
            if (days == threshold) {
                String authType = labelFor(earliest, i9Expiry, optEnd);
                notificationService.sendWorkAuthExpiryReminder(
                        engagement, threshold, earliest, authType);
                sends++;
            }
        }
        return sends;
    }

    private static LocalDate earliest(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    /** Human-readable label for the binding authorization. */
    private static String labelFor(LocalDate earliest, LocalDate i9, LocalDate opt) {
        if (earliest.equals(opt)) return "STEM OPT";
        if (earliest.equals(i9))  return "Work authorization";
        return "Work authorization";
    }
}
