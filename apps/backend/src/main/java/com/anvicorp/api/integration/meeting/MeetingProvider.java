package com.anvicorp.api.integration.meeting;

/**
 * Provider-agnostic meeting integration surface. Both
 * {@link com.anvicorp.api.integration.zoom.ZoomService} and
 * {@link com.anvicorp.api.integration.webex.WebexService} implement this so
 * consumers can be migrated to a single typed seam, and the active
 * implementation is selected at runtime by the {@code MEETING_PROVIDER} env
 * variable (see {@link MeetingProviderConfig}).
 *
 * <p>Provider IDs are exposed as {@link String} on this interface even though
 * Zoom's API returns numeric {@code long} ids — keeping the interface
 * provider-agnostic means we don't need to widen DB columns until Phase 2 of
 * the migration. The Zoom adapter does the {@code long}↔{@link String}
 * conversion internally.</p>
 *
 * <p>The legacy {@code ZoomService} public Long-based methods are retained
 * unchanged; existing consumers continue to compile and run. Migration of
 * consumers to this interface is a separate phase that lands after the live
 * WebEx auth + meeting-create probes are verified.</p>
 */
public interface MeetingProvider {

    /** Stable provider name for logging + health output, e.g. {@code "zoom"} or {@code "webex"}. */
    String providerName();

    /** True when the integration is enabled AND has all required credentials. */
    boolean isReady();

    /** True iff all required credential env vars / DB rows are populated. */
    boolean hasCredentials();

    /** True iff the boolean kill-switch is off (e.g. {@code ZOOM_ENABLED=false}). */
    boolean isForceDisabled();

    /**
     * Live auth probe — calls a harmless authenticated endpoint to verify the
     * credentials are still good. Returns a stable identifier (e.g. the host
     * email or display name) on success; throws on failure.
     */
    String probe() throws Exception;

    MeetingResponse createMeeting(MeetingRequest req);

    MeetingResponse updateMeeting(String providerMeetingId, MeetingRequest req);

    MeetingResponse getMeeting(String providerMeetingId);

    void deleteMeeting(String providerMeetingId);
}
