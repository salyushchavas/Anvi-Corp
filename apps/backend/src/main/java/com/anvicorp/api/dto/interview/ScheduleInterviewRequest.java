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
public class ScheduleInterviewRequest {

    @NotNull
    private UUID applicationId;

    @NotNull
    private UUID interviewerId;

    @NotNull
    @Future
    private Instant scheduledAt;

    @Min(15)
    @Max(240)
    @Builder.Default
    private Integer durationMinutes = 60;

    @NotNull
    private InterviewType type;

    @Pattern(
            regexp = "^(https?://).+",
            message = "meetingUrl must start with http:// or https://"
    )
    private String meetingUrl;

    private String candidateNotes;

    /** Phase 2 — IANA timezone string for the Zoom invite (default UTC). */
    private String timezone;

    /** Phase 2 — applicant-facing prep instructions rendered on the hero card. */
    private String prepInstructions;
}
