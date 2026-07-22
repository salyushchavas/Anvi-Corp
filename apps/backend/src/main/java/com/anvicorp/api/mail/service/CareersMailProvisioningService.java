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
 * <h2>Dual-write</h2>
 * In one {@code @Transactional} block:
 * <ol>
 *   <li>Create a {@link MailAccount} at {@code localPart@configured-domain}
 *       with the ERM-supplied starting password (BCrypt-encoded once via
 *       the shared {@link PasswordEncoder}). Status ACTIVE, role USER,
 *       {@code mustChangePassword=true} and
 *       {@code requireChangeOnFirstLogin=true} so the intern is forced
 *       to change on first mailbox login.</li>
 *   <li>Set {@link User#getMailAccountId()} to link the intern to the
 *       new mailbox and flip {@link User#getMailHandoverState()} from
 *       {@link MailHandoverState#PERSONAL} to
 *       {@link MailHandoverState#PENDING_ACTIVATION}. The tracker's
 *       "Mail ID + joining date" step reads this state and progresses
 *       once the intern activates.</li>
 * </ol>
 * If either write throws, the whole transaction rolls back — no
 * half-linked intern with an orphan mailbox.
 *
 * <h2>Pairing rule</h2>
 * The intern's careers login email is NOT moved to the company address
 * here — it stays at their personal Gmail so the credentials email can
 * reach them at that address and the {@code PERSONAL → PENDING → ACTIVATED}
 * state machine can carry the eventual swap. The user's original email
 * is preserved as {@link User#getEmail()} through PENDING_ACTIVATION;
 * a later activation flow (out of scope here) archives it into
 * {@code User.personalEmail} and moves {@code User.email} to the company
 * address.
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

        String passwordHash = passwordEncoder.encode(startingPassword);
        String displayName = intern.getFullName();
        MailAccount mailbox = MailAccount.builder()
                .domain(domain)
                .localPart(localPart)
                .displayName(displayName)
                .passwordHash(passwordHash)
                .role(MailRole.USER)
                .status(MailAccountStatus.ACTIVE)
                // Intern MUST change on first mailbox login per the
                // ERM dialog copy: "must change the password on first
                // mailbox login".
                .mustChangePassword(true)
                .requireChangeOnFirstLogin(true)
                .build();
        mailbox = mailAccountRepository.save(mailbox);

        String companyEmail = localPart + "@" + domain.getName();

        // Dual-write side 2: link the User and advance the state
        // machine. Runs in the same @Transactional so a repo save
        // failure here rolls back the mailbox insert too.
        intern.setMailAccountId(mailbox.getId());
        intern.setMailHandoverState(MailHandoverState.PENDING_ACTIVATION);
        userRepository.save(intern);

        // Best-effort credentials email to the intern's current login
        // address (their personal Gmail before the swap-on-activation
        // happens). Failure to send is non-fatal — the ERM can still
        // share the credentials out-of-band.
        Boolean credentialsEmailSent = null;
        String deliveryEmail = intern.getEmail();
        if (deliveryEmail != null && !deliveryEmail.isBlank()) {
            credentialsEmailSent = sendCredentialsEmail(
                    deliveryEmail.trim().toLowerCase(),
                    displayName,
                    companyEmail,
                    startingPassword);
        }

        log.info("[CareersMailProvisioning] ERM {} assigned company mailbox {} to intern {} "
                        + "({} → PENDING_ACTIVATION, credentialsEmailSent={})",
                caller != null ? caller.getId() : null, companyEmail, intern.getId(),
                deliveryEmail, credentialsEmailSent);

        return new ProvisionResult(
                intern.getId(),
                companyEmail,
                MailHandoverState.PENDING_ACTIVATION.name(),
                credentialsEmailSent);
    }

    private boolean sendCredentialsEmail(String deliveryEmail, String fullName,
                                          String companyEmail, String rawPassword) {
        String safeName = (fullName == null || fullName.isBlank()) ? "there" : fullName;
        String subject = "Your " + brand.getName() + " company mailbox is ready";

        String plain = ""
                + "Hi " + safeName + ",\n\n"
                + "A " + brand.getName() + " administrator has provisioned your company "
                + "mailbox. Sign in to your inbox and change the starting password on "
                + "first login.\n\n"
                + "Mailbox: " + companyEmail + "\n"
                + "Password: " + rawPassword + "\n\n"
                + "Sign in at: /mail\n\n"
                + "Once you complete the first login + password change your "
                + brand.getName() + " dashboard login also moves to this address.\n\n"
                + "— The " + brand.getName() + " team\n";

        String html = ""
                + "<h2 style=\"margin:0 0 12px;font-size:20px;color:#0f172a;\">"
                + "Your " + escapeHtml(brand.getName()) + " company mailbox is ready</h2>"
                + "<p style=\"margin:0 0 12px;font-size:15px;color:#1f2937;\">"
                + "Hi " + escapeHtml(safeName) + ",</p>"
                + "<p style=\"margin:0 0 12px;font-size:15px;color:#1f2937;\">"
                + "A " + escapeHtml(brand.getName()) + " administrator has provisioned your "
                + "company mailbox. Sign in and change the starting password on first "
                + "login. Once you do, your dashboard login also moves to this address.</p>"
                + "<div style=\"margin:16px 0;padding:14px 16px;background:#EFF7FD;"
                + "border:1px solid #D8ECFA;border-radius:6px;font-family:monospace;"
                + "font-size:14px;color:#0f172a;\">"
                + "<div><strong>Mailbox:</strong> " + escapeHtml(companyEmail) + "</div>"
                + "<div style=\"margin-top:6px;\"><strong>Password:</strong> "
                + escapeHtml(rawPassword) + "</div>"
                + "</div>"
                + "<p style=\"color:#6b7280;font-size:13px;margin:0;\">"
                + "Sign in at /mail and change your password immediately.</p>";

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
