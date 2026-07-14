package com.anvicorp.api.integration.meeting;

/**
 * Generic shape returned by every {@link MeetingProvider} CRUD call. Mirrors
 * the fields {@code ZoomMeetingResponse} carries today so the Zoom adapter is
 * a straight field map; the WebEx adapter populates the same fields from the
 * WebEx response shape.
 *
 * @param providerName       Which adapter produced this row ({@code "zoom"} /
 *                           {@code "webex"}). Lets consumers branch on the
 *                           originator if they need provider-specific
 *                           rendering (e.g. host-side links).
 * @param providerMeetingId  The provider's primary key for the meeting.
 *                           Zoom: numeric string (e.g. {@code "85123456789"}).
 *                           WebEx: opaque alphanumeric (e.g.
 *                           {@code "a1b2c3d4e5f6_1234"}).
 * @param joinUrl            Public attendee join URL. Safe to send to interns.
 * @param startUrl           Host-only start URL. Zoom returns this directly;
 *                           WebEx returns a {@code hostLink} that serves the
 *                           same purpose. NEVER expose to applicants.
 * @param password           Numeric / alphanumeric meeting password. May be
 *                           {@code null} when the provider isn't enforcing one.
 * @param hostEmail          The host email the provider associated with this
 *                           meeting (echoed back from create).
 */
public record MeetingResponse(
        String providerName,
        String providerMeetingId,
        String joinUrl,
        String startUrl,
        String password,
        String hostEmail
) {}
