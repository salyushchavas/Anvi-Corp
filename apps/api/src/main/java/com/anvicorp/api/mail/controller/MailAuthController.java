package com.anvicorp.api.mail.controller;

import com.anvicorp.api.mail.dto.MailAuthResponse;
import com.anvicorp.api.mail.dto.MailLoginRequest;
import com.anvicorp.api.mail.dto.MailRefreshRequest;
import com.anvicorp.api.mail.service.MailAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public mail auth endpoints (the {@code /api/mail/auth/**} permitAll surface). */
@RestController
@RequestMapping("/api/mail/auth")
@RequiredArgsConstructor
public class MailAuthController {

    private final MailAuthService mailAuthService;

    @PostMapping("/login")
    public ResponseEntity<MailAuthResponse> login(@Valid @RequestBody MailLoginRequest req,
                                                  HttpServletRequest httpRequest) {
        return ResponseEntity.ok(mailAuthService.login(req, httpRequest));
    }

    @PostMapping("/refresh")
    public ResponseEntity<MailAuthResponse> refresh(@Valid @RequestBody MailRefreshRequest req,
                                                    HttpServletRequest httpRequest) {
        return ResponseEntity.ok(mailAuthService.refresh(req.refreshToken(), httpRequest));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody MailRefreshRequest req) {
        mailAuthService.logout(req.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
