package com.anvicorp.api.erm.intern;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.mail.service.CareersMailProvisioningService;
import com.anvicorp.api.mail.service.CareersMailProvisioningService.ProvisionResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * ERM controller for actions scoped to a single intern (identified by
 * their careers {@code User.id}). Home of the mail-handover endpoints
 * that were previously in the deleted {@code erm/mail/} subpackage.
 */
@RestController
@RequestMapping("/api/v1/erm/interns")
@RequiredArgsConstructor
public class ErmInternController {

    private final CareersMailProvisioningService mailProvisioning;

    /**
     * Assign a company mailbox to an intern. Provisions the mailbox at
     * {@code companyEmailLocalPart@<configured-domain>} with the
     * ERM-supplied {@code startingPassword} (BCrypt-encoded), links it
     * to the intern's {@code User.mailAccountId}, and advances
     * {@code User.mailHandoverState} from {@code PERSONAL} to
     * {@code PENDING_ACTIVATION}. A best-effort credentials email goes
     * out to the intern's current login address.
     *
     * <p>Returns 400 on invalid local-part / password, 404 when the
     * intern doesn't exist or the configured mail domain isn't
     * provisioned, 409 when a mailbox already exists at the requested
     * address or the intern is already linked to a mailbox.</p>
     */
    @PutMapping("/{internId}/company-email")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public AssignCompanyEmailResponse assignCompanyEmail(
            @PathVariable UUID internId,
            @Valid @RequestBody AssignCompanyEmailRequest req,
            @AuthenticationPrincipal User caller) {
        ProvisionResult result = mailProvisioning.provisionForIntern(
                internId,
                req.getCompanyEmailLocalPart(),
                req.getStartingPassword(),
                caller);
        return new AssignCompanyEmailResponse(
                result.userId(),
                result.companyEmail(),
                result.status(),
                result.credentialsEmailSent());
    }
}
