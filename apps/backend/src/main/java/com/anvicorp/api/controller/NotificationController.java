package com.anvicorp.api.controller;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.UserNotification;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.UserNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 7 in-app notification inbox. Backs the bell + Messages page on
 * every role surface. Each call is scoped to the caller's
 * recipient_user_id.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final UserNotificationRepository repository;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> list(
            @AuthenticationPrincipal User caller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "false") boolean unread) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page), Math.min(100, Math.max(1, size)));
        Page<UserNotification> p = unread
                ? repository.findByRecipientUserIdAndReadAtIsNullOrderByCreatedAtDesc(
                        caller.getId(), pageable)
                : repository.findByRecipientUserIdOrderByCreatedAtDesc(
                        caller.getId(), pageable);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("content", p.getContent().stream()
                .map(NotificationController::toDto).toList());
        resp.put("totalElements", p.getTotalElements());
        resp.put("totalPages", p.getTotalPages());
        resp.put("page", p.getNumber());
        resp.put("size", p.getSize());
        return resp;
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> unreadCount(@AuthenticationPrincipal User caller) {
        long n = repository.countByRecipientUserIdAndReadAtIsNull(caller.getId());
        return Map.of("unread", n);
    }

    @GetMapping("/latest")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> latest(@AuthenticationPrincipal User caller) {
        return repository.findTop5ByRecipientUserIdOrderByCreatedAtDesc(caller.getId())
                .stream().map(NotificationController::toDto).toList();
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public Map<String, Object> markRead(@PathVariable UUID id,
                                         @AuthenticationPrincipal User caller) {
        UserNotification row = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + id));
        if (!caller.getId().equals(row.getRecipientUserId())) {
            throw new ForbiddenException("Not your notification");
        }
        if (row.getReadAt() == null) {
            row.setReadAt(Instant.now());
            repository.save(row);
        }
        return toDto(row);
    }

    @PostMapping("/mark-all-read")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public Map<String, Object> markAllRead(@AuthenticationPrincipal User caller) {
        int n = repository.markAllReadFor(caller.getId(), Instant.now());
        return Map.of("marked", n);
    }

    private static Map<String, Object> toDto(UserNotification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("eventType", n.getEventType());
        m.put("title", n.getTitle());
        m.put("body", n.getBody());
        m.put("actionUrl", n.getActionUrl());
        m.put("readAt", n.getReadAt());
        m.put("createdAt", n.getCreatedAt());
        return m;
    }
}
