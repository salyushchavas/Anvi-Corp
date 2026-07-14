package com.anvicorp.api.dto.screening;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Staff view of a screening — includes the answer key, per-question awarded
 * points, and the candidate's free-text answers.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScreeningStaffResponse {
    UUID id;
    UUID applicationId;
    String status;
    Instant sentAt;
    Instant completedAt;
    Integer score;
    Integer maxScore;
    String candidateName;
    String candidateEmail;
    String jobPostingTitle;
    String entityName;
    List<AnswerView> answers;

    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AnswerView {
        UUID questionId;
        Integer orderIndex;
        String type;
        String prompt;
        List<String> choices;
        Integer correctChoiceIndex;
        Integer choiceIndex;
        String freeText;
        Integer points;
        Integer awardedPoints;
    }
}
