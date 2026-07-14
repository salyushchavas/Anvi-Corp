package com.anvicorp.api.integration.zoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.anvicorp.api.integration.meeting.MeetingProvider;
import com.anvicorp.api.integration.meeting.MeetingRequest;
import com.anvicorp.api.integration.meeting.MeetingResponse;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Server-to-Server OAuth client for the Zoom Meetings API. Mirrors the
 * pattern in {@code GitHubService}: soft-configured, token cached in-process,
 * startup probe on boot, structured error logging.
 *
 * <h2>Boot semantics</h2>
 * Soft-configured. Integration is considered <b>ready</b> when all three
 * credential env vars ({@code ZOOM_ACCOUNT_ID}, {@code ZOOM_CLIENT_ID},
 * {@code ZOOM_CLIENT_SECRET}) are present <i>and</i> the optional
 * {@code ZOOM_ENABLED} kill-switch hasn't been explicitly set to
 * {@code false}. The default for {@code ZOOM_ENABLED} is {@code true} —
 * the previous boolean toggle was a footgun (creds set + enabled
 * forgotten = silently degraded scheduling). Set {@code ZOOM_ENABLED=false}
 * only when you want to force-disable Zoom while leaving creds in place.
 *
 * <p>When not ready, every public meeting CRUD method throws
 * {@link IllegalStateException} with a clean message. The bean still
 * constructs so the rest of the app boots.</p>
 *
 * <h2>Token cache</h2>
 * One token per JVM instance; refreshed when the cached value is within
 * {@link #REFRESH_WINDOW} of expiry. Serialised by a {@link ReentrantLock} so
 * concurrent callers piggy-back on one refresh.
 *
 * <h2>Field-level RBAC reminder</h2>
 * {@link ZoomMeetingResponse#startUrl()} is the HOST start URL — it MUST NOT
 * be returned to applicants. Persist it on the interview row but exclude it
 * from intern-facing DTOs.
 */
@Service
@Slf4j
public class ZoomService implements MeetingProvider {

    private static final String PROVIDER_NAME = "zoom";

    private static final Duration REFRESH_WINDOW = Duration.ofSeconds(60);
    private static final String OAUTH_URL = "https://zoom.us/oauth/token";
    private static final String API_BASE = "https://api.zoom.us/v2";

    private final String accountId;
    private final String clientId;
    private final String clientSecret;
    /** Force-disable override. Defaults to true so cred-presence alone enables Zoom. */
    @Getter
    private final boolean enabled;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile String cachedToken;
    private volatile Instant cachedExpiresAt;

    /** Last successful /users/me email — surfaced by the admin health endpoint. */
    @Getter
    private volatile String authenticatedHostEmail;

    /** Last startup-probe error, surfaced by admin health endpoint when set. */
    @Getter
    private volatile String lastProbeError;

    public ZoomService(
            @Value("${zoom.account-id:}") String accountId,
            @Value("${zoom.client-id:}") String clientId,
            @Value("${zoom.client-secret:}") String clientSecret,
            @Value("${zoom.enabled:true}") boolean enabled
    ) {
        this.accountId = trimToNull(accountId);
        this.clientId = trimToNull(clientId);
        this.clientSecret = trimToNull(clientSecret);
        this.enabled = enabled;

        if (!enabled) {
            log.info("[Zoom] force-disabled (ZOOM_ENABLED=false). Interview "
                    + "scheduling will persist rows without Zoom links.");
        } else if (!hasCredentials()) {
            log.info("[Zoom] not configured — set ZOOM_ACCOUNT_ID, ZOOM_CLIENT_ID, "
                    + "ZOOM_CLIENT_SECRET (from a Server-to-Server OAuth app with the "
                    + "meeting:write scope). Scheduling will work without links until "
                    + "creds are added.");
        }
    }

    /**
     * True when all three creds are present and ZOOM_ENABLED hasn't been
     * explicitly flipped to false. Cred presence alone is the gate — the
     * previous explicit enable toggle was the main cause of "Zoom doesn't work":
     * creds were set but the toggle wasn't.
     */
    public boolean isReady() {
        return enabled && hasCredentials();
    }

    /**
     * True when the boolean kill-switch is off (regardless of creds).
     * Used by the admin health endpoint to differentiate "disabled" vs
     * "missing creds" vs "auth failing".
     */
    public boolean isForceDisabled() {
        return !enabled;
    }

    /** True iff all three credential env vars are non-blank. */
    public boolean hasCredentials() {
        return accountId != null && clientId != null && clientSecret != null;
    }

    // ── Startup probe ───────────────────────────────────────────────────────

    @PostConstruct
    void onStartup() {
        if (!isReady()) return;
        // Non-blocking — startup keeps moving if Zoom is slow.
        Thread probe = new Thread(() -> {
            try {
                String email = probeUsersMe();
                authenticatedHostEmail = email;
                lastProbeError = null;
                log.info("[Zoom] authenticated as {}", email);
            } catch (Exception e) {
                lastProbeError = e.getMessage();
                log.warn("[Zoom] TOKEN VALIDATION FAILED: {}", e.getMessage());
            }
        }, "zoom-startup-probe");
        probe.setDaemon(true);
        probe.start();
    }

    /** Calls GET /users/me to verify the token works. Returns the host email. */
    public String probeUsersMe() throws Exception {
        ensureReady();
        HttpResponse<String> resp = sendAuthorized(HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/users/me"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build());
        if (resp.statusCode() >= 300) {
            throw new RuntimeException("Zoom /users/me failed: status="
                    + resp.statusCode() + " body=" + truncate(resp.body()));
        }
        JsonNode node = objectMapper.readTree(resp.body());
        return node.path("email").asText(null);
    }

    // ── MeetingProvider interface adapters ───────────────────────────────────
    //
    // Thin wrappers that translate the provider-agnostic {@link MeetingRequest}
    // / {@link MeetingResponse} contract into the existing Long-based Zoom
    // methods. The legacy public methods (createMeeting(ZoomMeetingRequest)
    // etc.) stay UNCHANGED — existing consumers keep compiling and running
    // unchanged. New consumers (post-WebEx phase) inject MeetingProvider and
    // use these adapter methods instead.

    @Override
    public String providerName() { return PROVIDER_NAME; }

    @Override
    public String probe() throws Exception {
        return probeUsersMe();
    }

    @Override
    public MeetingResponse createMeeting(MeetingRequest req) {
        ZoomMeetingResponse z = createMeeting(toZoomRequest(req));
        return toMeetingResponse(z);
    }

    @Override
    public MeetingResponse updateMeeting(String providerMeetingId, MeetingRequest req) {
        long mid = parseProviderId(providerMeetingId);
        ZoomMeetingResponse z = updateMeeting(mid, toZoomRequest(req));
        return toMeetingResponse(z);
    }

    @Override
    public MeetingResponse getMeeting(String providerMeetingId) {
        long mid = parseProviderId(providerMeetingId);
        ZoomMeetingResponse z = getMeeting(mid);
        return toMeetingResponse(z);
    }

    @Override
    public void deleteMeeting(String providerMeetingId) {
        deleteMeeting(parseProviderId(providerMeetingId));
    }

    private static ZoomMeetingRequest toZoomRequest(MeetingRequest req) {
        // Zoom's hostUserId accepts either an email or the literal "me" — the
        // generic MeetingRequest's hostEmail covers both shapes (consumer
        // passes "me" when no per-host email is set; otherwise the email).
        String hostUserId = (req.hostEmail() == null || req.hostEmail().isBlank())
                ? "me" : req.hostEmail();
        return new ZoomMeetingRequest(
                hostUserId, req.topic(), req.startTime(),
                req.durationMinutes(), req.timezone(), req.agenda());
    }

    private MeetingResponse toMeetingResponse(ZoomMeetingResponse z) {
        if (z == null) return null;
        return new MeetingResponse(
                PROVIDER_NAME,
                String.valueOf(z.meetingId()),
                z.joinUrl(),
                z.startUrl(),
                z.password(),
                z.hostEmail());
    }

    private static long parseProviderId(String providerMeetingId) {
        if (providerMeetingId == null || providerMeetingId.isBlank()) {
            throw new IllegalArgumentException("providerMeetingId is required");
        }
        try {
            return Long.parseLong(providerMeetingId.trim());
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException(
                    "Zoom providerMeetingId must be numeric: " + providerMeetingId);
        }
    }

    // ── Meeting CRUD (legacy Long-based — unchanged) ─────────────────────────

    public ZoomMeetingResponse createMeeting(ZoomMeetingRequest req) {
        ensureReady();
        String hostUserId = req.hostUserId() != null && !req.hostUserId().isBlank()
                ? req.hostUserId() : "me";
        try {
            ObjectNode body = buildMeetingPayload(req);
            HttpResponse<String> resp = sendAuthorized(HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/users/" + encode(hostUserId) + "/meetings"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build());
            return parseMeetingResponse(resp, "createMeeting");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Zoom createMeeting failed: " + e.getMessage(), e);
        }
    }

    public ZoomMeetingResponse updateMeeting(long meetingId, ZoomMeetingRequest req) {
        ensureReady();
        try {
            ObjectNode body = buildMeetingPayload(req);
            HttpResponse<String> resp = sendAuthorized(HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/meetings/" + meetingId))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build());
            if (resp.statusCode() >= 300 && resp.statusCode() != 204) {
                throw new RuntimeException("Zoom updateMeeting failed: status="
                        + resp.statusCode() + " body=" + truncate(resp.body()));
            }
            // PATCH /meetings/{id} returns 204 No Content on success — refetch to get fresh fields.
            return getMeeting(meetingId);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Zoom updateMeeting failed: " + e.getMessage(), e);
        }
    }

    public ZoomMeetingResponse getMeeting(long meetingId) {
        ensureReady();
        try {
            HttpResponse<String> resp = sendAuthorized(HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/meetings/" + meetingId))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build());
            return parseMeetingResponse(resp, "getMeeting");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Zoom getMeeting failed: " + e.getMessage(), e);
        }
    }

    public void deleteMeeting(long meetingId) {
        ensureReady();
        try {
            HttpResponse<String> resp = sendAuthorized(HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/meetings/" + meetingId))
                    .timeout(Duration.ofSeconds(10))
                    .DELETE()
                    .build());
            // 204 No Content on success; 404 on already-deleted (treat as success).
            if (resp.statusCode() >= 300 && resp.statusCode() != 404) {
                throw new RuntimeException("Zoom deleteMeeting failed: status="
                        + resp.statusCode() + " body=" + truncate(resp.body()));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Zoom deleteMeeting failed: " + e.getMessage(), e);
        }
    }

    // ── Token cache ─────────────────────────────────────────────────────────

    private String getAccessToken() throws Exception {
        Instant exp = cachedExpiresAt;
        if (cachedToken != null && exp != null
                && Instant.now().isBefore(exp.minus(REFRESH_WINDOW))) {
            return cachedToken;
        }
        refreshLock.lock();
        try {
            exp = cachedExpiresAt;
            if (cachedToken != null && exp != null
                    && Instant.now().isBefore(exp.minus(REFRESH_WINDOW))) {
                return cachedToken;
            }
            return refreshToken();
        } finally {
            refreshLock.unlock();
        }
    }

    private String refreshToken() throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        URI uri = URI.create(OAUTH_URL
                + "?grant_type=account_credentials&account_id=" + encode(accountId));
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            // Body may contain "reason":"..." — log truncated to avoid spilling
            // creds via echoed headers Zoom sometimes returns.
            throw new RuntimeException("Zoom token fetch failed: status="
                    + resp.statusCode() + " body=" + truncate(resp.body()));
        }
        JsonNode node = objectMapper.readTree(resp.body());
        String token = node.path("access_token").asText(null);
        long expiresIn = node.path("expires_in").asLong(3600);
        if (token == null) {
            throw new RuntimeException("Zoom token fetch returned no access_token");
        }
        cachedToken = token;
        cachedExpiresAt = Instant.now().plusSeconds(expiresIn);
        return token;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private HttpResponse<String> sendAuthorized(HttpRequest probe) throws Exception {
        String token = getAccessToken();
        HttpRequest authed = HttpRequest.newBuilder(probe, (n, v) -> true)
                .header("Authorization", "Bearer " + token)
                .build();
        HttpResponse<String> resp = http.send(authed, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 429) {
            String retryAfter = resp.headers().firstValue("retry-after").orElse("?");
            log.warn("[Zoom] rate limited (429), retry-after={}", retryAfter);
        } else if (resp.statusCode() >= 400) {
            log.warn("[Zoom] {} {} -> status={} body={}",
                    authed.method(), authed.uri(),
                    resp.statusCode(), truncate(resp.body()));
        }
        return resp;
    }

    private ZoomMeetingResponse parseMeetingResponse(HttpResponse<String> resp, String op)
            throws Exception {
        if (resp.statusCode() >= 300) {
            throw new RuntimeException("Zoom " + op + " failed: status="
                    + resp.statusCode() + " body=" + truncate(resp.body()));
        }
        JsonNode node = objectMapper.readTree(resp.body());
        long meetingId = node.path("id").asLong();
        String joinUrl = node.path("join_url").asText(null);
        String startUrl = node.path("start_url").asText(null);
        String password = node.path("password").asText(null);
        String hostEmail = node.path("host_email").asText(null);
        return new ZoomMeetingResponse(meetingId, joinUrl, startUrl, password, hostEmail);
    }

    private ObjectNode buildMeetingPayload(ZoomMeetingRequest req) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("topic", req.topic());
        body.put("type", 2); // 2 = scheduled meeting
        body.put("duration", clampDuration(req.durationMinutes()));
        String tz = (req.timezone() == null || req.timezone().isBlank())
                ? "UTC" : req.timezone();
        body.put("timezone", tz);
        if (req.startTime() != null) {
            body.put("start_time",
                    DateTimeFormatter.ISO_INSTANT.format(req.startTime().atZone(ZoneOffset.UTC).toInstant()));
        }
        if (req.agenda() != null && !req.agenda().isBlank()) {
            body.put("agenda", req.agenda());
        }
        ObjectNode settings = body.putObject("settings");
        // Waiting room OFF + join-before-host ON so the intern enters
        // directly without sitting on a host-admission lobby. The host
        // (ERM/trainer/evaluator) still starts the meeting via start_url
        // when they're ready; if the intern arrives first they're
        // already inside the room.
        //
        // meeting_authentication=false: guests can join without a Zoom
        // account. When a guest joins via the web client they're prompted
        // for their name on Zoom's join landing page — this is the
        // mechanism that makes participants identifiable. (Setting it to
        // true would force every joiner to sign into Zoom, which is the
        // opposite of what we want.)
        //
        // IMPORTANT LIMITATION the operator should know about:
        //   Zoom can ONLY prompt joiners who arrive via the web client
        //   without being signed in. Joiners on the native Zoom app who
        //   are signed in will use their Zoom account display name and
        //   skip the prompt — Zoom doesn't expose a per-meeting setting
        //   to override that. So:
        //     - If interns share / are signed in to the company Zoom
        //       account on their devices, they will all show as the
        //       company name regardless of these settings.
        //     - Reliable fix is org-side: each person uses their own
        //       Zoom account, signs out before joining, or joins via
        //       browser without the desktop app installed.
        //   The locked-in code-side alternative is Zoom's registrant
        //   flow (per-participant POST /v2/meetings/{id}/registrants
        //   with a name + email; returns a per-registrant join URL
        //   whose name is locked). Not implemented here — significantly
        //   more invasive than these per-meeting settings.
        //
        // Zoom account-level Waiting Room can be locked ON at the
        // account tier and override the per-meeting waiting_room=false
        // — confirm Zoom Admin > Settings > Meeting > In Meeting
        // (Advanced) > Waiting Room is OFF (or unlocked) for this
        // account.
        settings.put("join_before_host", true);
        settings.put("waiting_room", false);
        settings.put("meeting_authentication", false);
        settings.put("mute_upon_entry", true);
        return body;
    }

    private int clampDuration(int minutes) {
        if (minutes < 15) return 15;
        if (minutes > 240) return 240;
        return minutes;
    }

    private void ensureReady() {
        if (!isReady()) {
            throw new IllegalStateException(
                    "Zoom integration is not enabled or credentials are missing");
        }
    }

    private static String encode(String s) {
        return s == null ? "" : java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }
}
