package com.anvicorp.api.mail.controller;

import com.anvicorp.api.auth.MailPrincipal;
import com.anvicorp.api.mail.dto.MailRuleEnabledRequest;
import com.anvicorp.api.mail.dto.MailRuleReorderRequest;
import com.anvicorp.api.mail.dto.MailRuleRequest;
import com.anvicorp.api.mail.dto.MailRuleResponse;
import com.anvicorp.api.mail.exception.MailApiException;
import com.anvicorp.api.mail.service.MailRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Per-account inbox-rule CRUD under the A1 @Order(1) mail chain (MAIL_USER+). All
 * routes are under {@code /api/mail/rules} — distinct from the message/folder
 * controllers, no collision. Every call is walled in {@link MailRuleService} via
 * the re-loaded actor (a foreign rule id → 404). This is a USER feature, not admin.
 */
@RestController
@RequestMapping("/api/mail/rules")
@RequiredArgsConstructor
public class MailRuleController {

    private final MailRuleService service;

    @GetMapping
    public List<MailRuleResponse> list(@AuthenticationPrincipal MailPrincipal principal) {
        return service.list(principal);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MailRuleResponse create(@AuthenticationPrincipal MailPrincipal principal,
                                   @RequestBody MailRuleRequest req) {
        return service.create(principal, req);
    }

    @PutMapping("/{id}")
    public MailRuleResponse update(@AuthenticationPrincipal MailPrincipal principal,
                                   @PathVariable String id,
                                   @RequestBody MailRuleRequest req) {
        return service.update(principal, uuid(id), req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal MailPrincipal principal, @PathVariable String id) {
        service.delete(principal, uuid(id));
    }

    @PatchMapping("/{id}/enabled")
    public MailRuleResponse setEnabled(@AuthenticationPrincipal MailPrincipal principal,
                                       @PathVariable String id,
                                       @RequestBody MailRuleEnabledRequest req) {
        return service.setEnabled(principal, uuid(id), req == null ? null : req.enabled());
    }

    @PostMapping("/reorder")
    public List<MailRuleResponse> reorder(@AuthenticationPrincipal MailPrincipal principal,
                                          @RequestBody MailRuleReorderRequest req) {
        return service.reorder(principal, req == null ? null : req.ids());
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            throw new MailApiException(HttpStatus.BAD_REQUEST, "Invalid id", "MAIL_INVALID_ID");
        }
    }
}
