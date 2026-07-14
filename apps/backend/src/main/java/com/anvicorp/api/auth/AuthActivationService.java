package com.anvicorp.api.auth;

import com.anvicorp.api.auth.dto.ActivateRequest;
import com.anvicorp.api.auth.dto.ActivationValidationResponse;
import com.anvicorp.api.auth.dto.AuthResponse;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.StaffActivationToken;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.StaffActivationTokenRepository;
import com.anvicorp.api.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Resolves and redeems admin-issued activation tokens. Pairs with the
 * {@code AdminUserService.create} flow that mints the token; this class
 * is the redemption side.
 *
 * <h2>Security</h2>
 * <ul>
 *   <li>The caller submits the RAW token; we hash it via
 *       {@link SessionTokenService#hash(String)} and look up by hash. We
 *       NEVER store or log the raw value.</li>
 *   <li>Every failure mode (no row / used / expired / orphaned user)
 *       returns the SAME generic message + 400 status — no oracle for
 *       enumerating tokens or guessing state.</li>
 *   <li>Successful activation marks the token used (single-use guarantee)
 *       AND invalidates any other live tokens for the same user — if the
 *       admin issued a fresh invite, the stale one stops working.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthActivationService {

    private static final String GENERIC_INVALID =
            "Invalid or expired activation link";

    private final StaffActivationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogRepository auditLogRepository;
    private final SessionTokenService sessionTokenService;
    private final JwtUtil jwtUtil;

    /**
     * GET-side: tell the activation page enough to show "Set the password
     * for x@y" without revealing whether other addresses exist. Throws on
     * any failure mode with the same generic message.
     */
    @Transactional(readOnly = true)
    public ActivationValidationResponse validate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, GENERIC_INVALID);
        }
        StaffActivationToken token = lookupLiveTokenOrThrow(rawToken);
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new AuthException(HttpStatus.BAD_REQUEST, GENERIC_INVALID));
        // If the row was activated through some other path (admin reset)
        // since the token was issued, the link is no longer meaningful.
        if (user.getPasswordHash() != null) {
            throw new AuthException(HttpStatus.BAD_REQUEST, GENERIC_INVALID);
        }
        return new ActivationValidationResponse(
                user.getEmail(),
                user.getFullName(),
                primaryRole(user),
                token.getExpiresAt());
    }

    /**
     * POST-side: redeem the token, set the password, log the user in.
     * Wrapped in a single transaction so a token can't be marked used
     * without the password hash also being persisted (or vice versa).
     */
    @Transactional
    public AuthResponse activate(ActivateRequest req, HttpServletRequest httpRequest) {
        if (req == null || req.token() == null || req.token().isBlank()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, GENERIC_INVALID);
        }
        StaffActivationToken token = lookupLiveTokenOrThrow(req.token());
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new AuthException(HttpStatus.BAD_REQUEST, GENERIC_INVALID));
        if (user.getPasswordHash() != null) {
            // Already activated via another path — refuse to overwrite.
            throw new AuthException(HttpStatus.BAD_REQUEST, GENERIC_INVALID);
        }

        // Set the password using the SAME encoder as AuthService.register.
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        // Belt-and-suspenders: clear must_change_password (defaults to
        // false on a fresh row, but the flag column lives on every user
        // so we explicitly stamp it here).
        user.setMustChangePassword(false);
        userRepository.save(user);

        // Mark this token used. Invalidate any other live tokens for the
        // same user — re-issued invites shouldn't keep working after one
        // has been redeemed.
        token.setUsedAt(Instant.now());
        tokenRepository.save(token);
        try {
            tokenRepository.markAllUnusedByUserAsInvalidated(user.getId());
        } catch (Exception e) {
            log.warn("[Activation] failed to invalidate sibling tokens for {} (non-fatal): {}",
                    user.getId(), e.getMessage());
        }

        writeAccountAudit(user.getId(), "STAFF_ACCOUNT_ACTIVATED");
        log.info("[Activation] staff account activated: {} (role={})",
                user.getEmail(), primaryRole(user));

        // Issue a fresh session — the user lands authenticated on first
        // navigation after redirect.
        SessionTokenService.Issued issued =
                sessionTokenService.issueSession(user, httpRequest, "activation");
        return buildAuthResponse(user, issued);
    }

    private StaffActivationToken lookupLiveTokenOrThrow(String rawToken) {
        String hash = SessionTokenService.hash(rawToken);
        Optional<StaffActivationToken> opt = tokenRepository.findByTokenHash(hash);
        if (opt.isEmpty()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, GENERIC_INVALID);
        }
        StaffActivationToken token = opt.get();
        if (token.getUsedAt() != null) {
            throw new AuthException(HttpStatus.BAD_REQUEST, GENERIC_INVALID);
        }
        if (token.getExpiresAt() == null
                || token.getExpiresAt().isBefore(Instant.now())) {
            throw new AuthException(HttpStatus.BAD_REQUEST, GENERIC_INVALID);
        }
        return token;
    }

    private String primaryRole(User user) {
        if (user.getRoles() == null || user.getRoles().isEmpty()) return "STAFF";
        // Single-role accounts are the norm for the admin-invite flow.
        // If multi-role ever shows up here, pick a stable representative
        // by enum ordering.
        return user.getRoles().iterator().next().name();
    }

    private AuthResponse buildAuthResponse(User user, SessionTokenService.Issued issued) {
        // Mirrors AuthService.buildAuthResponse exactly so the activation
        // success payload is shape-identical to a normal login response —
        // the frontend can store it the same way without a special branch.
        String accessToken = jwtUtil.generateAccessToken(user, issued.session().getId());
        return new AuthResponse(
                accessToken,
                issued.rawRefreshToken(),
                jwtUtil.accessTtlSeconds(),
                user.getId().toString(),
                user.getEmail(),
                user.getFullName(),
                user.getRoles() == null ? List.of()
                        : user.getRoles().stream().map(UserRole::name).toList(),
                Boolean.TRUE.equals(user.getEmailVerified()),
                user.getApplicantId(),
                Boolean.TRUE.equals(user.getMustChangePassword())
        );
    }

    private void writeAccountAudit(java.util.UUID userId, String action) {
        try {
            AuditLog row = AuditLog.builder()
                    .userId(userId)
                    .entityType("User")
                    .entityId(userId)
                    .action(action)
                    .build();
            auditLogRepository.save(row);
        } catch (Exception e) {
            log.warn("[Activation] audit write failed (non-fatal): {}", e.getMessage());
        }
    }
}
