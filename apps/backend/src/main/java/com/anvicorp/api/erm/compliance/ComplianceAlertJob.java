package com.anvicorp.api.erm.compliance;

import com.anvicorp.api.event.WorkAuthExpiringEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * ERM Phase 5 — daily 08:00 UTC sweep over
 * {@code work_authorization_records} joined to active intern lifecycles.
 * Emits a {@link WorkAuthExpiringEvent} for any record whose earliest
 * expiration sits inside the alert window (≤30 days from today).
 *
 * <p>Idempotent at the listener layer via the existing
 * {@code sent_notifications} unique key on (event_type, target_id). The
 * job itself runs at most once per day; if Railway restarts in between
 * the next run is the next 08:00 UTC tick.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ComplianceAlertJob {

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher eventPublisher;
    private final ComplianceCalculatorService calculator;

    /** 08:00 UTC every day. Cheap — one indexed query, fan-out via events. */
    @Scheduled(cron = "0 0 8 * * *")
    public void fireDailyAlerts() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int emitted = 0;
        try {
            emitted = scanWorkAuthExpirations(today);
        } catch (Exception e) {
            log.warn("[ComplianceAlertJob] daily scan failed (non-fatal): {}",
                    e.getMessage());
        }
        if (emitted > 0) {
            log.info("[ComplianceAlertJob] emitted {} WorkAuthExpiringEvent(s) on {}",
                    emitted, today);
        }
    }

    private int scanWorkAuthExpirations(LocalDate today) {
        // Anything that hits today + 30 days from now where ≥ today.
        // ACTIVE + PROSPECTIVE lifecycles only — we don't pester exited folks.
        var rows = jdbc.query(
                "SELECT w.user_id, w.work_auth_type, "
                        + "       LEAST( "
                        + "         COALESCE(w.authorized_until, DATE '9999-12-31'), "
                        + "         COALESCE(w.ead_expiration,    DATE '9999-12-31'), "
                        + "         COALESCE(w.i20_expiration,    DATE '9999-12-31') "
                        + "       ) AS earliest "
                        + "  FROM work_authorization_records w "
                        + "  JOIN intern_lifecycles il ON il.user_id = w.user_id "
                        + " WHERE il.active_status IN ('ACTIVE','PROSPECTIVE') "
                        + "   AND LEAST( "
                        + "         COALESCE(w.authorized_until, DATE '9999-12-31'), "
                        + "         COALESCE(w.ead_expiration,    DATE '9999-12-31'), "
                        + "         COALESCE(w.i20_expiration,    DATE '9999-12-31') "
                        + "       ) BETWEEN CURRENT_DATE AND CURRENT_DATE + INTERVAL '30 days'",
                (rs, n) -> new WorkAuthRow(
                        UUID.fromString(rs.getString("user_id")),
                        rs.getString("work_auth_type"),
                        rs.getDate("earliest").toLocalDate()));

        int emitted = 0;
        for (WorkAuthRow row : rows) {
            Integer days = calculator.daysUntil(row.earliest(), today);
            if (days == null) continue;
            // The doc says alert thresholds are URGENT≤0 / WARN≤2 / INFO≤7,
            // but the daily-email cadence is broader (≤30) so ERMs see
            // upcoming work over a planning horizon. Per-day dedup handled
            // by the SentNotification ledger.
            try {
                eventPublisher.publishEvent(new WorkAuthExpiringEvent(
                        row.userId(), row.workAuthType(), row.earliest(), days));
                emitted++;
            } catch (Exception e) {
                log.warn("[ComplianceAlertJob] publish failed for user {}: {}",
                        row.userId(), e.getMessage());
            }
        }
        return emitted;
    }

    private record WorkAuthRow(UUID userId, String workAuthType, LocalDate earliest) {}
}
