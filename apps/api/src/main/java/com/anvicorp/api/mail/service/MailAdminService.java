package com.anvicorp.api.mail.service;

import com.anvicorp.api.auth.MailPrincipal;
import com.anvicorp.api.auth.MailSessionTokenService;
import com.anvicorp.api.mail.dto.CreateDomainRequest;
import com.anvicorp.api.mail.dto.CreateMailboxRequest;
import com.anvicorp.api.mail.dto.MailCredentialResponse;
import com.anvicorp.api.mail.dto.MailDomainResponse;
import com.anvicorp.api.mail.dto.MailMailboxResponse;
import com.anvicorp.api.mail.dto.UpdateDomainRequest;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailAccountStatus;
import com.anvicorp.api.mail.entity.MailAuditAction;
import com.anvicorp.api.mail.entity.MailAuditLog;
import com.anvicorp.api.mail.entity.MailDomain;
import com.anvicorp.api.mail.entity.MailRole;
import com.anvicorp.api.mail.exception.MailApiException;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailAuditLogRepository;
import com.anvicorp.api.mail.repository.MailDomainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Mail admin / provisioning service. EVERY operation re-loads the acting account
 * from {@link MailPrincipal#accountId()} (never trusting the token's claimed
 * role/domain) and enforces:
 * <ul>
 *   <li><b>Walling</b> — SUPER_ADMIN acts org-wide; ADMIN is confined to its own
 *       domain. A cross-domain target is reported as 404 (anti-enumeration),
 *       not 403.</li>
 *   <li><b>Hash-only passwords</b> — plaintext exists ONLY in the show-once
 *       create/reset response; never stored, logged, or returned by a GET.</li>
 *   <li><b>Last-active-super-admin guard</b> — demoting/suspending the final
 *       active SUPER_ADMIN (or deactivating its domain) is blocked (409), under
 *       a pessimistic row lock so concurrent attempts can't jointly orphan it.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MailAdminService {

    /** Matches MailAuthService.MIN_PASSWORD_LENGTH (admin-typed passwords). */
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern LOCAL_PART = Pattern.compile("^[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?$");

    private final MailAccountRepository accountRepository;
    private final MailDomainRepository domainRepository;
    private final MailAuditLogRepository auditRepository;
    private final MailSessionTokenService sessionTokenService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.webmail.provisioning.generated-password-length:16}")
    private int generatedPasswordLength;

    // ── Mailboxes ────────────────────────────────────────────────────────

    @Transactional
    public MailCredentialResponse createMailbox(MailPrincipal principal, CreateMailboxRequest req) {
        MailAccount actor = loadActor(principal);
        assertCanActOnDomain(actor, req.domainId());

        MailDomain domain = domainRepository.findById(req.domainId())
                .orElseThrow(() -> notFound("Domain not found"));

        String localPart = req.localPart() == null ? "" : req.localPart().trim().toLowerCase(Locale.ROOT);
        if (!LOCAL_PART.matcher(localPart).matches()) {
            throw badRequest("Invalid local part", "MAIL_INVALID_LOCAL_PART");
        }
        if (accountRepository.existsByLocalPartAndDomain_Id(localPart, domain.getId())) {
            throw new MailApiException(HttpStatus.CONFLICT,
                    "A mailbox with that address already exists", "MAIL_ADDRESS_TAKEN");
        }

        String plaintext = resolvePassword(req.password());
        boolean requireChange = req.requireChangeOnFirstLogin() == null || req.requireChangeOnFirstLogin();

        MailAccount account = MailAccount.builder()
                .domain(domain)
                .localPart(localPart)
                .displayName(blankToNull(req.displayName()))
                .passwordHash(passwordEncoder.encode(plaintext))
                .role(MailRole.USER)
                .status(MailAccountStatus.ACTIVE)
                .mustChangePassword(requireChange)
                .requireChangeOnFirstLogin(requireChange)
                .build();
        account = accountRepository.save(account);

        audit(actor, MailAuditAction.MAILBOX_CREATE, "MAILBOX", account.getId(),
                "created " + email(account) + " role=USER requireChange=" + requireChange);

        return new MailCredentialResponse(
                account.getId().toString(), email(account), plaintext, requireChange);
    }

    @Transactional
    public MailCredentialResponse resetPassword(MailPrincipal principal, UUID accountId, String password) {
        MailAccount actor = loadActor(principal);
        MailAccount target = loadTargetWalled(actor, accountId);
        // ADMIN may reset USER targets only; SUPER_ADMIN may reset anyone.
        if (actor.getRole() == MailRole.ADMIN && target.getRole() != MailRole.USER) {
            throw forbidden("Admins may only reset USER mailboxes");
        }

        String plaintext = resolvePassword(password);
        target.setPasswordHash(passwordEncoder.encode(plaintext));
        target.setMustChangePassword(true);
        target.setRequireChangeOnFirstLogin(true);
        accountRepository.save(target);
        // Kill the target's sessions so the old password is dead immediately.
        sessionTokenService.revokeAllForAccount(target.getId(), "password_reset");

        audit(actor, MailAuditAction.PASSWORD_RESET, "MAILBOX", target.getId(),
                "reset password for " + email(target));

        return new MailCredentialResponse(
                target.getId().toString(), email(target), plaintext, true);
    }

    @Transactional
    public MailMailboxResponse suspend(MailPrincipal principal, UUID accountId) {
        MailAccount actor = loadActor(principal);
        MailAccount target = loadTargetWalled(actor, accountId);
        assertActorMayManageRole(actor, target);
        assertNotLastActiveSuperAdmin(target);

        target.setStatus(MailAccountStatus.SUSPENDED);
        accountRepository.save(target);
        sessionTokenService.revokeAllForAccount(target.getId(), "suspended");

        audit(actor, MailAuditAction.MAILBOX_SUSPEND, "MAILBOX", target.getId(),
                "suspended " + email(target));
        return toMailboxResponse(target);
    }

    @Transactional
    public MailMailboxResponse reactivate(MailPrincipal principal, UUID accountId) {
        MailAccount actor = loadActor(principal);
        MailAccount target = loadTargetWalled(actor, accountId);
        assertActorMayManageRole(actor, target);

        target.setStatus(MailAccountStatus.ACTIVE);
        accountRepository.save(target);

        audit(actor, MailAuditAction.MAILBOX_REACTIVATE, "MAILBOX", target.getId(),
                "reactivated " + email(target));
        return toMailboxResponse(target);
    }

    @Transactional
    public MailMailboxResponse setRole(MailPrincipal principal, UUID accountId, MailRole newRole) {
        MailAccount actor = loadActor(principal);
        MailAccount target = loadTargetWalled(actor, accountId);
        assertActorMayManageRole(actor, target);
        if (actor.getRole() == MailRole.ADMIN && newRole == MailRole.SUPER_ADMIN) {
            throw forbidden("Admins may not grant SUPER_ADMIN");
        }
        // Demoting the final active super-admin is blocked.
        if (target.getRole() == MailRole.SUPER_ADMIN && newRole != MailRole.SUPER_ADMIN) {
            assertNotLastActiveSuperAdmin(target);
        }

        MailRole old = target.getRole();
        target.setRole(newRole);
        accountRepository.save(target);

        audit(actor, MailAuditAction.ROLE_CHANGE, "MAILBOX", target.getId(),
                "role " + old + " -> " + newRole + " for " + email(target));
        return toMailboxResponse(target);
    }

    @Transactional(readOnly = true)
    public List<MailMailboxResponse> listMailboxes(MailPrincipal principal,
                                                   UUID domainId, String status, String search) {
        MailAccount actor = loadActor(principal);
        List<MailAccount> accounts;
        if (actor.getRole() == MailRole.SUPER_ADMIN) {
            accounts = domainId != null
                    ? accountRepository.findByDomain_IdOrderByLocalPartAsc(domainId)
                    : accountRepository.findAllByOrderByLocalPartAsc();
        } else {
            UUID own = actor.getDomain().getId();
            if (domainId != null && !domainId.equals(own)) {
                throw notFound("Domain not found"); // anti-enumeration
            }
            accounts = accountRepository.findByDomain_IdOrderByLocalPartAsc(own);
        }

        MailAccountStatus statusFilter = parseStatus(status);
        String needle = search == null ? null : search.trim().toLowerCase(Locale.ROOT);
        return accounts.stream()
                .filter(a -> statusFilter == null || a.getStatus() == statusFilter)
                .filter(a -> needle == null || needle.isEmpty()
                        || a.getLocalPart().toLowerCase(Locale.ROOT).contains(needle)
                        || (a.getDisplayName() != null
                            && a.getDisplayName().toLowerCase(Locale.ROOT).contains(needle)))
                .map(this::toMailboxResponse)
                .toList();
    }

    // ── Domains (SUPER_ADMIN only) ───────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MailDomainResponse> listDomains(MailPrincipal principal) {
        assertSuperAdmin(loadActor(principal));
        return domainRepository.findAllByOrderByNameAsc().stream()
                .map(this::toDomainResponse)
                .toList();
    }

    @Transactional
    public MailDomainResponse createDomain(MailPrincipal principal, CreateDomainRequest req) {
        MailAccount actor = loadActor(principal);
        assertSuperAdmin(actor);
        String name = req.name() == null ? "" : req.name().trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            throw badRequest("Domain name is required", "MAIL_INVALID_DOMAIN");
        }
        if (domainRepository.existsByName(name)) {
            throw new MailApiException(HttpStatus.CONFLICT, "Domain already exists", "MAIL_DOMAIN_TAKEN");
        }
        MailDomain domain = domainRepository.save(MailDomain.builder()
                .name(name)
                .displayName(blankToNull(req.displayName()))
                .active(true)
                .build());
        audit(actor, MailAuditAction.DOMAIN_CREATE, "DOMAIN", domain.getId(), "created domain " + name);
        return toDomainResponse(domain);
    }

    @Transactional
    public MailDomainResponse updateDomain(MailPrincipal principal, UUID domainId, UpdateDomainRequest req) {
        MailAccount actor = loadActor(principal);
        assertSuperAdmin(actor);
        MailDomain domain = domainRepository.findById(domainId)
                .orElseThrow(() -> notFound("Domain not found"));

        if (req.displayName() != null) {
            domain.setDisplayName(blankToNull(req.displayName()));
        }
        if (req.active() != null) {
            if (!req.active() && Boolean.TRUE.equals(domain.getActive())) {
                assertDeactivationDoesNotOrphanSuperAdmin(domain);
            }
            domain.setActive(req.active());
        }
        domainRepository.save(domain);
        audit(actor, MailAuditAction.DOMAIN_UPDATE, "DOMAIN", domain.getId(),
                "updated domain " + domain.getName() + " active=" + domain.getActive());
        return toDomainResponse(domain);
    }

    @Transactional
    public void deleteDomain(MailPrincipal principal, UUID domainId) {
        MailAccount actor = loadActor(principal);
        assertSuperAdmin(actor);
        MailDomain domain = domainRepository.findById(domainId)
                .orElseThrow(() -> notFound("Domain not found"));
        if (accountRepository.countByDomain_Id(domainId) > 0) {
            throw new MailApiException(HttpStatus.CONFLICT,
                    "Domain has mailboxes — deactivate it instead", "MAIL_DOMAIN_NOT_EMPTY");
        }
        domainRepository.delete(domain);
        audit(actor, MailAuditAction.DOMAIN_DELETE, "DOMAIN", domainId, "deleted domain " + domain.getName());
    }

    // ── Walling / authz helpers ──────────────────────────────────────────

    private MailAccount loadActor(MailPrincipal principal) {
        if (principal == null) {
            throw new MailApiException(HttpStatus.UNAUTHORIZED, "Authentication required", "MAIL_UNAUTHENTICATED");
        }
        return accountRepository.findById(principal.accountId())
                .orElseThrow(() -> new MailApiException(
                        HttpStatus.UNAUTHORIZED, "Authentication required", "MAIL_UNAUTHENTICATED"));
    }

    private void assertSuperAdmin(MailAccount actor) {
        if (actor.getRole() != MailRole.SUPER_ADMIN) {
            throw forbidden("Super-admin only");
        }
    }

    /** SUPER_ADMIN: any domain. ADMIN: own domain only (else 404 anti-enumeration). */
    private void assertCanActOnDomain(MailAccount actor, UUID domainId) {
        if (actor.getRole() == MailRole.SUPER_ADMIN) {
            return;
        }
        if (actor.getRole() != MailRole.ADMIN || !actor.getDomain().getId().equals(domainId)) {
            throw notFound("Domain not found");
        }
    }

    /** Load a target mailbox and enforce domain walling (cross-domain → 404). */
    private MailAccount loadTargetWalled(MailAccount actor, UUID accountId) {
        MailAccount target = accountRepository.findById(accountId)
                .orElseThrow(() -> notFound("Mailbox not found"));
        if (actor.getRole() != MailRole.SUPER_ADMIN
                && !actor.getDomain().getId().equals(target.getDomain().getId())) {
            throw notFound("Mailbox not found"); // anti-enumeration
        }
        return target;
    }

    /** An ADMIN may never act on (suspend/reactivate/role) a SUPER_ADMIN target. */
    private void assertActorMayManageRole(MailAccount actor, MailAccount target) {
        if (actor.getRole() == MailRole.ADMIN && target.getRole() == MailRole.SUPER_ADMIN) {
            throw forbidden("Admins may not manage super-admins");
        }
    }

    /**
     * Block removing the final active SUPER_ADMIN. Pessimistic-locks the active
     * super-admin rows so two concurrent demotions/suspensions serialize.
     */
    private void assertNotLastActiveSuperAdmin(MailAccount target) {
        if (target.getRole() != MailRole.SUPER_ADMIN || target.getStatus() != MailAccountStatus.ACTIVE) {
            return;
        }
        List<MailAccount> activeSupers =
                accountRepository.lockByRoleAndStatus(MailRole.SUPER_ADMIN, MailAccountStatus.ACTIVE);
        // A fallback super-admin must be able to actually authenticate — i.e. sit
        // on an ACTIVE domain (login requires domain.active=true). A super-admin
        // stranded on a deactivated domain does NOT count, otherwise demoting or
        // suspending the last REACHABLE super-admin would lock everyone out.
        // (Consistent with assertDeactivationDoesNotOrphanSuperAdmin below.)
        boolean anyOther = activeSupers.stream().anyMatch(a -> !a.getId().equals(target.getId())
                && Boolean.TRUE.equals(a.getDomain().getActive()));
        if (!anyOther) {
            throw new MailApiException(HttpStatus.CONFLICT,
                    "Cannot remove the last active super-admin", "MAIL_LAST_SUPER_ADMIN");
        }
    }

    /** Deactivating a domain must not strand the last active super-admin. */
    private void assertDeactivationDoesNotOrphanSuperAdmin(MailDomain domain) {
        List<MailAccount> activeSupers =
                accountRepository.lockByRoleAndStatus(MailRole.SUPER_ADMIN, MailAccountStatus.ACTIVE);
        boolean thisDomainHasSuper = activeSupers.stream()
                .anyMatch(a -> a.getDomain().getId().equals(domain.getId()));
        if (!thisDomainHasSuper) {
            return;
        }
        boolean reachableElsewhere = activeSupers.stream()
                .anyMatch(a -> !a.getDomain().getId().equals(domain.getId())
                        && Boolean.TRUE.equals(a.getDomain().getActive()));
        if (!reachableElsewhere) {
            throw new MailApiException(HttpStatus.CONFLICT,
                    "Cannot deactivate the domain of the last active super-admin", "MAIL_LAST_SUPER_ADMIN");
        }
    }

    // ── Misc helpers ─────────────────────────────────────────────────────

    private String resolvePassword(String provided) {
        if (provided == null || provided.isBlank()) {
            return MailPasswordGenerator.generate(generatedPasswordLength);
        }
        if (provided.length() < MIN_PASSWORD_LENGTH) {
            throw badRequest("Password must be at least " + MIN_PASSWORD_LENGTH + " characters",
                    "MAIL_WEAK_PASSWORD");
        }
        return provided;
    }

    private void audit(MailAccount actor, MailAuditAction action, String targetType,
                       UUID targetId, String detail) {
        auditRepository.save(MailAuditLog.builder()
                .actorAccountId(actor.getId())
                .action(action.name())
                .targetType(targetType)
                .targetId(targetId)
                .detail(detail)
                .build());
    }

    private MailAccountStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return MailAccountStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("Invalid status filter", "MAIL_INVALID_STATUS");
        }
    }

    private static String email(MailAccount a) {
        return a.getLocalPart() + "@" + a.getDomain().getName();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private MailMailboxResponse toMailboxResponse(MailAccount a) {
        return new MailMailboxResponse(
                a.getId().toString(),
                a.getDomain().getId().toString(),
                a.getDomain().getName(),
                a.getLocalPart(),
                email(a),
                a.getDisplayName(),
                a.getRole().name(),
                a.getStatus().name(),
                Boolean.TRUE.equals(a.getMustChangePassword()),
                Boolean.TRUE.equals(a.getRequireChangeOnFirstLogin()),
                a.getQuotaBytes() == null ? 0L : a.getQuotaBytes(),
                a.getCreatedAt());
    }

    private MailDomainResponse toDomainResponse(MailDomain d) {
        return new MailDomainResponse(
                d.getId().toString(),
                d.getName(),
                d.getDisplayName(),
                Boolean.TRUE.equals(d.getActive()),
                accountRepository.countByDomain_Id(d.getId()),
                d.getCreatedAt());
    }

    private static MailApiException notFound(String msg) {
        return new MailApiException(HttpStatus.NOT_FOUND, msg, "MAIL_NOT_FOUND");
    }

    private static MailApiException forbidden(String msg) {
        return new MailApiException(HttpStatus.FORBIDDEN, msg, "MAIL_FORBIDDEN");
    }

    private static MailApiException badRequest(String msg, String code) {
        return new MailApiException(HttpStatus.BAD_REQUEST, msg, code);
    }
}
