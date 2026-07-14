package com.anvicorp.api.dto;

import com.anvicorp.api.enums.EmploymentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class JobPostingCreateRequest {

    @NotBlank(message = "title is required")
    private String title;

    @NotBlank(message = "description is required")
    private String description;

    private String requirements;

    @NotBlank(message = "location is required")
    private String location;

    private EmploymentType employmentType = EmploymentType.INTERNSHIP;

    @NotNull(message = "entityId is required")
    private UUID entityId;
}
