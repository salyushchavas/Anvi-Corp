package com.anvicorp.api.trainer.groupsessions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Trainer Group Sessions — request + response DTOs. */
public final class GroupSessionDtos {

    private GroupSessionDtos() {}

    public record CreateGroupSessionRequest(
            @NotBlank @Size(max = 200) String topic,
            @Size(max = 5000) String description,
            @NotNull Instant scheduledAt,
            @NotBlank String meetingTimezone,
            Integer durationMinutes,
            /** {@code intern_lifecycles.id} values — matches the shape the
             *  trainer's Active Interns picker already speaks. Backend
             *  resolves each to a {@code users.id} internally. */
            @NotNull List<UUID> participantLifecycleIds
    ) {}

    public record ParticipantRef(
            UUID internUserId,
            String fullName,
            String email
    ) {}

    /** Trainer-facing view — carries the host start URL. Never emit this
     *  shape to interns. */
    public record TrainerGroupSessionResponse(
            UUID id,
            String topic,
            String description,
            Instant scheduledAt,
            String meetingTimezone,
            Integer durationMinutes,
            String status,
            String zoomMeetingId,
            String zoomJoinUrl,
            String zoomStartUrl,
            String zoomPassword,
            Instant cancelledAt,
            List<ParticipantRef> participants,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /** Intern-facing view — HOST START URL IS INTENTIONALLY OMITTED. */
    public record InternGroupSessionResponse(
            UUID id,
            String topic,
            String description,
            Instant scheduledAt,
            String meetingTimezone,
            Integer durationMinutes,
            String status,
            String zoomJoinUrl,
            String zoomPassword,
            String hostName,
            Integer participantCount
    ) {}
}
