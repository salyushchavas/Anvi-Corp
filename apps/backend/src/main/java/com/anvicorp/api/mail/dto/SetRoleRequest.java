package com.anvicorp.api.mail.dto;

import com.anvicorp.api.mail.entity.MailRole;
import jakarta.validation.constraints.NotNull;

public record SetRoleRequest(
        @NotNull MailRole role
) {
}
