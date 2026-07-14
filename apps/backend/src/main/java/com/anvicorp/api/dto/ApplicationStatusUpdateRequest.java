package com.anvicorp.api.dto;

import com.anvicorp.api.enums.ApplicationStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApplicationStatusUpdateRequest {

    @NotNull(message = "status is required")
    private ApplicationStatus status;

    private String recruiterNotes;
}
