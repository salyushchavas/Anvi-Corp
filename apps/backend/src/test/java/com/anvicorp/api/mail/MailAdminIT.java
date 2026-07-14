package com.anvicorp.api.mail;

import com.anvicorp.api.auth.MailPrincipal;
import com.anvicorp.api.mail.dto.CreateDomainRequest;
import com.anvicorp.api.mail.dto.CreateMailboxRequest;
import com.anvicorp.api.mail.dto.MailCredentialResponse;
import com.anvicorp.api.mail.dto.MailLoginRequest;
import com.anvicorp.api.mail.dto.MailMailboxResponse;
import com.anvicorp.api.mail.dto.UpdateDomainRequest;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailAccountStatus;
import com.anvicorp.api.mail.entity.MailDomain;
import com.anvicorp.api.mail.entity.MailRole;
import com.anvicorp.api.mail.exception.MailApiException;
import com.anvicorp.api.mail.exception.MailAuthException;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailAuditLogRepository;
import com.anvicorp.api.mail.repository.MailDomainRepository;
import com.anvicorp.api.mail.repository.MailMailboxEntryRepository;
import com.anvicorp.api.mail.repository.MailMessageRecipientRepository;
import com.anvicorp.api.mail.repository.MailMessageRepository;
import com.anvicorp.api.mail.repository.MailRefreshTokenRepository;
import com.anvicorp.api.mail.service.MailAdminService;
import com.anvicorp.api.mail.service.MailAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A3 admin/provisioning verification against in-memory H2. Drives
 * {@link MailAdminService} with constructed principals and uses
 * {@link MailAuthService} to prove login/suspend/reset behavior end-to-end.
 * Seeds a super-admin + an admin in domain A, plus a user in domain B for the
 * cross-domain walling checks.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:anvi_a3;DB_CLOSE_DELAY=-1",
        "app.webmail.seed.admin-enabled=false"
})
class MailAdminIT {

    @Autowired MailAdminService admin;
    @Autowired MailAuthService auth;
    @Autowired MailAccountRepository accounts;
    @Autowired MailDomainRepository domains;
    @Autowired MailAuditLogRepository audits;
    @Autowired MailRefreshTokenRepository refreshTokens;
    @Autowired MailMailboxEntryRepository entries;
    @Autowired MailMessageRecipientRepository recipients;
    @Autowired MailMessageRepository messages;
    @Autowired PasswordEncoder enc;

    private MailDomain domA, domB;
    private MailAccount superA, adminA, userB;
    private MailPrincipal pSuper, pAdminA;

    @BeforeEach
    void seed() {
        audits.deleteAll();
        entries.deleteAll();
        recipients.deleteAll();
        messages.deleteAll();
        refreshTokens.deleteAll();
        accounts.deleteAll();
        domains.deleteAll();

        domA = domains.save(MailDomain.builder().name("anvicorp.com").displayName("Anvi").active(true).build());
        domB = domains.save(MailDomain.builder().name("other.com").displayName("Other").active(true).build());
        superA = account(domA, "super", MailRole.SUPER_ADMIN, "SuperPass123");
        adminA = account(domA, "admin", MailRole.ADMIN, "AdminPass123");
        userB = account(domB, "user", MailRole.USER, "UserBPass123");
        pSuper = principal(superA);
        pAdminA = principal(adminA);
    }

    private MailAccount account(MailDomain d, String local, MailRole role, String pw) {
        return accounts.save(MailAccount.builder()
                .domain(d).localPart(local).displayName(local)
                .passwordHash(enc.encode(pw))
                .role(role).status(MailAccountStatus.ACTIVE)
                .mustChangePassword(false).requireChangeOnFirstLogin(false)
                .build());
    }

    private MailPrincipal principal(MailAccount a) {
        return new MailPrincipal(a.getId(), a.getLocalPart() + "@" + a.getDomain().getName(),
                a.getDisplayName(), a.getDomain().getId(), a.getRole(), false);
    }

    // ── 1. Create with typed password: hash-only, show-once, must-change ──────
    @Test
    void createMailbox_typedPassword_storesHashOnly_returnsOnce() {
        MailCredentialResponse cred = admin.createMailbox(pSuper, new CreateMailboxRequest(
                domA.getId(), "alice", "Alice", "TypedPass123", null));

        assertEquals("alice@anvicorp.com", cred.email());
        assertEquals("TypedPass123", cred.oneTimePassword()); // shown once
        assertTrue(cred.mustChangePassword()); // default requireChange = true

        MailAccount saved = accounts.findById(UUID.fromString(cred.accountId())).orElseThrow();
        assertTrue(saved.getPasswordHash().startsWith("$2"), "stored as BCrypt hash");
        assertNotEquals("TypedPass123", saved.getPasswordHash(), "never the plaintext");
        assertTrue(saved.getMustChangePassword());

        // The created user can log in with the one-time password.
        assertTrue(auth.login(new MailLoginRequest("alice@anvicorp.com", "TypedPass123"), null)
                .mustChangePassword());
    }

