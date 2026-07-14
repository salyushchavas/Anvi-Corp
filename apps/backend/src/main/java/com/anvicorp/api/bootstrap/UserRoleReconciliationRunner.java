package com.anvicorp.api.bootstrap;

import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Reconcile {@code user.roles} with {@code engagement.status} on boot.
 *
 * <p>{@code user.role} is a denormalised cache of "is this user a hired
 * intern?". The source of truth is the candidate's engagement(s). When the
 * cache drifts — typically because an engagement transition committed in a
 * version of the code that didn't flip the role — this runner closes the
 * gap idempotently.</p>
 *
 * <h2>Policy</h2>
 * <ul>
 *   <li><b>Promote APPLICANT → INTERN</b> when any of the user's engagements
 *       is in {@code READY_TO_START}, {@code ACTIVE}, or {@code COMPLETED}.
 *       Promotion is via the canonical {@link UserService#ensureInternRole}.
 *   <li><b>Warn on INTERN-without-active-engagement</b> drift. Do NOT
 *       auto-demote — once an intern, always an intern by policy. A human
 *       must explicitly remove the role via the SUPER_ADMIN UI.
 *   <li>Skip users with any staff role (HR/OPS/TE/RM/EXECUTIVE/SUPER_ADMIN).
 * </ul>
 *
 * <h2>Operational</h2>
 * <ul>
 *   <li>Default ON. Kill-switch property
 *       {@code app.bootstrap.reconcile-user-roles-enabled=false} disables.
 *   <li>Per-user failures are caught and logged; the batch continues.
 *   <li>Trivially cheap at current scale (&lt;10k users). If the user table
 *       grows, the batch can be sharded by id range — for now we read all
 *       APPLICANTs + INTERNs in one go.
 *   <li>Boot order: {@link Order} 6 — runs after schema fix-ups and seed
 *       runners so the data the reconciliation reasons over is finalised.
 * </ul>
 */
@Component
@Order(6)
@RequiredArgsConstructor
@Slf4j
public class UserRoleReconciliationRunner implements ApplicationRunner {

    /**
     * Engagement statuses that mean "the user has been hired" (or finished a
     * hired stint). Any one of these implies the user should hold INTERN.
     */
    private static final Set<EngagementStatus> HIRED_STATUSES = EnumSet.of(
            EngagementStatus.READY_TO_START,
            EngagementStatus.ACTIVE,
            EngagementStatus.COMPLETED);

    private final UserRepository userRepository;
    private final CandidateRepository candidateRepository;
    private final EngagementRepository engagementRepository;
    private final UserService userService;

    @Value("${app.bootstrap.reconcile-user-roles-enabled:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[UserRoleReconciliation] disabled by property — skipping.");
            return;
        }

        int scanned = 0;
        int promoted = 0;
        int warnings = 0;
        int failures = 0;

        // Pass 1 — promote APPLICANTs whose engagement says they should be INTERN.
        List<User> applicants;
        try {
            applicants = userRepository.findByRole(UserRole.INTERN);
        } catch (Exception e) {
            log.warn("[UserRoleReconciliation] failed to load APPLICANTs: {}", e.getMessage(), e);
            return;
        }

        for (User user : applicants) {
            scanned++;
            try {
                if (userService.isStaff(user.getRoles())) {
                    // Defensive — findByRole returned a user matching APPLICANT,
                    // but they also hold a staff role. Skip silently.
                    continue;
                }
                Candidate candidate = candidateRepository.findByUserId(user.getId()).orElse(null);
                if (candidate == null) continue;

                List<Engagement> engagements = engagementRepository.findByCandidateId(candidate.getId());
                EngagementStatus qualifying = pickQualifyingStatus(engagements);
                if (qualifying == null) continue;

                boolean did = userService.ensureInternRole(
                        user, "RECONCILIATION_RUNNER", null);
                if (did) {
                    promoted++;
                    log.info("[UserRoleReconciliation] promoted {} APPLICANT -> INTERN (engagement status {})",
                            user.getEmail(), qualifying);
                }
            } catch (Exception e) {
                failures++;
                log.warn("[UserRoleReconciliation] per-user reconciliation failed for {}: {}",
                        user.getEmail(), e.getMessage(), e);
            }
        }

        // Pass 2 — warn on INTERNs that have no qualifying engagement. Never
        // auto-demote; just flag drift for a human to review.
        List<User> interns;
        try {
            interns = userRepository.findByRole(UserRole.INTERN);
        } catch (Exception e) {
            log.warn("[UserRoleReconciliation] failed to load INTERNs: {}", e.getMessage(), e);
            interns = List.of();
        }

        for (User user : interns) {
            try {
                if (userService.isStaff(user.getRoles())) continue;
                Candidate candidate = candidateRepository.findByUserId(user.getId()).orElse(null);
                if (candidate == null) {
                    warnings++;
                    log.warn("[UserRoleReconciliation] role-state drift: {} is INTERN but has no Candidate row",
                            user.getEmail());
                    continue;
                }
                List<Engagement> engagements = engagementRepository.findByCandidateId(candidate.getId());
                if (pickQualifyingStatus(engagements) == null) {
                    warnings++;
                    log.warn("[UserRoleReconciliation] role-state drift: {} is INTERN but has no active or completed engagement",
                            user.getEmail());
                }
            } catch (Exception e) {
                failures++;
                log.warn("[UserRoleReconciliation] per-user drift check failed for {}: {}",
                        user.getEmail(), e.getMessage(), e);
            }
        }

        log.info("[UserRoleReconciliation] done. scanned={} promoted={} warnings={} failures={}",
                scanned, promoted, warnings, failures);
    }

    /**
     * Returns the first engagement status in {@link #HIRED_STATUSES} found
     * among the candidate's engagements, or null if none qualify. Order
     * doesn't matter — any qualifying engagement is enough to flip the role.
     */
    private EngagementStatus pickQualifyingStatus(List<Engagement> engagements) {
        if (engagements == null || engagements.isEmpty()) return null;
        for (Engagement e : engagements) {
            if (e.getStatus() != null && HIRED_STATUSES.contains(e.getStatus())) {
                return e.getStatus();
            }
        }
        return null;
    }
}
