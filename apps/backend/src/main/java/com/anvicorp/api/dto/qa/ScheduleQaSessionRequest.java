package com.anvicorp.api.dto.qa;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code meetingLink} is a manual paste-in URL fallback used only when the
 * Zoom provider is unavailable at schedule time; when Zoom is wired the
 * service auto-creates a meeting and persists the host/join URLs on the
 * session row. The other fields shape that auto-created meeting.
 */
public record ScheduleQaSessionRequest(
        @NotNull UUID projectId,
        @NotNull Instant scheduledAt,
        @Size(max = 1024) String meetingLink,
        Integer durationMinutes,
        @Size(max = 64) String timezone,
        @Size(max = 200) String topic,
        @Size(max = 4000) String agenda
) {}
