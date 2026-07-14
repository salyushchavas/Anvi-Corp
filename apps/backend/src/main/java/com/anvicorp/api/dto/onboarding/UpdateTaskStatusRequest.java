package com.anvicorp.api.dto.onboarding;

import com.anvicorp.api.enums.OnboardingTaskStatus;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateTaskStatusRequest {

    @NotNull
    private OnboardingTaskStatus status;
}
