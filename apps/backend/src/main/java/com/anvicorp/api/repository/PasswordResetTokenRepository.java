package com.anvicorp.api.repository;

import com.anvicorp.api.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    /**
     * Find the most recent unused reset code matching {@code (userId, token)}.
     * Codes are scoped per-user (not globally unique), so lookup must include
     * the user; ordering by createdAt DESC ensures a re-issued code wins over
     * a stale one during the short overlap window before invalidation lands.
     */
    Optional<PasswordResetToken> findFirstByUserIdAndTokenAndUsedFalseOrderByCreatedAtDesc(
            UUID userId, String token);

    /**
     * Mark every unused code for a user as used. Called before issuing a fresh
     * code so previously-emailed codes stop working the moment a new one is
     * generated (limits the window a leaked code can be used).
     */
    @Modifying
    @Query("update PasswordResetToken t set t.used = true "
            + "where t.userId = :userId and t.used = false")
    int invalidateActiveForUser(@Param("userId") UUID userId);
}
