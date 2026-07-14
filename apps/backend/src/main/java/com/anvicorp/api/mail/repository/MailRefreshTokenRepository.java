package com.anvicorp.api.mail.repository;

import com.anvicorp.api.mail.entity.MailRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MailRefreshTokenRepository extends JpaRepository<MailRefreshToken, UUID> {

    Optional<MailRefreshToken> findByRefreshTokenHash(String refreshTokenHash);

    @Modifying
    @Query("UPDATE MailRefreshToken t SET t.revoked = true, t.revokedAt = :now, t.revokedReason = :reason "
            + "WHERE t.accountId = :accountId AND t.revoked = false")
    int revokeAllForAccount(@Param("accountId") UUID accountId,
                            @Param("now") Instant now,
                            @Param("reason") String reason);
}
