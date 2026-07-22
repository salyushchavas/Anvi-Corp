package com.anvicorp.api.erm.intern;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Body for {@code PUT /api/v1/erm/interns/{internId}/company-email}.
 * Field names match what the ERM {@code AssignCompanyEmailDialog}
 * frontend already sends.
 */
@Getter
@Setter
public class AssignCompanyEmailRequest {

    /** Local part of the new mailbox address (no @domain). Server appends the configured domain. */
    @NotBlank(message = "companyEmailLocalPart is required")
    @Size(max = 64, message = "companyEmailLocalPart cannot exceed 64 characters")
    private String companyEmailLocalPart;

    /**
     * Starting password. Bounds re-checked in the service layer with a
     * friendlier message; jakarta {@link Size} only enforces the outer
     * envelope here.
     */
    @NotBlank(message = "startingPassword is required")
    @Size(min = 8, max = 128, message = "startingPassword must be 8-128 characters")
    private String startingPassword;
}
