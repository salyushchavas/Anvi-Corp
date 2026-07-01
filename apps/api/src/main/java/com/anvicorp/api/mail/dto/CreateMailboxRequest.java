package com.anvicorp.api.mail.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Create a mailbox. {@code password} is optional — blank → the server generates
 * a strong one-time password. {@code requireChangeOnFirstLogin} defaults to true
 * (sets must_change_password) when null. New mailboxes are always role USER;
 * role is managed separately.
 */
public record CreateMailboxRequest(
        @NotNull UUID domainId,
        @NotBlank String localPart,
        String displayName,
        String password,
        Boolean requireChangeOnFirstLogin
) {
}