    // ── 2. Create with no password → generated; requireChange OFF → no gate ──
    @Test
    void createMailbox_generatedPassword_andRequireChangeOff() {
        MailCredentialResponse cred = admin.createMailbox(pSuper, new CreateMailboxRequest(
                domA.getId(), "bob", "Bob", null, false));

        assertTrue(cred.oneTimePassword() != null && cred.oneTimePassword().length() >= 12, "generated strong pw");
        assertFalse(cred.mustChangePassword()); // requireChange = false

        MailAccount saved = accounts.findById(UUID.fromString(cred.accountId())).orElseThrow();
        assertTrue(saved.getPasswordHash().startsWith("$2"));
        assertFalse(saved.getMustChangePassword());
        // Generated password actually works.
        assertFalse(auth.login(new MailLoginRequest("bob@anvicorp.com", cred.oneTimePassword()), null)
                .mustChangePassword());
    }

    // ── 3. Reset: new pw once, revoke tokens, old pw fails, audit has no pw ───
    @Test
    void reset_revokesTokens_oldPasswordFails_auditHasNoPlaintext() {
        MailCredentialResponse created = admin.createMailbox(pSuper, new CreateMailboxRequest(
                domA.getId(), "carol", "Carol", "InitialPass1", false));
        // Carol logs in → she now has an active refresh token.
        String oldRefresh = auth.login(new MailLoginRequest("carol@anvicorp.com", "InitialPass1"), null).refreshToken();

        MailCredentialResponse reset = admin.resetPassword(pSuper, UUID.fromString(created.accountId()), "NewPass456");
        assertEquals("NewPass456", reset.oneTimePassword());
        assertTrue(reset.mustChangePassword());

        // Old refresh token revoked; old password rejected; new password works.
        // (The auth layer throws MailAuthException; the admin layer throws MailApiException.)
        assertThrows(MailAuthException.class, () -> auth.refresh(oldRefresh, null));
        assertThrows(MailAuthException.class,
                () -> auth.login(new MailLoginRequest("carol@anvicorp.com", "InitialPass1"), null));
        assertTrue(auth.login(new MailLoginRequest("carol@anvicorp.com", "NewPass456"), null).mustChangePassword());

        // Audit trail exists but NEVER contains a plaintext password.
        boolean anyPlaintext = audits.findAll().stream()
                .anyMatch(a -> a.getDetail() != null
                        && (a.getDetail().contains("InitialPass1") || a.getDetail().contains("NewPass456")));
        assertFalse(anyPlaintext, "audit detail must never contain a password");
        assertTrue(audits.findAll().stream().anyMatch(a -> a.getAction().equals("PASSWORD_RESET")));
    }

    // ── 4. Walling: ADMIN cannot touch another domain (404), SUPER can ───────
    @Test
    void walling_adminConfinedToOwnDomain_crossDomainIs404() {
        UUID targetB = userB.getId();

        assertEquals(404, assertThrows(MailApiException.class, () -> admin.createMailbox(pAdminA,
                new CreateMailboxRequest(domB.getId(), "x", "X", "Whatever12", null))).getStatus().value());
        assertEquals(404, assertThrows(MailApiException.class,
                () -> admin.resetPassword(pAdminA, targetB, "Whatever12")).getStatus().value());
        assertEquals(404, assertThrows(MailApiException.class,
                () -> admin.suspend(pAdminA, targetB)).getStatus().value());
        assertEquals(404, assertThrows(MailApiException.class,
                () -> admin.setRole(pAdminA, targetB, MailRole.ADMIN)).getStatus().value());

        // Target in domain B is untouched.
        MailAccount stillB = accounts.findById(targetB).orElseThrow();
        assertEquals(MailAccountStatus.ACTIVE, stillB.getStatus());
        assertEquals(MailRole.USER, stillB.getRole());

        // SUPER_ADMIN acts org-wide.
        assertEquals("SUSPENDED", admin.suspend(pSuper, targetB).status());
    }

    // ── 5. Suspend blocks login; reactivate restores ─────────────────────────
    @Test
    void suspend_blocksLogin_reactivateRestores() {
        MailCredentialResponse dave = admin.createMailbox(pSuper, new CreateMailboxRequest(
                domA.getId(), "dave", "Dave", "DavePass123", false));
        UUID daveId = UUID.fromString(dave.accountId());
        assertFalse(auth.login(new MailLoginRequest("dave@anvicorp.com", "DavePass123"), null).mustChangePassword());

        admin.suspend(pSuper, daveId);
        assertThrows(MailAuthException.class,
                () -> auth.login(new MailLoginRequest("dave@anvicorp.com", "DavePass123"), null));

        admin.reactivate(pSuper, daveId);
        assertEquals("dave@anvicorp.com",
                auth.login(new MailLoginRequest("dave@anvicorp.com", "DavePass123"), null).email());
    }

