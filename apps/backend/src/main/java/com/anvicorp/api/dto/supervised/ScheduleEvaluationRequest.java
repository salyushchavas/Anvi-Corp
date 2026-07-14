package com.anvicorp.api.dto.supervised;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class ScheduleEvaluationRequest {

    @NotNull(message = "scheduledAt is required")
    private LocalDateTime scheduledAt;

    /** Optional override; defaults to the intern's assignedEvaluator. */
    private UUID evaluatorId;
}
