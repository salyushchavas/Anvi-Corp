package com.anvicorp.api.bootstrap;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Bot-signup mitigation — scheduled hard-delete of INTERN accounts that
 * never verified their email within a grace window.
 *
 * <p>Rate limiting + CAPTCHA (Cloudflare Turnstile) + honeypot cut the
 * inbound bot signup rate, but any bot that DOES slip through will
 * never verify (they don't own the address). Left in place those rows
 * would show up in the admin Users panel and inflate the "real intern
 * count" metric even though {@code AdminUserService.list} already
 * filters unverified INTERNs by default. This runner cleans them up
 * every hour.</p>
 *
 * <h2>Safety</h2>
 * <ul>
 *   <li>Only INTERN-only accounts (staff accounts hold ERM/MANAGER/etc.
 *       — never touched here). Enforced by the repo query
 *       {@code SIZE(u.roles) = 1 AND :internRole MEMBER OF u.roles}.</li>
 *   <li>Only rows older than {@code ttlHours} (default 48h). A real user
 *       who signed up but hasn't yet clicked the verification link
 *       within 24h is well past the friendly-reminder window; 48h is
 *       double that for slack.</li>
 *   <li>Delegates to {@link AdminUserService#deleteUnverifiedUser},
 *       which reuses the audited {@code hardPurge} FK sweep. If a
 *       future half-completed signup ever accretes downstream rows
 *       (application draft, resume upload) they are swept in the same
 *       transaction — no orphans.</li>
 *   <li>caller = {@code null} on the {@code deleteUnverifiedUser} call
 *       so the "cannot delete your own account" self-guard doesn't
 *       fire; the audit row is tagged
 *       {@code actor=system:UnverifiedAccountPurgeRunner} for
 *       traceability.</li>
 * </ul>
 *
 * <h2>Config</h2>
 * <p>Wire-in read from Spring properties (all defaulted so a fresh
 * clone just works):</p>
 * <ul>
 *   <li>{@code app.bot-mitigation.unverified-purge.enabled} — master
 *       switch; default {@code true}. Set false to freeze the runner
 *       during incident triage.</li>
 *   <li>{@code app.bot-mitigation.unverified-purge.ttl-hours} — grace
 *       window; default {@code 48}. Rows younger than this are left
 *       alone.</li>
 *   <li>{@code app.bot-mitigation.unverified-purge.batch-max} — soft
 *       cap on rows per run; default {@code 500}. If a backlog exists
 *       we drain it across several ticks so the transaction doesn't
 *       hold locks for minutes.</li>
 * </ul>
 *
 * <h2>Schedule</h2>
 * <p>{@code fixedDelay = 1h} with {@code initialDelay = 5min}. Every
 * hour we scan for eligible rows; a 5-minute initial delay lets the
 * app finish other startup runners before we touch user rows. The
 * schedule is intentionally loose — bot rows sitting for an extra
 * hour before cleanup is invisible to users.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UnverifiedAccountPurgeRunner {

    private final UserRepository userRepository;
    private final AdminUserService adminUserService;

    @Value("${app.bot-mitigation.unverified-purge.enabled:true}")
    private boolean enabled;

    @Value("${app.bot-mitigation.unverified-purge.ttl-hours:48}")
    private long ttlHours;

    @Value("${app.bot-mitigation.unverified-purge.batch-max:500}")
    private int batchMax;

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 300_000L)
    public void sweep() {
        if (!enabled) return;
        Instant cutoff = Instant.now().minus(Duration.ofHours(Math.max(1, ttlHours)));
        List<User> stale;
        try {
            stale = userRepository.findUnverifiedInternsCreatedBefore(
                    cutoff, UserRole.INTERN);
        } catch (Exception e) {
            log.warn("[UnverifiedAccountPurgeRunner] query failed: {} — will retry next tick",
                    e.getMessage());
            return;
        }
        if (stale.isEmpty()) {
            log.debug("[UnverifiedAccountPurgeRunner] no unverified intern rows older than {}h",
                    ttlHours);
            return;
        }
        int budget = Math.max(1, batchMax);
        int done = 0;
        int failed = 0;
        for (User u : stale) {
            if (done >= budget) break;
            try {
                adminUserService.deleteUnverifiedUser(u.getId(), /* caller */ null);
                done++;
            } catch (Exception e) {
                // Never fatal — a per-row failure (concurrent write,
                // FK the sweep doesn't yet know about) shouldn't halt
                // the batch. Log the row id + reason and continue.
                failed++;
                log.warn("[UnverifiedAccountPurgeRunner] failed to purge id={} email={}: {}",
                        u.getId(), maskEmail(u.getEmail()), e.getMessage());
            }
        }
        log.info("[UnverifiedAccountPurgeRunner] purged {} unverified intern account(s), "
                        + "failed {}, ttlHours={}, backlog={}",
                done, failed, ttlHours, Math.max(0, stale.size() - done));
    }

    /** Same masking convention {@code AuthService} uses in log lines —
     *  keeps a full email out of the deploy log while leaving enough
     *  breadcrumb to spot a targeted attack. */
    private static String maskEmail(String email) {
        if (email == null) return "?";
        int at = email.indexOf('@');
        if (at <= 1) return "*@" + (at < 0 ? "" : email.substring(at + 1));
        return email.charAt(0) + "***@" + email.substring(at + 1);
    }
}
