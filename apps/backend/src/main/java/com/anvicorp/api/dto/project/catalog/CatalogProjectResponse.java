package com.anvicorp.api.dto.project.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.anvicorp.api.enums.Difficulty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CatalogProjectResponse(
        UUID id,
        String name,
        String description,
        String requirements,
        String objectives,
        String techStack,
        Integer expectedDurationDays,
        String deliverables,
        Difficulty difficulty,
        String instructions,
        LocalDate startDate,
        LocalDate endDate,
        UserRef createdBy,
        Long assignmentCount,
        RepositoryRef repository,
        /** KT (Knowledge Transfer) — populated for assigned projects. */
        KtSummary kt,
        Instant createdAt,
        Instant updatedAt
) {
    public record UserRef(UUID id, String fullName) {}

    public record RepositoryRef(
            UUID id,
            String repositoryName,
            String repositoryUrl,
            UserRef linkedBy,
            Instant linkedAt
    ) {}

    public record KtSummary(
            String status,            // NOT_DONE | DONE
            Instant completedAt,
            String meetingLink,
            String notes,
            String markedByName,
            /** ── KT live session (Zoom) — null when the trainer hasn't
             *  scheduled a session for this project. {@code zoomStartUrl}
             *  is the host-only one-click link; the controller surface
             *  intern-side DTOs MUST strip it. */
            String zoomMeetingId,
            String zoomJoinUrl,
            String zoomStartUrl,
            Instant scheduledFor,
            Integer durationMinutes,
            String timezone
    ) {}
}
