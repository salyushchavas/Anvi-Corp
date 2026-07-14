package com.anvicorp.api.dto.admin;

import com.anvicorp.api.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Admin-issued staff invite. The admin does NOT set a password — the
 * user picks their own via the activation link emailed to them
 * (see {@code AdminUserService.create} and the {@code /auth/activate}
 * endpoint). Name is optional because the user fills it out themselves
 * during activation; legacy callers that still send it are accepted.
 */
@Getter
@Setter
public class CreateUserRequest {

    /** Optional — defaults to the local-part of the email if blank. */
    private String name;

    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid address")
    private String email;

    /** Must be a STAFF role; SUPER_ADMIN + INTERN are rejected at the service layer. */
    @NotNull(message = "role is required")
    private UserRole role;
}
