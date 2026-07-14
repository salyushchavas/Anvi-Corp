package com.anvicorp.api.intern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Centralised, monotonic advancement of {@code users.lifecycle_status}.
 * Callers in Phase 2 (Application, Interview) invoke {@link #advance} inside
 * the same transaction that owns the state change so the Phase 1 dashboard
 * mode engine reflects the new state on the next 30s poll.
 *
 * <h2>No regress</h2>
 * The advance() method NEVER moves a user backwards. If the requested target
 * is at or behind the current ordinal, the call is a logged no-op. This keeps
 * downstream effects (notifications, mode-engine derivation) idempotent.
 *
 * <h2>Audit</h2>
 * Every real transition writes a USER_LIFECYCLE_ADVANCE audit row with a
 * before/after JSON snapshot keyed on the user id.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternLifecycleService {

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * Advances the user's lifecycle status to {@code target} iff
     * {@code target.ordinal() > current.ordinal()}. Returns true when the
     * row was actually moved.
     *
     * @param user   the user being moved (managed entity; persisted in place)
     * @param target the requested new status
     * @param actorId the user id performing the action (nullable — null means
     *                system-driven)
     */
    public boolean advance(User user, InternLifecycleStatus target, UUID actorId) {
        if (user == null || target == null) return false;
        InternLifecycleStatus current = user.getLifecycleStatus() != null
                ? user.getLifecycleStatus()
                : InternLifecycleStatus.REGISTERED;
        if (target.ordinal() <= current.ordinal()) {
            if (target != current) {
                log.warn("[InternLifecycle] rejecting regression for user={} {} -> {}",
                        user.getId(), current, target);
            }
            return false;
        }
        user.setLifecycleStatus(target);
        User saved = userRepository.save(user);
        writeAudit(saved.getId(), current, target, actorId);
        log.info("[InternLifecycle] user={} {} -> {}",
                saved.getId(), current, target);
        return true;
    }

    private void writeAudit(UUID userId, InternLifecycleStatus from,
                            InternLifecycleStatus to, UUID actorId) {
        try {
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("lifecycleStatus", from);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("lifecycleStatus", to);
            AuditLog entry = AuditLog.builder()
                    .entityType("User")
                    .entityId(userId)
                    .subjectUserId(userId)
                    .userId(actorId)
                    .action("USER_LIFECYCLE_ADVANCE")
                    .beforeJson(objectMapper.writeValueAsString(before))
                    .afterJson(objectMapper.writeValueAsString(after))
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            // Audit insert is best-effort; never block the transition.
            log.warn("[InternLifecycle] audit write failed for user={} {}->{}: {}",
                    userId, from, to, e.getMessage());
        }
    }
}
