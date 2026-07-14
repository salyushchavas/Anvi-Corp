package com.anvicorp.api.bootstrap;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.service.ApplicantIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Phase 1.2 backfill — runs after every seeder so it sees demo users created
 * during the same boot cycle. Two idempotent passes:
 *
 *   1. Flip {@code email_verified=true} for any user where it's currently
 *      FALSE. Combined with the DDL-level DEFAULT TRUE in SchemaFixupRunner,
 *      this catches any newly-seeded demo accounts that JPA inserted with
 *      the entity default FALSE.
 *   2. Assign a Anvi Corp Applicant ID to every CANDIDATE that lacks one.
 *
 * Order is intentionally LAST among bootstrap runners (Order=20) so it always
 * sees the final user set for this boot. Wrapped in try/catch so a backfill
 * failure logs WARN and never crashes startup — PRODUCT.md non-essential-seed
 * rule.
 */
@Component
@Order(20)
@RequiredArgsConstructor
@Slf4j
public class VerificationBackfillRunner implements CommandLineRunner {

    private final UserRepository userRepository;
    private final ApplicantIdGenerator applicantIdGenerator;

    @Override
    public void run(String... args) {
        try {
            backfillEmailVerified();
        } catch (Exception e) {
            log.warn("email_verified backfill failed (non-fatal): {}", e.getMessage(), e);
        }
        try {
            backfillApplicantIds();
        } catch (Exception e) {
            log.warn("applicant_id backfill failed (non-fatal): {}", e.getMessage(), e);
        }
    }

    private void backfillEmailVerified() {
        List<User> all = userRepository.findAll();
        int flipped = 0;
        for (User u : all) {
            if (!Boolean.TRUE.equals(u.getEmailVerified())) {
                u.setEmailVerified(true);
                u.setEmailVerificationCode(null);
                u.setEmailVerificationSentAt(null);
                u.setEmailVerificationExpiresAt(null);
                userRepository.save(u);
                flipped++;
            }
        }
        if (flipped > 0) {
            log.warn("Backfilled email_verified=true on {} existing user(s).", flipped);
        } else {
            log.info("email_verified backfill: no users to flip (already verified).");
        }
    }

    private void backfillApplicantIds() {
        List<User> all = userRepository.findAll();
        int issued = 0;
        for (User u : all) {
            if (u.getApplicantId() != null) continue;
            // Candidate-side is APPLICANT (pre-hire) or INTERN (post-hire). The
            // backfill applies to either — even hired interns who pre-date the
            // applicantId rollout should have one stamped.
            if (u.getRoles() == null
                    || !(u.getRoles().contains(UserRole.INTERN)
                            || u.getRoles().contains(UserRole.INTERN))) continue;
            String id = applicantIdGenerator.nextApplicantId();
            u.setApplicantId(id);
            u.setApplicantIdCreatedAt(Instant.now());
            userRepository.save(u);
            issued++;
            log.info("Backfilled Applicant ID {} for {}", id, u.getEmail());
        }
        if (issued == 0) {
            log.info("applicant_id backfill: every applicant/intern already has an ID.");
        }
    }
}
