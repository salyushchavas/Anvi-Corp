package com.anvicorp.api.integration.meeting;

import com.anvicorp.api.integration.zoom.ZoomService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Binds {@link ZoomService} as the unconditional {@code @Primary}
 * {@link MeetingProvider} for consumer injection. Zoom is the sole meeting
 * provider after the WebEx integration was rolled back — Zoom's
 * {@code POST /v2/users/{userId}/meetings} natively returns both
 * {@code start_url} (one-click host link, no Zoom sign-in required) and
 * {@code join_url} (attendee link), which is the exact "scheduler hosts /
 * intern attends" shape we need without the Webex-style host-key dance.
 *
 * <p>{@code start_url} is time-limited (~2h after creation) so consumers
 * that surface it to schedulers must re-fetch via
 * {@link com.anvicorp.api.integration.zoom.ZoomService#getMeeting(long)}
 * on-demand rather than serve a stale stored value. The HTTP shape for
 * that fresh fetch is exposed at
 * {@code GET /api/v1/meetings/{providerMeetingId}/host-start}.</p>
 *
 * <p>If a future migration needs another provider alongside Zoom,
 * reintroduce a selector here.</p>
 */
@Configuration
@Slf4j
public class MeetingProviderConfig {

    @Bean
    @Primary
    public MeetingProvider primaryMeetingProvider(ZoomService zoomService) {
        log.info("[MeetingProvider] @Primary bound to '{}' (ready={}, forceDisabled={})",
                zoomService.providerName(), zoomService.isReady(),
                zoomService.isForceDisabled());
        return zoomService;
    }
}
