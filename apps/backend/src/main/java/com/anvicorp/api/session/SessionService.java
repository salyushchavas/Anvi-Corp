package com.anvicorp.api.session;

import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.UserSession;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.UserSessionRepository;
import com.anvicorp.api.session.dto.UserSessionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Session-management surface for the calling user. Scope is always the
 * caller — admin cross-user session controls live elsewhere (out of scope
 * for this build).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionService {

    private final UserSessionRepository sessionRepository;
    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public List<UserSessionResponse> listMine(User caller, UUID currentSessionId) {
        if (caller == null) return List.of();
        List<UserSession> active = sessionRepository.findActiveByUserId(caller.getId(), Instant.now());
        return active.stream()
                .map(s -> toResponse(s, currentSessionId))
                .toList();
    }

    @Transactional
    public void revoke(User caller, UUID sessionId) {
        if (caller == null || sessionId == null) return;
        UserSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) return;                          // already gone
        if (!session.getUserId().equals(caller.getId())) {
            // Don't leak existence — silent no-op for cross-user attempts.
            log.warn("User {} attempted to revoke another user's session {}",
                    caller.getId(), sessionId);
            return;
        }
        if (Boolean.TRUE.equals(session.getRevoked())) return; // idempotent
        session.setRevoked(true);
        session.setRevokedAt(Instant.now());
        session.setRevokedReason("user_revoked");
        sessionRepository.save(session);
        writeAudit(sessionId, "SESSION_REVOKED", caller.getId(), "user_revoked");
    }

    /**
     * Bulk-revoke. Returns the count of sessions actually flipped. The
     * caller's current session is excluded unless {@code includeCurrent} is
     * true.
     */
    @Transactional
    public int signOutEverywhere(User caller, UUID currentSessionId, boolean includeCurrent) {
        if (caller == null) return 0;
        UUID excludeId = includeCurrent ? null : currentSessionId;
        int n = sessionRepository.revokeAllForUser(
                caller.getId(), excludeId, Instant.now(), "sign_out_everywhere");
        writeAudit(caller.getId(), "SESSION_SIGN_OUT_EVERYWHERE", caller.getId(),
                "count=" + n + ",includeCurrent=" + includeCurrent);
        return n;
    }

    // ── Mapping ────────────────────────────────────────────────────────────

    private UserSessionResponse toResponse(UserSession s, UUID currentSessionId) {
        return UserSessionResponse.builder()
                .id(s.getId())
                .createdAt(s.getCreatedAt())
                .lastUsedAt(s.getLastUsedAt())
                .userAgent(s.getUserAgent())
                .deviceLabel(parseDevice(s.getUserAgent()))
                .ip(s.getIp())
                .current(currentSessionId != null && currentSessionId.equals(s.getId()))
                .build();
    }

    /**
     * Cheap parse of a User-Agent string into a human label.
     * Order matters — iPad before Mac, Edge before Chrome, etc. Returns
     * {@code "Unknown device"} for empty input so the row still has something
     * to render.
     */
    static String parseDevice(String ua) {
        if (ua == null || ua.isBlank()) return "Unknown device";
        String s = ua;
        String browser;
        if (s.contains("Edg/") || s.contains("Edge")) browser = "Edge";
        else if (s.contains("OPR/") || s.contains("Opera")) browser = "Opera";
        else if (s.contains("Firefox")) browser = "Firefox";
        else if (s.contains("Chrome")) browser = "Chrome";
        else if (s.contains("Safari")) browser = "Safari";
        else browser = "Browser";

        String os;
        if (s.contains("iPhone")) os = "iPhone";
        else if (s.contains("iPad")) os = "iPad";
        else if (s.contains("Android")) os = "Android";
        else if (s.contains("Windows")) os = "Windows";
        else if (s.contains("Mac OS X") || s.contains("Macintosh")) os = "macOS";
        else if (s.contains("Linux")) os = "Linux";
        else os = "Unknown OS";
        return browser + " on " + os;
    }

    private void writeAudit(UUID entityId, String action, UUID userId, String snapshot) {
        try {
            AuditLog row = AuditLog.builder()
                    .entityType("UserSession")
                    .entityId(entityId)
                    .action(action)
                    .userId(userId)
                    .afterJson(snapshot != null ? "{\"info\":\"" + snapshot + "\"}" : null)
                    .build();
            auditLogRepository.save(row);
        } catch (Exception e) {
            log.warn("Failed to write {} audit row (non-fatal): {}", action, e.getMessage());
        }
    }
}
