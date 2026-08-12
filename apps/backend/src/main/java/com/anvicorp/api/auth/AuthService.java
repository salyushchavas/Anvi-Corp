package com.anvicorp.api.auth;

import com.anvicorp.api.auth.dto.AuthResponse;
import com.anvicorp.api.auth.dto.ForgotPasswordRequest;
import com.anvicorp.api.auth.dto.LoginRequest;
import com.anvicorp.api.auth.dto.MeResponse;
import com.anvicorp.api.auth.dto.RegisterRequest;
import com.anvicorp.api.auth.dto.ResendVerificationRequest;
import com.anvicorp.api.auth.dto.ResetPasswordRequest;
import com.anvicorp.api.auth.dto.VerifyEmailRequest;
import com.anvicorp.api.auth.dto.VerifyEmailResponse;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.PasswordResetToken;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.intern.InternLifecycleService;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailAccountStatus;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.notification.EmailDeliveryException;
import com.anvicorp.api.notification.NotificationStub;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.PasswordResetTokenRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.repository.UserSessionRepository;
import com.anvicorp.api.service.ApplicantIdGenerator;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final long RESET_CODE_TTL_SECONDS = 15L * 60L;
    private static final long VERIFICATION_CODE_TTL_HOURS = 24L;
    private static final SecureRandom RNG = new SecureRandom();

    // ── Security Wave 1 — login lockout tuning ─────────────────────────────
    /** Wrong-password count that trips the per-account lockout. */
    private static final int LOGIN_LOCKOUT_THRESHOLD = 5;
    /** Duration of the per-account lockout once triggered. */
    private static final Duration LOGIN_LOCKOUT_DURATION = Duration.ofMinutes(15);
    /** Wrong-code count that invalidates the current email-verification code. */
    private static final int VERIFY_CODE_ATTEMPT_CAP = 5;

    /**
     * Current ToS / Privacy version stamp. Bump this whenever the legal text
     * meaningfully changes — every fresh registration captures the version
     * the user agreed to at signup.
     */
    private static final String TOS_VERSION = "2026-05-27";

    private final UserRepository userRepository;
    private final CandidateRepository candidateRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final NotificationStub notificationStub;
    private final ApplicantIdGenerator applicantIdGenerator;
    private final SessionTokenService sessionTokenService;
    private final UserSessionRepository userSessionRepository;
    private final InternLifecycleService internLifecycleService;
    /**
     * Mail bridge Phase 3 — DORMANT. Used only by
     * {@link #tryMailCredentialBridge} on the failure path of
     * {@link #login}, to retry the submitted password against the linked
     * {@code mail_accounts} row's BCrypt hash. Nothing flips
     * {@code mail_account_id} on a User yet, so the helper short-circuits
     * to {@code false} for every existing row and login behaviour is
     * byte-identical to pre-phase.
     */
    private final MailAccountRepository mailAccountRepository;

    /**
     * Dev-only escape hatch for the 6-digit password-reset code. When
     * {@code app.notification.surface-reset-token=true} (set in dev only) the
     * code is logged at INFO so a developer can copy it without a real email
     * provider. Default FALSE — production log sinks NEVER see a live code.
     * Non-final so @Value field-injection composes with @RequiredArgsConstructor
     * (the Lombok constructor still ignores non-final fields).
     */
    @Value("${app.notification.surface-reset-token:false}")
    private boolean surfaceResetToken;

    /**
     * Fixed-cost BCrypt hash used to equalize login timing when no usable
     * account is found — the constant-work anti-enumeration path. Mirrors
     * the pattern in {@link com.anvicorp.api.mail.service.MailAuthService}.
     * Encoded once at bean init so its cost factor matches stored hashes;
     * the plaintext is irrelevant (matches() result is discarded).
     */
    private String dummyLoginHash;

    @PostConstruct
    void initDummyLoginHash() {
        this.dummyLoginHash = passwordEncoder.encode("dummy-normalization-hash-do-not-use");
    }

    @Transactional
    public AuthResponse register(RegisterRequest req, HttpServletRequest httpRequest) {
        if (userRepository.existsByEmail(req.email())) {
            throw new AuthException(HttpStatus.CONFLICT, "Email already registered");
        }

        String code = generateVerificationCode();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(VERIFICATION_CODE_TTL_HOURS, ChronoUnit.HOURS);

        User user = User.builder()
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .fullName(req.fullName())
                .roles(EnumSet.of(UserRole.INTERN))
                .emailVerified(false)
                // Proof of consent — stamped because the @AssertTrue on
                // RegisterRequest.acceptedTos passed validation by the time
                // we reach this point.
                .tosAcceptedAt(now)
                .tosVersion(TOS_VERSION)
                .emailVerificationCode(code)
                .emailVerificationSentAt(now)
                .emailVerificationExpiresAt(expiresAt)
                .build();
        userRepository.save(user);

        // Approach 1 — signup persists the candidate row with only the legal
        // name; every other intake field (phone, school/degree, work-auth
        // attestation, etc.) is collected on the post-signup profile editor
        // at /careers/intern/profile/complete. The apply endpoint guards on
        // ProfileCompletionService so a blank row simply leaves Apply locked
        // until the editor is finished — the column nullability matches.
        Candidate candidate = Candidate.builder()
                .user(user)
                .legalName(emptyToNull(req.legalName() != null
                        ? req.legalName() : req.fullName()))
                .build();
        candidateRepository.save(candidate);

        // Send verification code. SMTP failure throws — the @Transactional
        // rolls back the user + candidate save so a re-registration starts
        // clean. Never silently swallow a failed verification email (C3).
        try {
            notificationStub.sendVerificationCode(user.getEmail(), code, expiresAt);
        } catch (EmailDeliveryException e) {
            log.error("Verification email send failed during register for {}: {}",
                    user.getEmail(), e.getMessage());
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Couldn't send your verification code. Please try again in a moment.");
        }
        writeAccountAudit(user.getId(), "EMAIL_VERIFICATION_PENDING");
        log.info("User registered: {}", maskEmail(user.getEmail()));

        // SECURITY — the verification code is NEVER returned to the client.
        // It rides email only; in dev the LogEmailProvider also writes it to
        // the backend log when surface-stub is on, so developers can read it
        // without an SMTP server, but it never round-trips to the browser.
        return issueSessionResponse(user, httpRequest);
    }

    /**
     * Validate a 6-digit verification code, flip the user to verified, and —
     * for CANDIDATE accounts that don't already have one — issue a Skyzen
     * Applicant ID atomically. Idempotent: re-verifying an already-verified
     * user returns success without re-issuing an ID. Audits
     * EMAIL_VERIFIED and (when issued) APPLICANT_ID_CREATED.
     */
    @Transactional
    public VerifyEmailResponse verifyEmail(VerifyEmailRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new AuthException(HttpStatus.BAD_REQUEST,
                        "Invalid email or verification code"));

        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            // Already verified — return a no-op success with their existing ID.
            // Heal any stuck lifecycle row (legacy users from before the
            // verifyEmail→advance wiring shipped). advance() is a no-op if
            // they're already past EMAIL_VERIFIED.
            internLifecycleService.advance(
                    user, InternLifecycleStatus.EMAIL_VERIFIED, user.getId());
            return new VerifyEmailResponse(true, user.getApplicantId(),
                    "Email already verified");
        }

        String storedCode = user.getEmailVerificationCode();
        Instant expiresAt = user.getEmailVerificationExpiresAt();
        if (storedCode == null || !storedCode.equals(req.code())) {
            // Per-account brute-force cap on a 6-digit code (1M keyspace
            // + 24hr TTL was trivially brute-forceable). At the 5th wrong
            // attempt, invalidate the code entirely and force the user to
            // request a fresh one — that both stops the attack and makes
            // the interruption visible on the legitimate user's side.
            int attempts = user.getVerifyCodeAttempts() == null
                    ? 0 : user.getVerifyCodeAttempts();
            int nextAttempts = attempts + 1;
            if (nextAttempts >= VERIFY_CODE_ATTEMPT_CAP) {
                user.setEmailVerificationCode(null);
                user.setEmailVerificationSentAt(null);
                user.setEmailVerificationExpiresAt(null);
                user.setVerifyCodeAttempts(0);
                userRepository.save(user);
                log.warn("[VerifyEmailCap] user {} exceeded {} wrong-code attempts; "
                        + "code invalidated",
                        maskEmail(user.getEmail()), VERIFY_CODE_ATTEMPT_CAP);
                throw new AuthException(HttpStatus.BAD_REQUEST,
                        "Too many incorrect attempts. Request a new verification code.");
            }
            user.setVerifyCodeAttempts(nextAttempts);
            userRepository.save(user);
            throw new AuthException(HttpStatus.BAD_REQUEST,
                    "Invalid email or verification code");
        }
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            throw new AuthException(HttpStatus.BAD_REQUEST,
                    "Verification code has expired — request a new one");
        }

        user.setEmailVerified(true);
        user.setEmailVerificationCode(null);
        user.setEmailVerificationSentAt(null);
        user.setEmailVerificationExpiresAt(null);
        // Reset the attempt counter explicitly — the code-null above
        // logically resets it, but keeping this line makes the intent
        // obvious to anyone reading the success path.
        user.setVerifyCodeAttempts(0);
        writeAccountAudit(user.getId(), "EMAIL_VERIFIED");

        // Advance lifecycle REGISTERED → EMAIL_VERIFIED so downstream gates
        // (intern dashboard "Verify your email" banner, ApplyModal opening
        // checks, etc.) flip off immediately. advance() is idempotent + a
        // no-op if the user is already past EMAIL_VERIFIED (e.g. they were
        // re-issued a code after applying), so it's safe regardless of the
        // user's current position.
        internLifecycleService.advance(
                user, InternLifecycleStatus.EMAIL_VERIFIED, user.getId());

        // Only CANDIDATEs receive an Applicant ID; staff registrations (when
        // they exist via the admin path) are exempt. The unique DB sequence
        // backing nextApplicantId() guarantees the ID is collision-free even
        // under concurrent verifications.
        String applicantId = user.getApplicantId();
        if (applicantId == null && user.getRoles() != null
                && user.getRoles().contains(UserRole.INTERN)) {
            // Applicant IDs are issued only on the first email verification of
            // a freshly-registered APPLICANT. Once they're hired they become
            // INTERN — by then they already carry an applicantId from this
            // initial pass, so we don't include INTERN in the gate.
            applicantId = applicantIdGenerator.nextApplicantId();
            user.setApplicantId(applicantId);
            user.setApplicantIdCreatedAt(Instant.now());
            // Only send the "Your Applicant ID" email on the true
            // applicant-first-verify path. After the advance() above
            // the in-memory user status is EMAIL_VERIFIED; we allow
            // either REGISTERED or EMAIL_VERIFIED so both the
            // pre-advance and post-advance states resolve to "yes,
            // fire the email." Anyone whose lifecycle is further along
            // — a direct-onboarded intern who somehow ends up in this
            // branch, an ERM-hired intern re-verifying after an email
            // change, or any legacy user whose applicantId row got
            // wiped — must NOT receive this email; the copy ("Keep
            // this for your records — recruiters reference it on every
            // application") is pitched at first-time applicants and
            // reads as noise / confusing branding to anyone past that
            // stage. Same guard also short-circuits the edge case
            // where a hired intern's applicantId is briefly null
            // (mid-migration, admin manual reset) — the ID is still
            // stamped, the email is just skipped.
            boolean firstApplicantVerify = user.getLifecycleStatus() == null
                    || user.getLifecycleStatus()
                            == com.anvicorp.api.enums.InternLifecycleStatus.REGISTERED
                    || user.getLifecycleStatus()
                            == com.anvicorp.api.enums.InternLifecycleStatus.EMAIL_VERIFIED;
            if (firstApplicantVerify) {
                // Best-effort — the ID assignment is the source of truth; an email
                // hiccup must not block the verification flow.
                try {
                    notificationStub.sendApplicantIdIssued(user.getEmail(), applicantId);
                } catch (EmailDeliveryException e) {
                    log.warn("Applicant ID email failed (non-fatal) for {}: {}",
                            user.getEmail(), e.getMessage());
                }
            } else {
                log.info("[Applicant ID] issued {} for user {} but skipped the "
                                + "\"Applicant ID\" email — lifecycle is {} (past REGISTERED), "
                                + "the applicant-first-verify copy would read as noise.",
                        applicantId, user.getId(), user.getLifecycleStatus());
            }
            writeAccountAudit(user.getId(), "APPLICANT_ID_CREATED");
        }
        userRepository.save(user);

        return new VerifyEmailResponse(true, applicantId, "Email verified");
    }

    /**
     * Re-issue a fresh code + sentAt + expiresAt and stub-send it. Idempotent
     * — if the account is already verified we return without changing state.
     * To avoid revealing account existence to scanners, an unknown email
     * silently no-ops.
     */
    @Transactional
    public void resendVerification(ResendVerificationRequest req) {
        Optional<User> userOpt = userRepository.findByEmail(req.email());
        if (userOpt.isEmpty()) {
            // Don't reveal whether the email exists.
            return;
        }
        User user = userOpt.get();
        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            return;
        }
        String code = generateVerificationCode();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(VERIFICATION_CODE_TTL_HOURS, ChronoUnit.HOURS);
        user.setEmailVerificationCode(code);
        user.setEmailVerificationSentAt(now);
        user.setEmailVerificationExpiresAt(expiresAt);
        // Fresh code → fresh 5-attempt budget. Without this an attacker
        // who used up 4 attempts against the old code would only get 1
        // shot at the new one, which is a legit-user footgun.
        user.setVerifyCodeAttempts(0);
        userRepository.save(user);

        // Resend: same retryable-error semantics as register. The code change
        // is persisted; throwing here just stops the response from claiming
        // success when no email actually went out.
        try {
            notificationStub.sendVerificationCode(user.getEmail(), code, expiresAt);
        } catch (EmailDeliveryException e) {
            log.error("Verification email resend failed for {}: {}",
                    user.getEmail(), e.getMessage());
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Couldn't send your verification code. Please try again in a moment.");
        }
        writeAccountAudit(user.getId(), "EMAIL_VERIFICATION_RESENT");
    }

    private String generateVerificationCode() {
        // 000000 - 999999, zero-padded so it's always 6 chars on the wire.
        return String.format("%06d", RNG.nextInt(1_000_000));
    }

    /**
     * Treat an empty/whitespace-only string as null so intake fields the user
     * left blank don't show up as " " or "" rows in the DB. JSON deserialisation
     * keeps explicit nulls null already; this only handles the empty-string case.
     */
    /**
     * Security Wave 2 (M/L) — mask an email for logging. Shows the first
     * character of the local-part plus the domain so operators can still
     * grep for a rough identifier without disclosing the full PII value
     * to log-aggregation / SIEM sinks.
     * <pre>
     *   jane.doe@example.com  →  j****@example.com
     *   x@example.com         →  *@example.com
     *   (null / blank)        →  "&lt;anon&gt;"
     * </pre>
     */
    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "<anon>";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() == 1) return "*" + domain;
        return local.charAt(0) + "****" + domain;
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void writeAccountAudit(UUID userId, String action) {
        AuditLog row = AuditLog.builder()
                .userId(userId)
                .entityType("User")
                .entityId(userId)
                .action(action)
                .build();
        auditLogRepository.save(row);
    }

    /**
     * Login with three security invariants:
     *
     * <ol>
     *   <li><b>Constant work</b> — every failure path runs exactly one
     *       BCrypt matches() call (real hash if the account has one, a
     *       fixed {@link #dummyLoginHash} otherwise) so a timing side
     *       channel can't distinguish unknown-email from wrong-password
     *       or invited-not-activated from either.</li>
     *   <li><b>Single generic message</b> — every failure surfaces the
     *       same "Invalid credentials" 401. The prior distinct
     *       "Account not activated" branch was an enumeration oracle
     *       (finding H-4) and has been removed; the activation link email
     *       remains the correct nudge for that user.</li>
     *   <li><b>Per-account lockout</b> — 5 consecutive wrong passwords
     *       trip a 15-minute {@link User#getLockedUntil()} and the
     *       counter resets. During the lockout window the same generic
     *       401 is returned (never "locked" — that's another oracle).</li>
     * </ol>
     *
     * <p>The Phase-3 {@link #tryMailCredentialBridge} still fires as a
     * dormant fallback when a real password_hash exists but doesn't
     * match — behaviour preserved byte-for-byte for pre-existing users
     * (mail_account_id is null on every row today, so the bridge
     * short-circuits).</p>
     */
    @Transactional
    public AuthResponse login(LoginRequest req, HttpServletRequest httpRequest) {
        Optional<User> userOpt = userRepository.findByEmail(req.email());

        // 1) Lockout check FIRST — before any BCrypt work so a locked
        //    attacker can't even burn CPU. Same generic 401 as any other
        //    failure; we never leak "locked" as a distinct state.
        if (userOpt.isPresent()) {
            User u = userOpt.get();
            Instant lockedUntil = u.getLockedUntil();
            if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
                // Still run the dummy match so the timing profile of a
                // locked account matches the timing of any other 401.
                passwordEncoder.matches(req.password(), dummyLoginHash);
                log.warn("[LoginLockout] blocked attempt for locked user {} (locked until {})",
                        u.getEmail(), lockedUntil);
                throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
            }
        }

        // 2) Unknown email → run dummy match to normalize timing, then 401.
        if (userOpt.isEmpty()) {
            passwordEncoder.matches(req.password(), dummyLoginHash);
            log.warn("Failed login attempt for email: {}", maskEmail(req.email()));
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        User user = userOpt.get();
        String hash = user.getPasswordHash();

        // 3) Invited-not-activated (password_hash IS NULL). Run dummy match
        //    so timing matches wrong-password, then generic 401. The prior
        //    distinct "Account not activated" message was an enumeration
        //    oracle — activation-link email is the correct surface. Mail
        //    bridge still gets a shot only when a real mail account link
        //    exists (mail_account_id != null); dormant today.
        if (hash == null) {
            if (tryMailCredentialBridge(user, req.password())) {
                return finishSuccessfulLogin(user, httpRequest);
            }
            passwordEncoder.matches(req.password(), dummyLoginHash);
            log.warn("Failed login attempt for email: {} (unactivated)", maskEmail(req.email()));
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        // 4) Real BCrypt check. On mismatch: try the dormant mail-bridge
        //    fallback; if that also fails, bump the per-account counter and
        //    trip the lockout at 5 consecutive misses.
        boolean careersOk = passwordEncoder.matches(req.password(), hash);
        if (!careersOk && !tryMailCredentialBridge(user, req.password())) {
            recordFailedLogin(user);
            log.warn("Failed login attempt for email: {}", maskEmail(req.email()));
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        return finishSuccessfulLogin(user, httpRequest);
    }

    /**
     * Common tail for a successful authentication: reject deactivated
     * accounts (same generic 401), reset the failure counter, and hand
     * off to the session issuer.
     */
    private AuthResponse finishSuccessfulLogin(User user, HttpServletRequest httpRequest) {
        // Deactivated accounts see the same 401 as wrong password — the
        // frontend can't tell "real account, deactivated" from "unknown
        // email". Applies to mail-bridge logins too.
        if (Boolean.FALSE.equals(user.getActive())) {
            log.warn("Login blocked for deactivated user: {}", maskEmail(user.getEmail()));
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        resetFailedLoginCounter(user);
        log.info("User logged in: {}", maskEmail(user.getEmail()));
        return issueSessionResponse(user, httpRequest);
    }

    /**
     * Increment the failed-login counter; at {@link #LOGIN_LOCKOUT_THRESHOLD}
     * set {@link User#getLockedUntil()} and reset the counter so a fresh
     * 5-strike window starts after the unlock. Absorbs a single optimistic
     * lock collision — a second replica raced us and wrote first; the
     * observed counter is close enough for a lockout signal.
     */
    private void recordFailedLogin(User user) {
        int current = user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount();
        int next = current + 1;
        try {
            if (next >= LOGIN_LOCKOUT_THRESHOLD) {
                Instant lockedUntil = Instant.now().plus(LOGIN_LOCKOUT_DURATION);
                user.setLockedUntil(lockedUntil);
                user.setFailedLoginCount(0);
                userRepository.save(user);
                log.warn("[LoginLockout] user {} locked until {}", maskEmail(user.getEmail()), lockedUntil);
            } else {
                user.setFailedLoginCount(next);
                userRepository.save(user);
            }
        } catch (ObjectOptimisticLockingFailureException e) {
            // Two replicas raced. The other replica already wrote a newer
            // state; retry once with a fresh read so we don't drop this
            // failure. If it still races, swallow — the counter drifting
            // by one across replicas is acceptable for a lockout signal
            // and preferable to a 500 on a wrong password.
            try {
                User fresh = userRepository.findById(user.getId()).orElse(null);
                if (fresh != null) {
                    int freshCurrent = fresh.getFailedLoginCount() == null
                            ? 0 : fresh.getFailedLoginCount();
                    int freshNext = freshCurrent + 1;
                    if (freshNext >= LOGIN_LOCKOUT_THRESHOLD) {
                        Instant lockedUntil = Instant.now().plus(LOGIN_LOCKOUT_DURATION);
                        fresh.setLockedUntil(lockedUntil);
                        fresh.setFailedLoginCount(0);
                        userRepository.save(fresh);
                        log.warn("[LoginLockout] user {} locked until {} (after race retry)",
                                fresh.getEmail(), lockedUntil);
                    } else {
                        fresh.setFailedLoginCount(freshNext);
                        userRepository.save(fresh);
                    }
                }
            } catch (ObjectOptimisticLockingFailureException ignored) {
                log.debug("[LoginLockout] failed-count save lost race twice for {}, "
                        + "swallowing (drift acceptable)", user.getEmail());
            }
        }
    }

    /**
     * Zero the lockout counter + clear any expired lockout on the happy
     * path. Runs on every successful login (even ones with a 0 counter)
     * so a race where the counter drifted up in the previous replica
     * settles here.
     */
    private void resetFailedLoginCounter(User user) {
        boolean dirty = false;
        if (user.getFailedLoginCount() != null && user.getFailedLoginCount() != 0) {
            user.setFailedLoginCount(0);
            dirty = true;
        }
        if (user.getLockedUntil() != null) {
            user.setLockedUntil(null);
            dirty = true;
        }
        if (dirty) {
            try {
                userRepository.save(user);
            } catch (ObjectOptimisticLockingFailureException e) {
                // Success path — don't fail the login over a counter save.
                log.debug("[LoginLockout] reset lost race for {}, ignoring", maskEmail(user.getEmail()));
            }
        }
    }

    /**
     * Mail bridge Phase 3 — DORMANT fallback for the Careers login
     * failure path. Returns {@code true} iff the user has a linked mail
     * account ({@code mail_account_id != null}) that is
     * {@link MailAccountStatus#ACTIVE} and whose stored BCrypt hash
     * matches the submitted password.
     *
     * <p>Issues no token, mutates no row, never widens access: the
     * caller (only {@link #login}) still applies the active-gate +
     * normal session issuance after a {@code true} return. A successful
     * mail-credential match still mints a normal CAREERS JWT for the
     * SAME Careers User row; no MAIL JWT is read or produced here, and
     * no JWT secret is shared.</p>
     *
     * <p>Gated on {@code mail_account_id != null}; nothing in the
     * codebase sets this column yet, so every existing user gets an
     * immediate {@code return false} and login behaviour is
     * byte-identical to pre-phase.</p>
     */
    private boolean tryMailCredentialBridge(User user, String submittedPassword) {
        if (user == null || submittedPassword == null || submittedPassword.isEmpty()) {
            return false;
        }
        UUID mailAccountId = user.getMailAccountId();
        if (mailAccountId == null) {
            return false;
        }
        MailAccount account = mailAccountRepository.findById(mailAccountId).orElse(null);
        if (account == null) {
            return false;
        }
        if (account.getStatus() != MailAccountStatus.ACTIVE) {
            return false;
        }
        String hash = account.getPasswordHash();
        if (hash == null || hash.isBlank()) {
            return false;
        }
        return passwordEncoder.matches(submittedPassword, hash);
    }

    /**
     * Refresh-token rotation. Validates the presented refresh token against a
     * non-revoked, non-expired session, then rotates (revokes the old row,
     * issues a fresh access+refresh pair). Used by the frontend's auto-refresh
     * on 401.
     */
    @Transactional
    public AuthResponse refresh(String refreshToken, HttpServletRequest httpRequest) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        String hash = SessionTokenService.hash(refreshToken);
        com.anvicorp.api.entity.UserSession session = userSessionRepository
                .findByRefreshTokenHash(hash)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED,
                        "Invalid refresh token"));
        User user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED,
                        "Invalid refresh token"));
        if (Boolean.FALSE.equals(user.getActive())) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        SessionTokenService.Issued issued = sessionTokenService.rotate(user, refreshToken, httpRequest);
        return buildAuthResponse(user, issued);
    }

    /**
     * Code-entry reset: generate a fresh 6-digit code, invalidate any active
     * codes for the user, persist the new row, and email the code inline.
     *
     * <p>Silent no-op if the email is unknown — the caller (controller) always
     * returns 200 so an attacker cannot enumerate accounts. Email-send failures
     * are also swallowed for the same reason; the code row is the source of
     * truth and the user can request another via the same endpoint.</p>
     */
    @Transactional
    public void forgotPassword(ForgotPasswordRequest req) {
        Optional<User> userOpt = userRepository.findByEmail(req.email());
        if (userOpt.isEmpty()) {
            return;
        }
        User user = userOpt.get();

        // Invalidate previously-issued codes so only the freshest one works
        // (limits the window a leaked code stays live).
        passwordResetTokenRepository.invalidateActiveForUser(user.getId());

        String code = generateVerificationCode();
        Instant expiresAt = Instant.now().plusSeconds(RESET_CODE_TTL_SECONDS);
        PasswordResetToken prt = PasswordResetToken.builder()
                .userId(user.getId())
                .token(code)
                .expiresAt(expiresAt)
                .used(false)
                .build();
        passwordResetTokenRepository.save(prt);

        if (surfaceResetToken) {
            // Security Wave 2 — even dev-only logs mask both the recipient
            // and the code so a shipping app.notification.surface-reset-token
            // (e.g. via a leaked env var) does not disclose a live reset
            // code to log aggregators.
            log.info("DEV ONLY — password reset code issued for {} (code {}****)",
                    maskEmail(req.email()),
                    code == null || code.length() < 2 ? "**" : code.substring(0, 2));
        }

        try {
            notificationStub.sendPasswordReset(user.getEmail(), code, expiresAt);
        } catch (EmailDeliveryException e) {
            log.error("Password-reset email failed for {}: {}",
                    maskEmail(user.getEmail()), e.getMessage());
        }
    }

    /**
     * Validate {@code (email, code)} against an unused, unexpired
     * {@link PasswordResetToken}, then BCrypt-encode {@code newPassword} onto
     * {@link User#getPasswordHash()} and mark the code used. Same "Invalid or
     * expired code" message on every failure so an attacker can't distinguish
     * unknown-email from wrong-code.
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new AuthException(HttpStatus.BAD_REQUEST,
                        "Invalid or expired code"));

        PasswordResetToken prt = passwordResetTokenRepository
                .findFirstByUserIdAndTokenAndUsedFalseOrderByCreatedAtDesc(
                        user.getId(), req.code())
                .orElseThrow(() -> new AuthException(HttpStatus.BAD_REQUEST,
                        "Invalid or expired code"));

        if (prt.getExpiresAt().isBefore(Instant.now())) {
            throw new AuthException(HttpStatus.BAD_REQUEST,
                    "Invalid or expired code");
        }

        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepository.save(user);

        prt.setUsed(true);
        passwordResetTokenRepository.save(prt);

        log.info("Password reset completed for user: {}", maskEmail(user.getEmail()));
    }

    public MeResponse me(User user) {
        List<String> roles = user.getRoles().stream().map(Enum::name).toList();
        // Phase 3 step 6 — surface expectedTrack so the candidate sidebar can
        // hide non-STEM-OPT-only tiles (I-983 Training Plan). Only candidates
        // have a Candidate row; staff users return null here.
        String expectedTrack = candidateRepository.findByUserId(user.getId())
                .map(c -> c.getExpectedTrack() != null
                        ? c.getExpectedTrack().name()
                        : null)
                .orElse(null);
        return new MeResponse(
                user.getId().toString(),
                user.getEmail(),
                user.getFullName(),
                user.getPhoneNumber(),
                roles,
                user.getCreatedAt(),
                user.getEmailVerified(),
                user.getApplicantId(),
                expectedTrack,
                Boolean.TRUE.equals(user.getMustChangePassword())
        );
    }

    /**
     * Create a fresh session + bind a freshly-signed access token to it.
     * Used by login + register; the refresh path uses {@link #buildAuthResponse}
     * directly off a rotated session.
     */
    private AuthResponse issueSessionResponse(User user, HttpServletRequest httpRequest) {
        SessionTokenService.Issued issued = sessionTokenService.issueSession(
                user, httpRequest, "login");
        return buildAuthResponse(user, issued);
    }

    private AuthResponse buildAuthResponse(User user, SessionTokenService.Issued issued) {
        String accessToken = jwtUtil.generateAccessToken(user, issued.session().getId());
        List<String> roles = user.getRoles().stream().map(Enum::name).toList();
        return new AuthResponse(
                accessToken,
                issued.rawRefreshToken(),
                jwtUtil.accessTtlSeconds(),
                user.getId().toString(),
                user.getEmail(),
                user.getFullName(),
                roles,
                user.getEmailVerified(),
                user.getApplicantId(),
                Boolean.TRUE.equals(user.getMustChangePassword())
        );
    }
}
