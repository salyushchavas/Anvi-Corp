package com.anvicorp.api.controller;

import com.anvicorp.api.integration.meeting.MeetingProvider;
import com.anvicorp.api.integration.meeting.MeetingResponse;
import com.anvicorp.api.repository.DoubtRequestRepository;
import com.anvicorp.api.repository.InternEvaluationRepository;
import com.anvicorp.api.repository.InterviewRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.QaSessionRepository;
import com.anvicorp.api.repository.WeeklyMeetingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-demand fresh fetch of a meeting's host start URL. Zoom's
 * {@code start_url} is the one-click host link — clicking it opens Zoom
 * and starts the meeting AS the host without requiring a Zoom sign-in —
 * but the URL is time-limited (~2h after create) so a stale stored copy
 * stops working before the meeting starts. This endpoint refetches the
 * meeting via {@link MeetingProvider#getMeeting} every call so the
 * scheduler's modal/email always serves a current link.
 *
 * <p>Staff-only (no INTERN access). To avoid acting as a generic
 * start-URL proxy for arbitrary provider meeting ids we verify the
 * supplied id exists in at least one of our three meeting tables
 * ({@code weekly_meetings}, {@code interviews}, {@code intern_evaluations})
 * before forwarding to the provider. 404s otherwise.</p>
 *
 * <p>Returns a small shape: {@code { providerMeetingId, startUrl, joinUrl,
 * available, fetchedAt }}. {@code available=false} when the provider
 * returned no start URL (provider misconfig or transient failure) — the
 * frontend can fall back to the stored copy in that case.</p>
 *
 * <p>The controller is mounted at {@code /api/v1/meetings/{providerMeetingId}/host-start};
 * the old {@code /host-key} path is preserved as a back-compat alias for
 * any client cached against the Webex-era shape (the body is now
 * Zoom-shaped regardless).</p>
 */
@RestController
@RequestMapping("/api/v1/meetings")
@RequiredArgsConstructor
@Slf4j
public class MeetingHostKeyController {

    private final MeetingProvider meetingProvider;
    private final WeeklyMeetingRepository weeklyMeetingRepository;
    private final InterviewRepository interviewRepository;
    private final InternEvaluationRepository internEvaluationRepository;
    private final ProjectRepository projectRepository;
    private final DoubtRequestRepository doubtRequestRepository;
    private final QaSessionRepository qaSessionRepository;

    @GetMapping({"/{providerMeetingId}/host-start", "/{providerMeetingId}/host-key"})
    @PreAuthorize("hasAnyRole('TRAINER', 'REPORTING_MANAGER', 'MANAGER', 'ERM', 'EVALUATOR', 'SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> hostStart(@PathVariable String providerMeetingId) {
        boolean known = weeklyMeetingRepository.findFirstByZoomMeetingId(providerMeetingId).isPresent()
                || interviewRepository.findFirstByZoomMeetingId(providerMeetingId).isPresent()
                || internEvaluationRepository.findFirstByZoomMeetingId(providerMeetingId).isPresent()
                || projectRepository.findFirstByKtZoomMeetingId(providerMeetingId).isPresent()
                || doubtRequestRepository.findFirstByZoomMeetingId(providerMeetingId).isPresent()
                || qaSessionRepository.findFirstByZoomMeetingId(providerMeetingId).isPresent();
        if (!known) {
            return ResponseEntity.notFound().build();
        }
        String startUrl = null;
        String joinUrl = null;
        try {
            MeetingResponse fresh = meetingProvider.getMeeting(providerMeetingId);
            if (fresh != null) {
                startUrl = fresh.startUrl();
                joinUrl = fresh.joinUrl();
            }
        } catch (Exception e) {
            log.warn("[MeetingHostStart] fresh fetch for {} failed (non-fatal): {}",
                    providerMeetingId, e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerMeetingId", providerMeetingId);
        body.put("startUrl", startUrl);
        body.put("joinUrl", joinUrl);
        body.put("available", startUrl != null && !startUrl.isBlank());
        body.put("fetchedAt", Instant.now().toString());
        // start_url is short-lived (~2h on Zoom) — never let intermediaries
        // cache the response.
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
