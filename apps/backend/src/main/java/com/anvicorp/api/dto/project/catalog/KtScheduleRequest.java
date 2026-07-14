package com.anvicorp.api.dto.project.catalog;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Trainer payload for {@code POST /api/v1/projects/{id}/kt-schedule}.
 *
 * <p>Schedules a live KT session for the project: backend creates a
 * Zoom meeting via the {@code MeetingProvider}, stores the
 * {@code start_url} (host) + {@code join_url} (intern) on the
 * {@code projects} row, and dispatches a KT_SCHEDULED notification
 * (intern internal-mail + in-app; trainer host-link email). Separate
 * from {@code POST /kt-done} — marking KT done remains an
 * always-available action that does not require a prior schedule.</p>
 *
 * <p>{@code scheduledFor} is required (ISO-8601 instant). Duration
 * defaults to 30 minutes when missing; timezone defaults to UTC.
 * {@code topic}/{@code agenda} are optional — when blank the server
 * synthesises "KT Session — {project title}" so the Zoom lobby has a
 * sensible label.</p>
 */
public record KtScheduleRequest(
        /** ISO-8601 instant, e.g. "2026-07-15T14:00:00Z". */
        @NotNull String scheduledFor,
        Integer durationMinutes,
        @Size(max = 50) String timezone,
        @Size(max = 200) String topic,
        @Size(max = 2000) String agenda
) {}
