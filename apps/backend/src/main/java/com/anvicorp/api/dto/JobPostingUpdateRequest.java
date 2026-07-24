package com.anvicorp.api.dto;

import com.anvicorp.api.enums.EmploymentType;
import com.anvicorp.api.enums.JobPostingStatus;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class JobPostingUpdateRequest {
    private String title;
    private String description;
    private String requirements;
    private String location;
    private EmploymentType employmentType;
    private JobPostingStatus status;
    private UUID entityId;
}
