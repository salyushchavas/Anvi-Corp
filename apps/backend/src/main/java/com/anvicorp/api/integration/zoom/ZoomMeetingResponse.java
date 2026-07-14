package com.anvicorp.api.integration.zoom;

/**
 * Subset of fields returned by Zoom's POST /v2/users/{userId}/meetings that
 * the platform persists. {@code startUrl} is treated as host-only and is
 * never returned to applicants — see InterviewController DTO mapping.
 */
public record ZoomMeetingResponse(
        long meetingId,
        String joinUrl,
        String startUrl,
        String password,
        String hostEmail
) {}
