package com.anvicorp.api.mail.controller;

import com.anvicorp.api.mail.auth.MailPrincipal;
import com.anvicorp.api.mail.dto.MailCustomFolderRequest;
import com.anvicorp.api.mail.dto.MailCustomFolderResponse;
import com.anvicorp.api.mail.dto.MailMessageSummary;
import com.anvicorp.api.mail.dto.MailPage;
import com.anvicorp.api.mail.exception.MailApiException;
import com.anvicorp.api.mail.service.MailCustomFolderService;
import com.anvicorp.api.mail.service.MailMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Custom-folder CRUD + listing under the A1 @Order(1) mail chain (MAIL_USER+).
 * These routes ({@code GET/POST /api/mail/folders}, {@code PUT/DELETE
 * /api/mail/folders/{id}}, {@code GET /api/mail/folders/{id}/messages}) are
 * distinct from MailMessageController's system-folder listing
 * {@code GET /api/mail/folders/{folder}} (enum name) — different method/shape,
 * so no route collision.
 */
@RestController
@RequestMapping("/api/mail/folders")
@RequiredArgsConstructor
public class MailFolderController {

    private final MailCustomFolderService service;
    private final MailMessageService messageService;

    @GetMapping
    public List<MailCustomFolderResponse> list(@AuthenticationPrincipal MailPrincipal principal) {
        return service.list(principal);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MailCustomFolderResponse create(@AuthenticationPrincipal MailPrincipal principal,
                                           @RequestBody MailCustomFolderRequest req) {
        return service.create(principal, req);
    }

    @PutMapping("/{id}")
    public MailCustomFolderResponse rename(@AuthenticationPrincipal MailPrincipal principal,
                                           @PathVariable String id,
                                           @RequestBody MailCustomFolderRequest req) {
        return service.rename(principal, uuid(id), req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal MailPrincipal principal, @PathVariable String id) {
        service.delete(principal, uuid(id));
    }

    @GetMapping("/{id}/messages")
    public MailPage<MailMessageSummary> messages(@AuthenticationPrincipal MailPrincipal principal,
                                                 @PathVariable String id,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "0") int size) {
        return messageService.listCustomFolder(principal, uuid(id), page, size);
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            throw new MailApiException(HttpStatus.BAD_REQUEST, "Invalid id", "MAIL_INVALID_ID");
        }
    }
}
