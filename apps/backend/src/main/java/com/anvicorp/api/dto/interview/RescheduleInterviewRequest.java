package com.anvicorp.api.dto.interview;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class RescheduleInterviewRequest {

    @NotNull(message = "scheduledAt is required")
    @Future(message = "scheduledAt must be in the future")
    private Instant scheduledAt;

    @Min(15)
    @Max(240)
    private Integer durationMinutes;

    private String timezone;
}
