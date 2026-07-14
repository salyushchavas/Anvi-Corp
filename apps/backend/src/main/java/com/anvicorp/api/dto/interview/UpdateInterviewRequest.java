package com.anvicorp.api.dto.interview;

import com.anvicorp.api.enums.InterviewType;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateInterviewRequest {

    @Future
    private Instant scheduledAt;

    @Min(15)
    @Max(240)
    private Integer durationMinutes;

    private UUID interviewerId;

    private InterviewType type;

    @Pattern(
            regexp = "^(https?://).+",
            message = "meetingUrl must start with http:// or https://"
    )
    private String meetingUrl;

    private String candidateNotes;
}
