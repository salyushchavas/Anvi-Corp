package com.anvicorp.api.repository;

import com.anvicorp.api.entity.StaffActivationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface StaffActivationTokenRepository
        extends JpaRepository<StaffActivationToken, UUID> {

    Optional<StaffActivationToken> findByTokenHash(String tokenHash);

    /**
     * Invalidate every still-live token a given user holds. Called when a
     * fresh invite is issued to the same user (the admin re-sent the
     * link) so the prior link instantly stops working.
     *
     * <p>Hibernate 6 is strict about the JPQL {@code CURRENT_TIMESTAMP}
     * function: it types the value as {@code java.sql.Timestamp} and
     * refuses the implicit assignment to an {@code Instant} column,
     * failing repository-bean validation at boot with "Cannot assign
     * expression of type 'java.sql.Timestamp' to target path 't.usedAt'
     * of type 'java.time.Instant'". Pass the timestamp as a bind
     * parameter of the correct type instead.</p>
     */
    @Modifying
    @Query("UPDATE StaffActivationToken t SET t.usedAt = :now "
            + "WHERE t.userId = :userId AND t.usedAt IS NULL")
    int markAllUnusedByUserAsInvalidated(@Param("userId") UUID userId,
                                          @Param("now") Instant now);
}