    // ── 6. Role rules + last-active-super-admin guard ─────────────────────────
    @Test
    void role_adminLimits_and_lastSuperAdminGuard() {
        MailCredentialResponse erin = admin.createMailbox(pSuper, new CreateMailboxRequest(
                domA.getId(), "erin", "Erin", "ErinPass1234", false));
        UUID erinId = UUID.fromString(erin.accountId());

        // ADMIN may not grant SUPER_ADMIN.
        assertEquals(403, assertThrows(MailApiException.class,
                () -> admin.setRole(pAdminA, erinId, MailRole.SUPER_ADMIN)).getStatus().value());
        // ADMIN may not act on a SUPER_ADMIN target (same domain).
        assertEquals(403, assertThrows(MailApiException.class,
                () -> admin.setRole(pAdminA, superA.getId(), MailRole.USER)).getStatus().value());

        // Last active super-admin cannot be demoted or suspended (409).
        assertEquals(409, assertThrows(MailApiException.class,
                () -> admin.setRole(pSuper, superA.getId(), MailRole.USER)).getStatus().value());
        assertEquals(409, assertThrows(MailApiException.class,
                () -> admin.suspend(pSuper, superA.getId())).getStatus().value());

        // With a SECOND super-admin, demotion of the first is allowed.
        admin.setRole(pSuper, erinId, MailRole.SUPER_ADMIN);
        assertEquals("USER", admin.setRole(pSuper, superA.getId(), MailRole.USER).role());
    }

    // ── 6b. Guard: a super-admin on a DEACTIVATED domain is not a fallback ───
    @Test
    void lastReachableSuperAdmin_strandedFallbackDoesNotCount() {
        // Second super-admin on domain B, then deactivate domain B so it can't log in.
        MailCredentialResponse superB = admin.createMailbox(pSuper, new CreateMailboxRequest(
                domB.getId(), "superb", "SuperB", "SuperbPass12", false));
        admin.setRole(pSuper, UUID.fromString(superB.accountId()), MailRole.SUPER_ADMIN);
        admin.updateDomain(pSuper, domB.getId(), new UpdateDomainRequest(null, false));

        // superA is now the last REACHABLE super-admin (superB is stranded on the
        // deactivated domain and cannot authenticate) → demotion must be blocked.
        assertEquals(409, assertThrows(MailApiException.class,
                () -> admin.setRole(pSuper, superA.getId(), MailRole.USER)).getStatus().value());
        assertEquals(409, assertThrows(MailApiException.class,
                () -> admin.suspend(pSuper, superA.getId())).getStatus().value());
    }

    // ── 7. Domains: super creates, admin cannot, non-empty delete → 409 ──────
    @Test
    void domains_superOnly_and_nonEmptyDeleteBlocked() {
        assertEquals("new.com", admin.createDomain(pSuper, new CreateDomainRequest("new.com", "New")).name());
        // ADMIN cannot create a domain.
        assertEquals(403, assertThrows(MailApiException.class,
                () -> admin.createDomain(pAdminA, new CreateDomainRequest("nope.com", "Nope"))).getStatus().value());

        // Non-empty domain delete blocked (domA has super+admin).
        assertEquals(409, assertThrows(MailApiException.class,
                () -> admin.deleteDomain(pSuper, domA.getId())).getStatus().value());

        // Empty domain delete succeeds.
        UUID newId = UUID.fromString(admin.listDomains(pSuper).stream()
                .filter(d -> d.name().equals("new.com")).findFirst().orElseThrow().id());
        admin.deleteDomain(pSuper, newId);
        assertTrue(admin.listDomains(pSuper).stream().noneMatch(d -> d.name().equals("new.com")));
    }

    // ── 8. List is domain-scoped for ADMIN and carries no password field ─────
    @Test
    void list_adminScopedToOwnDomain_noPasswordLeak() {
        List<MailMailboxResponse> asSuper = admin.listMailboxes(pSuper, null, null, null);
        assertTrue(asSuper.stream().anyMatch(m -> m.email().equals("user@other.com"))); // org-wide sees domain B

        List<MailMailboxResponse> asAdmin = admin.listMailboxes(pAdminA, null, null, null);
        assertTrue(asAdmin.stream().allMatch(m -> m.domainName().equals("anvicorp.com")), "admin sees only own domain");
        assertTrue(asAdmin.stream().noneMatch(m -> m.email().equals("user@other.com")));

        // ADMIN asking for another domain → 404 (anti-enumeration).
        assertEquals(404, assertThrows(MailApiException.class,
                () -> admin.listMailboxes(pAdminA, domB.getId(), null, null)).getStatus().value());

        // MailMailboxResponse has no password component at all (compile-time guarantee).
        assertTrue(asSuper.stream().allMatch(m -> m.role() != null && m.status() != null));
    }

    @Test
    void updateDomain_deactivate_isSuperOnly() {
        // sanity: super can deactivate a spare domain; admin cannot update.
        admin.createDomain(pSuper, new CreateDomainRequest("spare.com", "Spare"));
        UUID spareId = UUID.fromString(admin.listDomains(pSuper).stream()
                .filter(d -> d.name().equals("spare.com")).findFirst().orElseThrow().id());
        assertFalse(admin.updateDomain(pSuper, spareId, new UpdateDomainRequest(null, false)).active());
        assertEquals(403, assertThrows(MailApiException.class,
                () -> admin.updateDomain(pAdminA, spareId, new UpdateDomainRequest(null, true))).getStatus().value());
    }
}
