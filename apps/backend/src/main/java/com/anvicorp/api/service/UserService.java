package com.anvicorp.api.service;

import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Canonical role-flip operations for the APPLICANT ↔ INTERN cache.
 *
 * <p>{@code user.role} is a denormalised cache of "is this user a hired
 * intern?". The source of truth is {@code engagement.status} — this service
 * exposes the single canonical promotion path so we never have multiple
 * services inlining their own role flip and getting the audit metadata
 * subtly different.</p>
 *
 * <h2>Promotion only — never demotion</h2>
 * <p>Once a user has been an intern, the INTERN role sticks until a
 * SUPER_ADMIN explicitly removes it. {@link UserRoleService} above mostly
 * lives so the reconciliation runner can call the same logic the engagement
 * transition does.</p>
 *
 * <h2>Audit posture</h2>
 * <p>Every promotion writes a {@code ROLE_PROMOTED} audit row with the
 * triggering context in {@code afterJson}. The actor is the system caller
 * when the flip is driven by an engagement transition — there is no human
 * actor for "engagement reached READY_TO_START".</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;

    /**
     * Idempotently promote a user to the INTERN role.
     *
     * <p>Semantics:</p>
     * <ul>
     *   <li>If the user is null or has no role set, no-op.</li>
     *   <li>If the user already has the INTERN role, no-op.</li>
     *   <li>If the user has any "staff" role (HR/OPERATIONS/TE/RM/EXECUTIVE/
     *       SUPER_ADMIN) without APPLICANT — no-op; staff are not flipped to
     *       INTERN by engagement state.</li>
     *   <li>Otherwise: drop APPLICANT, add INTERN, save, write a
     *       {@code ROLE_PROMOTED} audit row.</li>
     * </ul>
     *
     * <p>{@code triggeredBy} is a free-form tag — typical values are
     * {@code "ENGAGEMENT_TRANSITION"}, {@code "RECONCILIATION_RUNNER"}, or
     * {@code "ADMIN_PROMOTION"}. {@code triggerContextId} carries the
     * engagement id (or null when not applicable) so the audit row can be
     * traced back to the cause.</p>
     *
     * <p>The whole method is wrapped in try/catch so callers can rely on it
     * being non-fatal — a role-flip failure must never block the upstream
     * domain effect (engagement save, etc.). Errors are logged at ERROR
     * level with stacktrace so the issue is visible in ops.</p>
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean ensureInternRole(User user,
                                    String triggeredBy,
                                    UUID triggerContextId) {
        try {
            if (user == null || user.getRoles() == null) return false;

            Set<UserRole> roles = user.getRoles();

            if (roles.contains(UserRole.INTERN)) {
                return false;
            }

            // Skip staff. The reconciliation runner separately skips users
            // whose role is staff; this guard makes the service safe to call
            // unconditionally from any code path.
            if (isStaff(roles)) {
                return false;
            }

            EnumSet<UserRole> next = EnumSet.copyOf(roles);
            boolean wasApplicant = next.remove(UserRole.INTERN);
            next.add(UserRole.INTERN);
            user.setRoles(next);
            userRepository.save(user);

            String afterJson = "{\"from\":\""
                    + (wasApplicant ? "APPLICANT" : "OTHER")
                    + "\",\"to\":\"INTERN\",\"triggeredBy\":\""
                    + safeJson(triggeredBy)
                    + (triggerContextId != null
                            ? "\",\"engagementId\":\"" + triggerContextId + "\""
                            : "\"")
                    + "}";

            AuditLog row = AuditLog.builder()
                    .entityType("User")
                    .entityId(user.getId())
                    .action("ROLE_PROMOTED")
                    .userId(null) // system actor
                    .afterJson(afterJson)
                    .build();
            try {
                auditLogRepository.save(row);
            } catch (Exception auditErr) {
                // Never let the audit-row write fail the user promotion.
                log.warn("Role-promotion audit write failed (non-fatal) for {}: {}",
                        user.getEmail(), auditErr.getMessage());
            }

            log.info("Promoted user {} to INTERN (triggeredBy={}, context={})",
                    user.getEmail(), triggeredBy, triggerContextId);
            return true;
        } catch (Exception e) {
            log.error("ensureInternRole failed for user {} (non-fatal — engagement transition continues): {}",
                    user != null ? user.getEmail() : "<null>", e.getMessage(), e);
            return false;
        }
    }

    /**
     * True when the user holds a staff role — any role that is not APPLICANT,
     * INTERN, or absent. Used by {@link #ensureInternRole} and the
     * reconciliation runner to skip staff accounts so they're never demoted
     * or promoted on engagement state.
     */
    public boolean isStaff(Set<UserRole> roles) {
        if (roles == null || roles.isEmpty()) return false;
        for (UserRole r : roles) {
            if (r == UserRole.INTERN || r == UserRole.INTERN) continue;
            return true;
        }
        return false;
    }

    private static String safeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
