package com.anvicorp.api.repository;

import com.anvicorp.api.entity.UserNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UserNotificationRepository extends JpaRepository<UserNotification, UUID> {

    Page<UserNotification> findByRecipientUserIdOrderByCreatedAtDesc(
            UUID recipientUserId, Pageable pageable);

    Page<UserNotification> findByRecipientUserIdAndReadAtIsNullOrderByCreatedAtDesc(
            UUID recipientUserId, Pageable pageable);

    long countByRecipientUserIdAndReadAtIsNull(UUID recipientUserId);

    List<UserNotification> findTop5ByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId);

    @Modifying
    @Query("UPDATE UserNotification n SET n.readAt = :now "
            + "WHERE n.recipientUserId = :userId AND n.readAt IS NULL")
    int markAllReadFor(@Param("userId") UUID userId, @Param("now") Instant now);
}
