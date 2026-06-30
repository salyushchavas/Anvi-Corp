package com.anvicorp.api.mail.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Authenticated self-change of password. Length/strength + "must differ" rules
 * are enforced in the service (so they return mail-specific error codes), not
 * via bean-validation annotations.
 */
public record MailChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank String newPassword
) {
}
