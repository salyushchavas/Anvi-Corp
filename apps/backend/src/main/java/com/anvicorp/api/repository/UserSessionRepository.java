package com.anvicorp.api.repository;

import com.anvicorp.api.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    /** Lookup by refresh-token hash — the auth/refresh hot path. */
    Optional<UserSession> findByRefreshTokenHash(String refreshTokenHash);

    /** Active (non-revoked + non-expired) sessions for one user, newest first. */
    @Query("SELECT s FROM UserSession s " +
            "WHERE s.userId = :userId " +
            "  AND s.revoked = false " +
            "  AND s.expiresAt > :now " +
            "ORDER BY s.lastUsedAt DESC")
    List<UserSession> findActiveByUserId(
            @Param("userId") UUID userId,
            @Param("now") Instant now);

    /**
     * Bulk-revoke every active session for a user, optionally excluding one
     * (the caller's current session, for "sign out everywhere"). Returns the
     * number of rows actually flipped.
     */
    @Modifying
    @Query("UPDATE UserSession s " +
            "SET s.revoked = true, s.revokedAt = :now, s.revokedReason = :reason " +
            "WHERE s.userId = :userId " +
            "  AND s.revoked = false " +
            "  AND (:excludeId IS NULL OR s.id <> :excludeId)")
    int revokeAllForUser(
            @Param("userId") UUID userId,
            @Param("excludeId") UUID excludeId,
            @Param("now") Instant now,
            @Param("reason") String reason);
}
