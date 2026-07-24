package com.anvicorp.api.dto;

import com.anvicorp.api.enums.EmploymentType;
import com.anvicorp.api.enums.JobPostingStatus;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class JobPostingCreateRequest {

    @NotBlank(message = "jobId is required")
    private String jobId;

    @NotBlank(message = "title is required")
    private String title;

    @NotBlank(message = "description is required")
    private String description;

    private String requirements;

    @NotBlank(message = "location is required")
    private String location;

    private EmploymentType employmentType = EmploymentType.INTERNSHIP;

    private JobPostingStatus status = JobPostingStatus.DRAFT;
}
