package com.anvicorp.api.repository;

import com.anvicorp.api.entity.StaffActivationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface StaffActivationTokenRepository
        extends JpaRepository<StaffActivationToken, UUID> {

    Optional<StaffActivationToken> findByTokenHash(String tokenHash);

    /**
     * Invalidate every still-live token a given user holds. Called when a
     * fresh invite is issued to the same user (the admin re-sent the
     * link) so the prior link instantly stops working.
     */
    @Modifying
    @Query("UPDATE StaffActivationToken t SET t.usedAt = CURRENT_TIMESTAMP "
            + "WHERE t.userId = :userId AND t.usedAt IS NULL")
    int markAllUnusedByUserAsInvalidated(@Param("userId") UUID userId);
}
