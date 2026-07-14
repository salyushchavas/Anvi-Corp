package com.anvicorp.api.session;

import com.anvicorp.api.auth.JwtAuthenticationFilter;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.session.dto.SignOutEverywhereRequest;
import com.anvicorp.api.session.dto.UserSessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Session management — applies to every authenticated role (candidate, staff,
 * super-admin). Scope is always the caller; cross-user controls live on the
 * admin surface and are not exposed here.
 */
@RestController
@RequestMapping("/api/v1/me/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<UserSessionResponse> listMine(@AuthenticationPrincipal User user,
                                              HttpServletRequest request) {
        UUID currentSessionId = currentSessionId(request);
        return sessionService.listMine(user, currentSessionId);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> revoke(@PathVariable UUID id,
                                       @AuthenticationPrincipal User user) {
        sessionService.revoke(user, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sign-out-everywhere")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> signOutEverywhere(
            @RequestBody(required = false) SignOutEverywhereRequest req,
            @AuthenticationPrincipal User user,
            HttpServletRequest request) {
        UUID currentSessionId = currentSessionId(request);
        boolean includeCurrent = req != null && req.includeCurrentOrDefault();
        int count = sessionService.signOutEverywhere(user, currentSessionId, includeCurrent);
        return ResponseEntity.ok(Map.of(
                "revoked", count,
                "currentIncluded", includeCurrent));
    }

    private static UUID currentSessionId(HttpServletRequest request) {
        Object raw = request.getAttribute(JwtAuthenticationFilter.CURRENT_SESSION_ID_ATTR);
        return raw instanceof UUID u ? u : null;
    }
}
