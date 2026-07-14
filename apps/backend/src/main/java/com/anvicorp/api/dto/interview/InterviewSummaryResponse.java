package com.anvicorp.api.dto.interview;

import com.anvicorp.api.enums.InterviewStatus;
import com.anvicorp.api.enums.InterviewType;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewSummaryResponse {
    private UUID id;
    private String candidateName;
    private String jobPostingTitle;
    private String interviewerName;
    private Instant scheduledAt;
    private Integer durationMinutes;
    private InterviewType type;
    private InterviewStatus status;
    private boolean hasFeedback;
}
