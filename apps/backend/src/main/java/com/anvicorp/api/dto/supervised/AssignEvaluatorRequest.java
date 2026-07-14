package com.anvicorp.api.dto.supervised;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class AssignEvaluatorRequest {

    @NotNull(message = "evaluatorId is required")
    private UUID evaluatorId;
}
