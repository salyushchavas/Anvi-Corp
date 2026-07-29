package com.anvicorp.api.auth;

import com.anvicorp.api.auth.dto.AuthResponse;
import com.anvicorp.api.auth.dto.ForgotPasswordRequest;
import com.anvicorp.api.auth.dto.LoginRequest;
import com.anvicorp.api.auth.dto.MeResponse;
import com.anvicorp.api.auth.dto.RefreshTokenRequest;
import com.anvicorp.api.auth.dto.RegisterRequest;
import com.anvicorp.api.auth.dto.ResendVerificationRequest;
import com.anvicorp.api.auth.dto.ResetPasswordRequest;
import com.anvicorp.api.auth.dto.VerifyEmailRequest;
import com.anvicorp.api.auth.dto.VerifyEmailResponse;
import com.anvicorp.api.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req,
                                                 HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.register(req, httpRequest));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req,
                                              HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.login(req, httpRequest));
    }

    /**
     * Exchange a refresh token for a fresh access+refresh pair. The presented
     * refresh token is revoked on success (rotation); a replayed or revoked
     * token returns 401 and the device is effectively signed out.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest req,
                                                HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.refresh(req.refreshToken(), httpRequest));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        authService.forgotPassword(req);
        return ResponseEntity.ok(Map.of("message",
                "If the email is registered, a reset code has been sent"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req);
        return ResponseEntity.ok(Map.of("message", "Password reset successful"));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<VerifyEmailResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
        return ResponseEntity.ok(authService.verifyEmail(req));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification(
            @Valid @RequestBody ResendVerificationRequest req) {
        authService.resendVerification(req);
        // Always 200 — we don't reveal whether an account exists for that email.
        return ResponseEntity.ok(Map.of("message",
                "If the account exists and is unverified, a new code has been sent"));
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(authService.me(user));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> handleAuthException(AuthException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of("error", ex.getMessage()));
    }
}
