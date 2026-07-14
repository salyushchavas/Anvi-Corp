package com.anvicorp.api.dto.qa;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.anvicorp.api.enums.QaSessionStatus;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QaSessionResponse(
        UUID id,
        UUID projectId,
        String projectTitle,
        UUID internUserId,
        String internName,
        Instant scheduledAt,
        String meetingLink,
        String zoomMeetingId,
        String zoomJoinUrl,
        // HOST-ONLY — the frontend uses /host-start to refetch a fresh
        // copy (~2h zak); we still surface the stored copy so the host
        // card has a fallback to mention in its error state.
        String zoomStartUrl,
        QaSessionStatus status,
        String questionsAsked,
        String internResponses,
        Integer marks,
        String remarks,
        UUID scheduledByUserId,
        UUID conductedByUserId,
        Instant completedAt,
        Instant returnedAt,
        String returnReason,
        Instant createdAt,
        Instant updatedAt
) {}
