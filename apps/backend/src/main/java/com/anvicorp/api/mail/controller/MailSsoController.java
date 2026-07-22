package com.anvicorp.api.mail.controller;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.mail.dto.MailSsoTokenResponse;
import com.anvicorp.api.mail.service.MailSsoService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Careers → mail SSO bridge. The URL sits under {@code /api/v1/**} (not
 * {@code /api/mail/**}) so the request is filtered by the CAREERS
 * security chain — the caller must present a valid careers JWT. The
 * identity of the target user is taken from the SecurityContext via
 * {@code @AuthenticationPrincipal}; the body is empty. No path or body
 * parameter can influence which mailbox is minted for.
 */
@RestController
@RequestMapping("/api/v1/mail")
@RequiredArgsConstructor
public class MailSsoController {

    private final MailSsoService mailSsoService;

    /**
     * Mint a fresh mail JWT + refresh token for the caller's paired
     * mailbox. Returns 404 (via {@code ResourceNotFoundException} →
     * {@code GlobalExceptionHandler}) when no active mailbox is
     * provisioned for the caller — the frontend surfaces this as a
     * "No mailbox provisioned" toast and stays put.
     */
    @PostMapping("/sso-token")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MailSsoTokenResponse> mintSsoToken(
            @AuthenticationPrincipal User caller,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(mailSsoService.mintFor(caller, httpRequest));
    }
}
