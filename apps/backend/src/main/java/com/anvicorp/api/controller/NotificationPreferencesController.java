package com.anvicorp.api.controller;

import com.anvicorp.api.dto.users.NotificationPreferencesResponse;
import com.anvicorp.api.dto.users.UpdateNotificationPreferencesRequest;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET / PUT /api/v1/me/notification-preferences} — applies to every
 * authenticated role. Default is opt-in (both flags true); transactional
 * mail (verification, password reset, offer letters, TNC) ignores these
 * flags entirely and is always delivered.
 */
@RestController
@RequestMapping("/api/v1/me/notification-preferences")
@RequiredArgsConstructor
public class NotificationPreferencesController {

    private final UserProfileService userProfileService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public NotificationPreferencesResponse get(@AuthenticationPrincipal User caller) {
        return userProfileService.getNotificationPreferences(caller);
    }

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    public NotificationPreferencesResponse update(
            @Valid @RequestBody UpdateNotificationPreferencesRequest req,
            @AuthenticationPrincipal User caller) {
        return userProfileService.updateNotificationPreferences(caller, req);
    }
}
