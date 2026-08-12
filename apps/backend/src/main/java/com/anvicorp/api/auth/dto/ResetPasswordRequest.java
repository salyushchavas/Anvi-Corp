package com.anvicorp.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload for the code-entry reset flow: {@code email} identifies the account,
 * {@code code} is the 6-digit numeric code delivered by email (mirrors the
 * registration verification code), {@code newPassword} replaces the stored
 * BCrypt hash on success.
 */
public record ResetPasswordRequest(
        @Email @NotBlank String email,
        @NotBlank @Pattern(regexp = "\\d{6}", message = "Code must be 6 digits") String code,
        /** Wave-3-latent alignment — cap at 128 to match the admin
         *  {@code CreateUserRequest} + {@code UpdateUserCredentialsRequest}
         *  DTOs so the same account can't be created with a 500-char
         *  password on one endpoint and rejected on another. Bcrypt
         *  truncates past ~72 bytes; the cap costs nothing. */
        @NotBlank @Size(min = 8, max = 128) String newPassword
) {}
