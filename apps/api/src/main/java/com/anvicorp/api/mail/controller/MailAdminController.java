package com.anvicorp.api.mail.controller;

import com.anvicorp.api.auth.MailPrincipal;
import com.anvicorp.api.mail.dto.CreateDomainRequest;
import com.anvicorp.api.mail.dto.CreateMailboxRequest;
import com.anvicorp.api.mail.dto.MailCredentialResponse;
import com.anvicorp.api.mail.dto.MailDomainResponse;
import com.anvicorp.api.mail.dto.MailMailboxResponse;
import com.anvicorp.api.mail.dto.ResetPasswordRequest;
import com.anvicorp.api.mail.dto.SetRoleRequest;
import com.anvicorp.api.mail.dto.UpdateDomainRequest;
import com.anvicorp.api.mail.service.MailAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Mail admin console. The {@code /api/mail/admin/**} path is already gated by
 * {@code hasAnyAuthority("MAIL_ADMIN","MAIL_SUPER_ADMIN")} in MailSecurityConfig
 * (so USERs — and pre-change principals holding only MAIL_PRECHANGE — cannot
 * reach it). Fine-grained authz + per-domain walling live in MailAdminService,
 * which re-loads the actor from the principal.
 */
@RestController
@RequestMapping("/api/mail/admin")
@RequiredArgsConstructor
public class MailAdminController {

    private final MailAdminService adminService;

    // ── Mailboxes ────────────────────────────────────────────────────────

    @GetMapping("/mailboxes")
    public ResponseEntity<List<MailMailboxResponse>> listMailboxes(
            @AuthenticationPrincipal MailPrincipal principal,
            @RequestParam(required = false) UUID domainId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(adminService.listMailboxes(principal, domainId, status, search));
    }

    @PostMapping("/mailboxes")
    @ResponseStatus(HttpStatus.CREATED)
    public MailCredentialResponse createMailbox(@AuthenticationPrincipal MailPrincipal principal,
                                                @Valid @RequestBody CreateMailboxRequest req) {
        return adminService.createMailbox(principal, req);
    }

    @PostMapping("/mailboxes/{id}/reset-password")
    public MailCredentialResponse resetPassword(@AuthenticationPrincipal MailPrincipal principal,
                                                @PathVariable UUID id,
                                                @RequestBody(required = false) ResetPasswordRequest req) {
        return adminService.resetPassword(principal, id, req == null ? null : req.password());
    }

    @PostMapping("/mailboxes/{id}/suspend")
    public MailMailboxResponse suspend(@AuthenticationPrincipal MailPrincipal principal,
                                       @PathVariable UUID id) {
        return adminService.suspend(principal, id);
    }

    @PostMapping("/mailboxes/{id}/reactivate")
    public MailMailboxResponse reactivate(@AuthenticationPrincipal MailPrincipal principal,
                                          @PathVariable UUID id) {
        return adminService.reactivate(principal, id);
    }

    @PatchMapping("/mailboxes/{id}/role")
    public MailMailboxResponse setRole(@AuthenticationPrincipal MailPrincipal principal,
                                       @PathVariable UUID id,
                                       @Valid @RequestBody SetRoleRequest req) {
        return adminService.setRole(principal, id, req.role());
    }

    // ── Domains (SUPER_ADMIN only — enforced in the service) ──────────────

    @GetMapping("/domains")
    public ResponseEntity<List<MailDomainResponse>> listDomains(
            @AuthenticationPrincipal MailPrincipal principal) {
        return ResponseEntity.ok(adminService.listDomains(principal));
    }

    @PostMapping("/domains")
    @ResponseStatus(HttpStatus.CREATED)
    public MailDomainResponse createDomain(@AuthenticationPrincipal MailPrincipal principal,
                                           @Valid @RequestBody CreateDomainRequest req) {
        return adminService.createDomain(principal, req);
    }

    @PatchMapping("/domains/{id}")
    public MailDomainResponse updateDomain(@AuthenticationPrincipal MailPrincipal principal,
                                           @PathVariable UUID id,
                                           @RequestBody UpdateDomainRequest req) {
        return adminService.updateDomain(principal, id, req);
    }

    @DeleteMapping("/domains/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDomain(@AuthenticationPrincipal MailPrincipal principal,
                             @PathVariable UUID id) {
        adminService.deleteDomain(principal, id);
    }
}
