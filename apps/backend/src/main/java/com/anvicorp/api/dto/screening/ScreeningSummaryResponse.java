package com.anvicorp.api.dto.screening;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Compact view returned by the "send" endpoint and surfaced on the recruiter
 * review screen when the screening is still SENT (no answers to show yet).
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScreeningSummaryResponse {
    UUID id;
    UUID applicationId;
    String status;
    Instant sentAt;
    Instant completedAt;
    Integer score;
    Integer maxScore;
}
