package com.anvicorp.api.integration.meeting;

import java.time.Instant;

/**
 * Generic input to {@link MeetingProvider#createMeeting} / {@link
 * MeetingProvider#updateMeeting}. Mirrors the surface of {@code
 * ZoomMeetingRequest} so the Zoom adapter is a thin pass-through.
 *
 * @param hostEmail        Email of the user that should host the meeting.
 *                         For Zoom this maps to {@code hostUserId} (either
 *                         the email or the literal {@code "me"} to use the
 *                         service account). For WebEx this is required on
 *                         the create payload to schedule per-user via the
 *                         Service App.
 * @param topic            Meeting title shown in the calendar invite.
 * @param startTime        Scheduled start instant. Must not be in the past
 *                         (WebEx enforces this server-side).
 * @param durationMinutes  Length in minutes. Zoom clamps 15–240; WebEx
 *                         requires 10–1439 (23h59m). Adapters apply their
 *                         own clamping.
 * @param timezone         IANA timezone string (e.g. {@code "America/New_York"}).
 *                         Defaults to UTC if blank.
 * @param agenda           Free-form agenda body (nullable).
 */
public record MeetingRequest(
        String hostEmail,
        String topic,
        Instant startTime,
        int durationMinutes,
        String timezone,
        String agenda
) {}
