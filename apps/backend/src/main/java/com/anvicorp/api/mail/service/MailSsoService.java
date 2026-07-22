package com.anvicorp.api.mail.service;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.mail.auth.MailJwtUtil;
import com.anvicorp.api.mail.auth.MailSessionTokenService;
import com.anvicorp.api.mail.dto.MailSsoTokenResponse;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailAccountStatus;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Careers → mail single-sign-on bridge. Takes a careers-authenticated
 * {@link User} (never a request body — the identity comes from the
 * SecurityContext filled in by the careers JWT filter) and mints a
 * fresh mail JWT + refresh token for that user's paired
 * {@link MailAccount}. The frontend writes the returned tokens into
 * the {@code mail.*} localStorage keys and navigates to {@code /mail},
 * where the mail app's own guard now sees an authenticated session
 * without asking for credentials.
 *
 * <h2>Pairing</h2>
 * By email match — the current unified-create flow provisions the
 * MailAccount with {@code localPart@domain == User.email}, and the
 * credential-edit flow keeps them in sync. This service resolves the
 * mailbox via
 * {@link MailAccountRepository#findActiveByLocalPartAndDomainName(String, String)}
 * so only mailboxes on an ACTIVE domain and in ACTIVE status match.
 * Suspended accounts and unprovisioned domains both surface a clean
 * 404 — never a token minted for the wrong account.
 *
 * <h2>Reuse of mail login primitives</h2>
 * The token pair is minted through the exact same primitives the mail
 * login endpoint uses ({@link MailSessionTokenService#issue} +
 * {@link MailJwtUtil#generateAccessToken}) so downstream mail auth,
 * refresh, and revoke paths treat SSO-minted sessions identically to
 * password-login sessions. No new JWT-issue code, no divergence.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailSsoService {

    private final MailAccountRepository accountRepository;
    private final MailSessionTokenService sessionTokenService;
    private final MailJwtUtil mailJwtUtil;

    @Transactional
    public MailSsoTokenResponse mintFor(User careersUser, HttpServletRequest httpRequest) {
        if (careersUser == null) {
            // Defence in depth — the controller's @PreAuthorize should
            // catch this, but if the SecurityContext is somehow empty
            // we refuse rather than looking anyone up.
            throw new ForbiddenException("Authentication required.");
        }
        // Fail-closed if the mail module isn't configured on this
        // environment. Without a MAIL_JWT_SECRET we can't mint a token
        // that the /mail app will accept — better to surface a clean
        // error than to hand back a signature the caller can't use.
        if (!mailJwtUtil.isConfigured()) {
            throw new ResourceNotFoundException(
                    "Mail is not configured on this environment.");
        }

        String email = careersUser.getEmail() == null
                ? null : careersUser.getEmail().trim().toLowerCase(Locale.ROOT);
        if (email == null || email.isEmpty()) {
            throw new ResourceNotFoundException(
                    "No mailbox provisioned for this account.");
        }
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) {
            throw new ResourceNotFoundException(
                    "No mailbox provisioned for this account.");
        }
        String localPart = email.substring(0, at);
        String domainName = email.substring(at + 1);

        MailAccount account = accountRepository
                .findActiveByLocalPartAndDomainName(localPart, domainName)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No mailbox provisioned for this account."));

        // Second gate — findActiveByLocalPartAndDomainName already
        // filters on domain.active + account.status == ACTIVE, but a
        // defensive re-check makes the fail-closed contract explicit
        // for future refactors of that finder.
        if (account.getStatus() != MailAccountStatus.ACTIVE) {
            throw new ResourceNotFoundException(
                    "No mailbox provisioned for this account.");
        }

        // Reuse the mail login primitives so the SSO-minted session is
        // indistinguishable from a password-login session downstream.
        MailSessionTokenService.Issued issued =
                sessionTokenService.issue(account, httpRequest);
        String accessToken = mailJwtUtil.generateAccessToken(
                account, issued.token().getId());

        log.info("[MailSso] issued mail session for careers user {} ({}) → mailbox {}",
                careersUser.getId(), email, account.getId());

        String mailboxAddress = account.getLocalPart() + "@" + account.getDomain().getName();
        return new MailSsoTokenResponse(
                accessToken,
                issued.rawRefreshToken(),
                mailJwtUtil.accessTtlSeconds(),
                account.getId().toString(),
                mailboxAddress,
                account.getDisplayName(),
                account.getRole().name(),
                Boolean.TRUE.equals(account.getMustChangePassword()),
                account.getDomain().getId().toString());
    }
}
