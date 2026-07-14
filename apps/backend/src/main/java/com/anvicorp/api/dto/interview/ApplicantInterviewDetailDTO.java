package com.anvicorp.api.dto.interview;

import com.anvicorp.api.enums.InterviewRecommendation;
import com.anvicorp.api.enums.InterviewStatus;
import com.anvicorp.api.enums.InterviewType;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Change 4 — applicant-side interview detail. Deliberately omits raw numeric
 * scores, interviewer comments, and reviewer-private fields. The frontend uses
 * this to render the "What we discussed / What happens next" page.
 *
 * <p>{@code recommendation} is surfaced only for HIRE outcomes (the applicant
 * should hear positive news from the system); HOLD / REJECT are returned as
 * a coarse {@code outcomeCategory} for branching copy, never the raw enum.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicantInterviewDetailDTO {

    private UUID id;
    private UUID applicationId;
    private String jobPostingTitle;
    private InterviewType type;
    private InterviewStatus status;
    private Instant scheduledAt;
    private Integer durationMinutes;
    private String interviewerName;

    /** Set when status == COMPLETED. */
    private Instant completedAt;

    /** HIRE | HOLD | REJECT — coarse branch for FE copy. Null until completed. */
    private String outcomeCategory;

    /** True when the team has reached a positive outcome. */
    private boolean outcomePositive;
}
