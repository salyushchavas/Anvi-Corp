package com.anvicorp.api.auth;

import com.anvicorp.api.auth.dto.ActivateRequest;
import com.anvicorp.api.auth.dto.ActivationValidationResponse;
import com.anvicorp.api.auth.dto.AuthResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoints for the admin-issued activation-link flow.
 * Mounted under {@code /auth} (matching {@link AuthController}) so the
 * existing permitAll matchers in SecurityConfig cover the validate +
 * activate paths without requiring an authenticated principal.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthActivationController {

    private final AuthActivationService activationService;
    private final AuthCookies authCookies;

    /**
     * Confirms the link is live and returns enough context for the
     * activation page to render "Set the password for &lt;email&gt;". A
     * dead/unknown token returns 400 with a generic message — no oracle.
     */
    @GetMapping("/activate/validate")
    public ActivationValidationResponse validate(@RequestParam("token") String token) {
        return activationService.validate(token);
    }

    /**
     * Redeems the token, sets the password, issues a session. Returns
     * the same {@link AuthResponse} shape as login so the frontend can
     * persist tokens identically and route to the role dashboard.
     *
     * <p>Security Wave 2 — session cookies (access + refresh + CSRF) are
     * emitted here as well so a freshly-activated staff account lands
     * fully-authenticated on the dashboard without a subsequent /login.</p>
     */
    @PostMapping("/activate")
    public AuthResponse activate(@Valid @RequestBody ActivateRequest req,
                                 HttpServletRequest httpRequest,
                                 HttpServletResponse httpResponse) {
        AuthResponse body = activationService.activate(req, httpRequest);
        if (body != null) {
            if (body.token() != null) authCookies.writeAccessCookie(httpResponse, body.token());
            if (body.refreshToken() != null) {
                authCookies.writeRefreshCookie(httpResponse, body.refreshToken());
            }
            authCookies.writeCsrfCookie(httpResponse, AuthCookies.mintCsrfToken());
        }
        return body;
    }
}
