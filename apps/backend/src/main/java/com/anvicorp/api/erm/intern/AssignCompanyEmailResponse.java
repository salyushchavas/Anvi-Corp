package com.anvicorp.api.erm.intern;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Response body for {@code PUT /api/v1/erm/interns/{internId}/company-email}.
 *
 * <p>{@code companyEmail} is what the ERM {@code AssignCompanyEmailDialog}
 * consumes (via {@code onAssigned(res.data.companyEmail)}); the rest is
 * informational so the frontend can render a richer confirmation later
 * without another round-trip.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssignCompanyEmailResponse(
        UUID userId,
        String companyEmail,
        /** MailHandoverState enum name — {@code PENDING_ACTIVATION} on success. */
        String status,
        /** True/false when a credentials email was attempted; null when no delivery address was on file. */
        Boolean credentialsEmailSent
) {
}
