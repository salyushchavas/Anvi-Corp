package com.anvicorp.api.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateEntityRequest {

    @NotBlank(message = "name is required")
    private String name;

    private String address;
    private String country;
    /** Defaults to true when omitted. */
    private Boolean isActive;
}
