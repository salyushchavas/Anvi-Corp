package com.anvicorp.api.dto.interview;

import com.anvicorp.api.enums.InterviewStatus;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateStatusRequest {

    @NotNull
    private InterviewStatus status;
}
