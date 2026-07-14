package com.anvicorp.api.mail.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for both POST /api/mail/auth/refresh and POST /api/mail/auth/logout. */
public record MailRefreshRequest(
        @NotBlank String refreshToken
) {
}
