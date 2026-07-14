package com.anvicorp.api.dto.interview;

import com.anvicorp.api.enums.InterviewStatus;
import com.anvicorp.api.enums.InterviewType;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Applicant-safe interview DTO. By construction this class never contains
 * {@code zoomStartUrl} or {@code internalNotes} — any future addition that
 * exposes host-only state should go to {@link InterviewResponse} instead.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandidateInterviewResponse {
    private UUID id;
    private UUID applicationId;
    private String jobPostingTitle;
    private Instant scheduledAt;
    private Integer durationMinutes;
    private String timezone;
    private InterviewType type;
    private InterviewStatus status;
    /** Applicant-safe Zoom join URL — same as zoomJoinUrl on the host DTO. */
    private String meetingUrl;
    private String zoomJoinUrl;
    private String zoomPassword;
    private String candidateNotes;
    private String prepInstructions;
    private String interviewerName;
    /** SELECTED | HOLD | REJECTED — null until ERM completes the interview. */
    private String decision;
    /** The doc-spec applicant-safe outcome message. */
    private String applicantVisibleNotes;
}
