package com.anvicorp.api.controller;

import com.anvicorp.api.dto.users.ChangePasswordRequest;
import com.anvicorp.api.dto.users.NotificationPreferencesResponse;
import com.anvicorp.api.dto.users.UpdateNotificationPreferencesRequest;
import com.anvicorp.api.dto.users.UpdateProfileRequest;
import com.anvicorp.api.dto.users.UserProfileResponse;
import com.anvicorp.api.auth.JwtAuthenticationFilter;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.UserProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Current-user profile endpoints. Read + edit personal info and change
 * password. Distinct from the {@code /auth/me} JWT-bootstrap endpoint —
 * that one stays as-is so the auth client doesn't need to change.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final com.anvicorp.api.auth.RegistrationRateLimiter rateLimiter;

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public UserProfileResponse getMe(@AuthenticationPrincipal User caller) {
        return userProfileService.getProfile(caller);
    }

    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public UserProfileResponse updateMe(@Valid @RequestBody UpdateProfileRequest req,
                                        @AuthenticationPrincipal User caller) {
        return userProfileService.updateProfile(caller, req);
    }

    @PostMapping("/me/change-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                               @AuthenticationPrincipal User caller,
                                               HttpServletRequest request) {
        // Security Wave 3 — per-user throttle (3/user/hour). A compromised
        // session cannot rapid-cycle passwords to defeat the "revoke other
        // sessions" side-effect of a successful change.
        rateLimiter.enforcePasswordChange(caller == null ? null : caller.getId());
        Object raw = request.getAttribute(JwtAuthenticationFilter.CURRENT_SESSION_ID_ATTR);
        UUID currentSessionId = raw instanceof UUID u ? u : null;
        userProfileService.changePassword(caller, req, currentSessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Self-service GitHub username — the intern types it once on their
     * assignment page so the TE can invite them as a repository collaborator
     * out-of-band on GitHub. Validated against GitHub's username rules via
     * the request DTO's @Pattern.
     */
    @PutMapping("/me/github-username")
    @PreAuthorize("isAuthenticated()")
    public java.util.Map<String, Object> setGithubUsername(
            @Valid @RequestBody
            com.anvicorp.api.dto.user.SetGithubUsernameRequest req,
            @AuthenticationPrincipal User caller) {
        return userProfileService.setGithubUsername(caller, req.githubUsername());
    }

    /**
     * Self-service Zoom host email — staff users set this so ZoomService
     * uses their own licensed Zoom seat as the meeting host
     * (POST /users/{userId}/meetings) instead of falling back to the
     * service account. Validated as an email on the DTO.
     */
    @PutMapping("/me/zoom-email")
    @PreAuthorize("isAuthenticated()")
    public java.util.Map<String, Object> setZoomEmail(
            @Valid @RequestBody
            com.anvicorp.api.dto.user.SetZoomEmailRequest req,
            @AuthenticationPrincipal User caller) {
        return userProfileService.setZoomEmail(caller, req.zoomEmail());
    }
}
