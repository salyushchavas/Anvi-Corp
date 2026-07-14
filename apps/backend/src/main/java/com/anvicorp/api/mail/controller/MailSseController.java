package com.anvicorp.api.mail.controller;

import com.anvicorp.api.mail.auth.MailPrincipal;
import com.anvicorp.api.mail.exception.MailApiException;
import com.anvicorp.api.mail.service.MailSseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Real-time event stream (A8) under the A1 @Order(1) mail chain (MAIL_USER+). The
 * chain authorizes only the initial REQUEST dispatch (shouldFilterAllDispatcherTypes
 * false), so the async SSE writes aren't re-authorized — the caller is authenticated
 * once at connect. Walled: a stream is registered for the caller's OWN account id, and
 * events are only ever published to that account's streams.
 */
@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
public class MailSseController {

    private final MailSseService sseService;

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthenticationPrincipal MailPrincipal principal) {
        if (principal == null) {
            throw new MailApiException(HttpStatus.UNAUTHORIZED, "Authentication required", "MAIL_UNAUTHENTICATED");
        }
        return sseService.register(principal.accountId());
    }
}
