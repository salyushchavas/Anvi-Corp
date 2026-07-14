package com.anvicorp.api.repository;

import com.anvicorp.api.entity.SentNotification;
import com.anvicorp.api.notification.NotificationEventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SentNotificationRepository extends JpaRepository<SentNotification, UUID> {

    boolean existsByEventTypeAndTargetId(NotificationEventType eventType, UUID targetId);

    /**
     * Most-recent notifications addressed to a specific recipient (typically
     * the candidate's email). Drives the candidate-dashboard "recent updates"
     * feed in Change 2 — caller passes a small Pageable to bound the result
     * (LIMIT N). Bulk fan-outs (HR/Ops comma-joined recipient strings) are
     * naturally excluded by the equality match on email.
     */
    List<SentNotification> findByRecipientOrderBySentAtDesc(String recipient, Pageable pageable);
}
