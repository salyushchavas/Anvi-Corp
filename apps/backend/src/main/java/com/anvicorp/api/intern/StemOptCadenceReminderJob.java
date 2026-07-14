package com.anvicorp.api.intern;

import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.I983Plan;
import com.anvicorp.api.entity.InternEvaluation;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.enums.WorkAuthTrack;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.I983PlanRepository;
import com.anvicorp.api.repository.InternEvaluationRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * Phase 6 weekly cadence reminder — every Monday 9am UTC, scan ACTIVE
 * F1_STEM_OPT interns and log a nudge for the assigned Evaluator when the
 * 12-month or 24-month STEM OPT anniversary is within ±7 days. Does NOT
 * auto-create the evaluation row.
 *
 * <p>Phase 7 will replace the log lines with real SentNotification rows.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StemOptCadenceReminderJob {

    private static final Set<String> NON_FINAL_STATUSES = Set.of(
            "DRAFT", "SCHEDULED", "IN_PROGRESS", "PUBLISHED", "ACKNOWLEDGED", "AMENDED");

    private static final long WINDOW_DAYS = 7;

    private final InternLifecycleRepository lifecycleRepository;
    private final CandidateRepository candidateRepository;
    private final I983PlanRepository i983PlanRepository;
    private final InternEvaluationRepository evalRepository;

    @Scheduled(cron = "0 0 9 * * MON")
    public void nudge() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int nudges = 0;
        try {
            List<InternLifecycle> lifecycles = lifecycleRepository.findAll();
            for (InternLifecycle lc : lifecycles) {
                if (!"ACTIVE".equals(lc.getActiveStatus())) continue;
                if (lc.getEvaluatorId() == null) continue;
                Candidate candidate = candidateRepository.findByUserId(lc.getUserId())
                        .orElse(null);
                if (candidate == null
                        || candidate.getExpectedTrack() != WorkAuthTrack.STEM_OPT) continue;

                List<I983Plan> plans = i983PlanRepository
                        .findByCandidateIdOrderByCreatedAtDesc(candidate.getId());
                if (plans.isEmpty()) continue;
                LocalDate startDate = plans.get(0).getOptStartDate();
                if (startDate == null) continue;

                if (withinAnniversaryWindow(today, startDate, 12)
                        && !hasNonCancelledOfType(lc.getUserId(), "STEM_OPT_12_MONTH")) {
                    log.info("[StemOptCadence] 12-month evaluation due for user={} evaluator={}",
                            lc.getUserId(), lc.getEvaluatorId());
                    nudges++;
                }
                if (withinAnniversaryWindow(today, startDate, 24)
                        && !hasNonCancelledOfType(lc.getUserId(), "STEM_OPT_24_MONTH")) {
                    log.info("[StemOptCadence] 24-month evaluation due for user={} evaluator={}",
                            lc.getUserId(), lc.getEvaluatorId());
                    nudges++;
                }
            }
            if (nudges > 0) {
                log.info("[StemOptCadence] nudged {} evaluators this tick", nudges);
            }
        } catch (Exception e) {
            log.warn("[StemOptCadence] scan failed (non-fatal): {}", e.getMessage());
        }
    }

    private static boolean withinAnniversaryWindow(LocalDate today, LocalDate start, int months) {
        LocalDate anniversary = start.plusMonths(months);
        long daysDiff = Math.abs(ChronoUnit.DAYS.between(anniversary, today));
        return daysDiff <= WINDOW_DAYS;
    }

    private boolean hasNonCancelledOfType(java.util.UUID internId, String type) {
        return evalRepository
                .findByInternIdOrderByCreatedAtDesc(internId).stream()
                .anyMatch(e -> type.equals(e.getEvaluationType())
                        && NON_FINAL_STATUSES.contains(e.getStatus()));
    }
}
