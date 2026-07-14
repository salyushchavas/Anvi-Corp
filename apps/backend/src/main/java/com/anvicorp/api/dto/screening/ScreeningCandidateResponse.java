package com.anvicorp.api.dto.screening;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Candidate-facing screening payload — does NOT include correctChoiceIndex
 * (would leak the answer key) or awardedPoints.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScreeningCandidateResponse {
    UUID id;
    String status;
    Instant sentAt;
    Instant completedAt;
    String jobPostingTitle;
    String entityName;
    List<Question> questions;

    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Question {
        UUID id;
        Integer orderIndex;
        String type;
        String prompt;
        /** Present for SINGLE_CHOICE; null/omitted for FREE_TEXT. */
        List<String> choices;
    }
}
