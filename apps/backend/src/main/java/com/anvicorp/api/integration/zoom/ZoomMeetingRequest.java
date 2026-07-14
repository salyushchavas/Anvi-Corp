package com.anvicorp.api.integration.zoom;

import java.time.Instant;

/**
 * Input to {@link ZoomService#createMeeting(ZoomMeetingRequest)} and
 * {@link ZoomService#updateMeeting(long, ZoomMeetingRequest)}.
 *
 * @param hostUserId    Zoom user id or {@code "me"} to use the authenticated
 *                      service account. Use the host's Zoom email when available.
 * @param topic         Meeting topic shown in Zoom client.
 * @param startTime     Scheduled start instant.
 * @param durationMinutes  Length in minutes; clamped 15-240 server-side.
 * @param timezone      IANA timezone string (e.g. {@code "America/New_York"}).
 *                      Defaults to UTC if blank.
 * @param agenda        Free-form agenda body; nullable.
 */
public record ZoomMeetingRequest(
        String hostUserId,
        String topic,
        Instant startTime,
        int durationMinutes,
        String timezone,
        String agenda
) {}
