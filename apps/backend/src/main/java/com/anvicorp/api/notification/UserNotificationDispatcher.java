package com.anvicorp.api.notification;

import com.anvicorp.api.entity.UserNotification;
import com.anvicorp.api.repository.UserNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7 dispatcher for in-app notification rows. Distinct from the
 * existing {@code NotificationService} which is the email send + per-event
 * idempotency ledger ({@code SentNotification}). This dispatcher writes
 * one {@code UserNotification} row per recipient so the bell + Messages
 * page can render real per-user feeds with read-tracking.
 *
 * <p>Best-effort: a failure here logs but never rolls back the parent
 * transaction. Runs in REQUIRES_NEW so the inbox row survives caller
 * rollbacks.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserNotificationDispatcher {

    private final UserNotificationRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(UUID recipientUserId, String eventType, UUID subjectUserId,
                          String title, String body, String actionUrl, boolean emailSent) {
        if (recipientUserId == null) return;
        try {
            UserNotification row = UserNotification.builder()
                    .recipientUserId(recipientUserId)
                    .eventType(eventType)
                    .subjectUserId(subjectUserId)
                    .title(title)
                    .body(body)
                    .actionUrl(actionUrl)
                    .emailSent(emailSent)
                    .emailSentAt(emailSent ? Instant.now() : null)
                    .build();
            repository.save(row);
        } catch (Exception e) {
            log.warn("[UserNotif] dispatch failed (non-fatal): {}", e.getMessage());
        }
    }
}
