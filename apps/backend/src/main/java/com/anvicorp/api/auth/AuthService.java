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
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
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
        log.info("User registered: {}", user.getEmail());

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
            // Best-effort — the ID assignment is the source of truth; an email
            // hiccup must not block the verification flow.
            try {
                notificationStub.sendApplicantIdIssued(user.getEmail(), applicantId);
            } catch (EmailDeliveryException e) {
                log.warn("Applicant ID email failed (non-fatal) for {}: {}",
                        user.getEmail(), e.getMessage());
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

    public AuthResponse login(LoginRequest req, HttpServletRequest httpRequest) {
        Optional<User> userOpt = userRepository.findByEmail(req.email());

        // Mail bridge Phase 5 (revised) — the Phase-4 PENDING_ACTIVATION
        // hard-lock has been REMOVED. Dashboard login is now byte-identical
        // for every user (including those handed over to a company
        // mailbox); the mailbox is a notification inbox reached via /mail
        // with mail-side credentials. The Phase-3 tryMailCredentialBridge
        // below stays in place as a dormant fallback (harmless — fires
        // only on Careers BCrypt failure for users with a linked mail
        // account, which still gates on the mail hash).

        // Explicit unactivated-account gate. Admin-invite rows live in
        // the DB with password_hash IS NULL until the user redeems the
        // activation link; without this branch BCryptPasswordEncoder
        // would just return false and we'd surface "Invalid credentials",
        // which is misleading and hides the real fix (open the email).
        if (userOpt.isPresent() && userOpt.get().getPasswordHash() == null) {
            // Mail bridge Phase 3 (DORMANT) — a handed-over user may have
            // a null Careers password by design and authenticate purely
            // via their mail-account credential. tryMailCredentialBridge
            // short-circuits to false when mail_account_id is null (every
            // user today), so the original "Account not activated"
            // behaviour is preserved byte-for-byte. On a true return the
            // bridge falls through to the SAME active-gate +
            // issueSessionResponse path the normal success uses.
            if (!tryMailCredentialBridge(userOpt.get(), req.password())) {
                log.warn("Login blocked for unactivated user: {}", req.email());
                throw new AuthException(HttpStatus.UNAUTHORIZED,
                        "Account not activated. Use the activation link emailed to you "
                                + "by your admin. If it's lost or expired, ask the admin to re-issue it.");
            }
        } else if (userOpt.isEmpty()
                || !passwordEncoder.matches(req.password(), userOpt.get().getPasswordHash())) {
            // Mail bridge Phase 3 (DORMANT) — the standard Careers BCrypt
            // check has FAILED. If the user exists AND has a linked mail
            // account, retry the submitted password against the mail
            // account's stored hash. tryMailCredentialBridge short-circuits
            // to false when mail_account_id is null (every user today),
            // so the original "Invalid credentials" behaviour is
            // preserved. Unknown email still throws — userOpt.isEmpty()
            // is checked first so we never call the bridge for a
            // nonexistent account.
            if (userOpt.isEmpty()
                    || !tryMailCredentialBridge(userOpt.get(), req.password())) {
                log.warn("Failed login attempt for email: {}", req.email());
                throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
            }
        }
        User user = userOpt.get();
        // Reject deactivated accounts at the login boundary. We use 401 rather
        // than 403 so a deactivated account behaves the same as a wrong-password
        // attempt — clients don't get an oracle that distinguishes "real account"
        // from "real account, just locked". Applies to bridge logins too: a
        // deactivated user cannot bridge in.
        if (Boolean.FALSE.equals(user.getActive())) {
            log.warn("Login blocked for deactivated user: {}", user.getEmail());
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        log.info("User logged in: {}", user.getEmail());
        return issueSessionResponse(user, httpRequest);
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
            log.info("DEV ONLY — password reset code for {}: {}",
                    req.email(), code);
        }

        try {
            notificationStub.sendPasswordReset(user.getEmail(), code, expiresAt);
        } catch (EmailDeliveryException e) {
            log.error("Password-reset email failed for {}: {}",
                    user.getEmail(), e.getMessage());
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

        log.info("Password reset completed for user: {}", user.getEmail());
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
