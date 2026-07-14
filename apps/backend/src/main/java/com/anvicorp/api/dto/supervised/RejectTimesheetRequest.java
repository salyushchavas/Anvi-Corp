package com.anvicorp.api.dto.supervised;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RejectTimesheetRequest {

    @NotBlank(message = "reason is required")
    private String reason;
}
