package com.anvicorp.api.dto.users;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePasswordRequest {

    @NotBlank(message = "currentPassword is required")
    private String currentPassword;

    @NotBlank(message = "newPassword is required")
    // Wave-3-latent alignment — max = 128 matches the admin
    // CreateUserRequest + UpdateUserCredentialsRequest DTOs so the same
    // account can't accept a 500-char password on one endpoint and
    // reject it on another. Bcrypt truncates past ~72 bytes anyway.
    @Size(min = 8, max = 128, message = "newPassword must be 8-128 characters")
    private String newPassword;
}
