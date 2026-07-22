package com.anvicorp.api.mail.service;

import com.anvicorp.api.config.BrandConfig;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.MailHandoverState;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailAccountStatus;
import com.anvicorp.api.mail.entity.MailDomain;
import com.anvicorp.api.mail.entity.MailRole;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailDomainRepository;
import com.anvicorp.api.notification.EmailDeliveryException;
import com.anvicorp.api.notification.EmailProvider;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ERM-driven mailbox provisioning for an existing intern User.
 *
 * <p>Rebuild of the intern-side of the mail bridge that landed originally
 * in {@code intern/MailHandoverService.assignCompanyEmail} and was
 * dropped during the backend port (see commit {@code bd5c5d6}). Same
 * contract, minus the after-commit event publishing since the matching
 * {@code CompanyEmailAssignedListener} was also dropped from the port
 * and there is no subscriber to receive the event. The credentials
 * handoff email is sent inline here instead, best-effort — mirrors the
 * unified admin-create pattern in {@code AdminUserService.create}.</p>
 *
 * <h2>Dual-write — FINAL assignment, no activation step</h2>
 * In one {@code @Transactional} block:
 * <ol>
 *   <li>Create a {@link MailAccount} at {@code localPart@configured-domain}
 *       with the ERM-supplied starting password (BCrypt-encoded once via
 *       the shared {@link PasswordEncoder}). Status ACTIVE, role USER;
 *       {@code mustChangePassword} and {@code requireChangeOnFirstLogin}
 *       are BOTH FALSE — the ERM-typed password is the final credential,
 *       the intern is not asked to change it on first login.</li>
 *   <li>Set {@link User#getMailAccountId()} to link the intern to the
 *       new mailbox and flip {@link User#getMailHandoverState()} straight
 *       to {@link MailHandoverState#ACTIVATED} (skipping
 *       {@code PENDING_ACTIVATION} entirely). Stamp
 *       {@link User#getMailHandoverAt()} with the current instant so
 *       downstream code that reads the stamp works.</li>
 *   <li>Archive the intern's current careers login into
 *       {@link User#getPersonalEmail()} and set {@link User#getEmail()}
 *       to the new company address so the dashboard login moves at
 *       the same moment as the mailbox provisioning. No re-login gate,
 *       no second step.</li>
 * </ol>
 * If any write throws, the whole transaction rolls back — no
 * half-linked intern with an orphan mailbox and no email swap without
 * a mailbox.
 *
 * <h2>Credentials handoff</h2>
 * A best-effort credentials email is sent to the intern's
 * <b>previous</b> (personal) address BEFORE the email swap so the
 * message actually reaches them. Failure to send is non-fatal — the
 * ERM can still share the credentials out-of-band from the dialog
 * response.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CareersMailProvisioningService {

    /** Local-part rule: lowercase ASCII alnum, dot / dash / underscore, 1–64 chars. */
    private static final Pattern LOCAL_PART_RULE =
            Pattern.compile("^[a-z0-9](?:[a-z0-9._-]{0,62}[a-z0-9])?$");
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private final UserRepository userRepository;
    private final MailDomainRepository mailDomainRepository;
    private final MailAccountRepository mailAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailProvider emailProvider;
    private final BrandConfig brand;

    /**
     * Default company domain — the same seed key {@code MailAdminSeeder}
     * reads at boot, so the domain we mint against is always the one
     * that's guaranteed to exist as a {@link MailDomain} row.
     */
    @Value("${app.webmail.seed.admin-domain:anvicorp.com}")
    private String defaultDomain;

    /**
     * Provision a mailbox for the given intern. Idempotency: refuses if
     * a mailbox already exists at {@code (localPart, defaultDomain)};
     * refuses if the intern is already linked to a mailbox. Both cases
     * surface as 409.
     */
    @Transactional
    public ProvisionResult provisionForIntern(UUID internUserId,
                                              String localPartRaw,
                                              String startingPassword,
                                              User caller) {
        if (internUserId == null) {
            throw new BadRequestException("internId is required");
        }
        String localPart = localPartRaw == null
                ? "" : localPartRaw.trim().toLowerCase();
        if (localPart.isEmpty() || !LOCAL_PART_RULE.matcher(localPart).matches()) {
            throw new BadRequestException(
                    "companyEmailLocalPart must be lowercase ASCII alphanumeric "
                            + "(plus . _ -), 1–64 chars, and start/end with a letter or digit.");
        }
        if (startingPassword == null || startingPassword.length() < MIN_PASSWORD_LENGTH
                || startingPassword.length() > MAX_PASSWORD_LENGTH) {
            throw new BadRequestException(
                    "startingPassword must be " + MIN_PASSWORD_LENGTH
                            + "–" + MAX_PASSWORD_LENGTH + " characters.");
        }

        User intern = userRepository.findById(internUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Intern not found: " + internUserId));
        // Sanity gate — this endpoint is only for interns. Refuse if
        // the target is a staff-only account so the ERM can't
        // accidentally provision a mailbox for the wrong side.
        if (intern.getRoles() == null || !intern.getRoles().contains(UserRole.INTERN)) {
            throw new BadRequestException(
                    "Company email can only be assigned to accounts with the INTERN role.");
        }
        if (intern.getMailAccountId() != null) {
            throw new ConflictException(
                    "This intern already has a company mailbox linked. Reassignment "
                            + "isn't supported by this endpoint.");
        }

        MailDomain domain = mailDomainRepository.findByName(defaultDomain)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Configured mail domain '" + defaultDomain
                                + "' is not provisioned. Seed it first."));

        if (mailAccountRepository.existsByLocalPartAndDomain_Id(localPart, domain.getId())) {
            throw new ConflictException(
                    "A mailbox already exists at " + localPart + "@" + defaultDomain
                            + ". Pick a different local-part.");
        }

        // Pre-flight uniqueness on the careers side too — we're about to
        // move User.email to the company address atomically, so a
        // collision with any OTHER user's login email would raise a raw
        // DataIntegrityViolation (users.email UNIQUE). Surface it as a
        // clean 409 with an actionable message instead — most commonly
        // fires when a staff row (admin-created via the unified flow)
        // already claims that local-part.
        String prospectiveCompanyEmail = localPart + "@" + domain.getName();
        String currentInternEmail = intern.getEmail() == null
                ? "" : intern.getEmail().toLowerCase();
        if (!prospectiveCompanyEmail.equalsIgnoreCase(currentInternEmail)
                && userRepository.existsByEmail(prospectiveCompanyEmail)) {
            throw new ConflictException(
                    "Another user already has the careers login "
                            + prospectiveCompanyEmail
                            + ". Pick a different local-part.");
        }

        String passwordHash = passwordEncoder.encode(startingPassword);
        String displayName = intern.getFullName();
        MailAccount mailbox = MailAccount.builder()
                .domain(domain)
                .localPart(localPart)
                .displayName(displayName)
                .passwordHash(passwordHash)
                .role(MailRole.USER)
                .status(MailAccountStatus.ACTIVE)
                // ERM-assigned credentials are FINAL — no forced change
                // on first mailbox login. Both flags stay false so the
                // intern can sign in with the ERM-typed password and
                // keep using it.
                .mustChangePassword(false)
                .requireChangeOnFirstLogin(false)
                .build();
        mailbox = mailAccountRepository.save(mailbox);

        String companyEmail = localPart + "@" + domain.getName();

        // Capture the pre-swap personal address so the credentials
        // email actually reaches the intern (it must go out BEFORE we
        // move User.email to the company address, otherwise we'd try
        // to email a mailbox the intern hasn't logged into yet).
        String personalEmail = intern.getEmail();

        // Best-effort credentials email — sent to the intern's current
        // personal address before the email swap below. Failure is
        // non-fatal; the ERM copy panel is the backup channel.
        Boolean credentialsEmailSent = null;
        if (personalEmail != null && !personalEmail.isBlank()) {
            credentialsEmailSent = sendCredentialsEmail(
                    personalEmail.trim().toLowerCase(),
                    displayName,
                    companyEmail,
                    startingPassword);
        }

        // Dual-write side 2: link the mailbox, mark handover complete,
        // and move the careers login email to the company address in
        // one shot. No PENDING_ACTIVATION step — ERM assignment is
        // terminal. Archives the personal address into
        // User.personalEmail so downstream code (fallback comms,
        // account recovery) can still reach the intern out-of-band.
        Instant now = Instant.now();
        intern.setMailAccountId(mailbox.getId());
        intern.setMailHandoverState(MailHandoverState.ACTIVATED);
        intern.setMailHandoverAt(now);
        if (personalEmail != null && !personalEmail.isBlank()
                && intern.getPersonalEmail() == null) {
            // Only stash on the first swap — a re-assignment (if we ever
            // allow one) shouldn't overwrite the original personal
            // address with a stale company address.
            intern.setPersonalEmail(personalEmail);
        }
        intern.setEmail(companyEmail);
        userRepository.save(intern);

        log.info("[CareersMailProvisioning] ERM {} assigned company mailbox {} to intern {} "
                        + "(personal {} archived; state ACTIVATED; credentialsEmailSent={})",
                caller != null ? caller.getId() : null, companyEmail, intern.getId(),
                personalEmail, credentialsEmailSent);

        return new ProvisionResult(
                intern.getId(),
                companyEmail,
                MailHandoverState.ACTIVATED.name(),
                credentialsEmailSent);
    }

    private boolean sendCredentialsEmail(String deliveryEmail, String fullName,
                                          String companyEmail, String rawPassword) {
        String safeName = (fullName == null || fullName.isBlank()) ? "there" : fullName;
        String subject = "Your " + brand.getName() + " company mailbox is ready";

        String plain = ""
                + "Hi " + safeName + ",\n\n"
                + "A " + brand.getName() + " administrator has provisioned your company "
                + "mailbox. These are your final credentials — use them to sign in to "
                + "both your mailbox at /mail AND your " + brand.getName() + " dashboard.\n\n"
                + "Login email: " + companyEmail + "\n"
                + "Password:    " + rawPassword + "\n\n"
                + "Sign in at: /mail\n\n"
                + "— The " + brand.getName() + " team\n";

        String html = ""
                + "<h2 style=\"margin:0 0 12px;font-size:20px;color:#0f172a;\">"
                + "Your " + escapeHtml(brand.getName()) + " company mailbox is ready</h2>"
                + "<p style=\"margin:0 0 12px;font-size:15px;color:#1f2937;\">"
                + "Hi " + escapeHtml(safeName) + ",</p>"
                + "<p style=\"margin:0 0 12px;font-size:15px;color:#1f2937;\">"
                + "A " + escapeHtml(brand.getName()) + " administrator has provisioned your "
                + "company mailbox. These are your <strong>final credentials</strong> — "
                + "use them to sign in to both your mailbox at /mail AND your "
                + escapeHtml(brand.getName()) + " dashboard.</p>"
                + "<div style=\"margin:16px 0;padding:14px 16px;background:#EFF7FD;"
                + "border:1px solid #D8ECFA;border-radius:6px;font-family:monospace;"
                + "font-size:14px;color:#0f172a;\">"
                + "<div><strong>Login email:</strong> " + escapeHtml(companyEmail) + "</div>"
                + "<div style=\"margin-top:6px;\"><strong>Password:</strong> "
                + escapeHtml(rawPassword) + "</div>"
                + "</div>"
                + "<p style=\"color:#6b7280;font-size:13px;margin:0;\">"
                + "Sign in at /mail to reach your inbox.</p>";

        try {
            emailProvider.sendBrandedHtml(deliveryEmail, subject, plain, html);
            return true;
        } catch (EmailDeliveryException e) {
            log.warn("[CareersMailProvisioning] credentials email failed for {} "
                    + "(ERM can share credentials out-of-band): {}",
                    deliveryEmail, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("[CareersMailProvisioning] credentials email unexpected failure for {}: {}",
                    deliveryEmail, e.getMessage());
            return false;
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Return payload for the controller. Mirrors the frontend's expected shape. */
    public record ProvisionResult(
            UUID userId,
            String companyEmail,
            String status,
            Boolean credentialsEmailSent) {
    }
}
