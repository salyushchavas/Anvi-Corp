package com.anvicorp.api.repository;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /** Users that include the given role in their {@code roles} set. */
    @Query("SELECT u FROM User u JOIN u.roles r WHERE r = :role ORDER BY u.fullName")
    List<User> findByRole(@Param("role") UserRole role);

    /** Phase 4 — activation job filter. */
    List<User> findByLifecycleStatus(InternLifecycleStatus status);

    /**
     * Bot-mitigation — feeds the scheduled unverified-account purge in
     * {@code UnverifiedAccountPurgeRunner}. Returns accounts that:
     *   1. never verified their email ({@code emailVerified = false}),
     *   2. hold ONLY the {@code INTERN} role (staff accounts are seeded
     *      pre-verified; if a real staff row is somehow unverified it
     *      shouldn't be scoped out by an automated cleanup), AND
     *   3. were created before {@code cutoff} — the last-chance window
     *      after which the address is considered abandoned.
     * Ordered by creation time so the runner can log a stable head.
     */
    @Query("SELECT u FROM User u "
            + "WHERE u.emailVerified = false "
            + "AND u.createdAt < :cutoff "
            + "AND SIZE(u.roles) = 1 "
            + "AND :internRole MEMBER OF u.roles "
            + "ORDER BY u.createdAt ASC")
    List<User> findUnverifiedInternsCreatedBefore(
            @Param("cutoff") Instant cutoff,
            @Param("internRole") UserRole internRole);
}
