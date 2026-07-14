package com.anvicorp.api.dto.admin;

import com.anvicorp.api.enums.UserRole;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateUserRoleRequest {

    @NotNull(message = "role is required")
    private UserRole role;
}
