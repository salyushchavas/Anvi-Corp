package com.anvicorp.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Bot-signup mitigation — Cloudflare Turnstile server-side token
 * verification for the public {@code /auth/register} endpoint (and any
 * future public write endpoint that adds a challenge).
 *
 * <p>Turnstile chosen over hCaptcha / reCAPTCHA:</p>
 * <ul>
 *   <li>Free at every tier, no per-request cap for our size.</li>
 *   <li>Privacy-friendly — no third-party tracking cookies, no cross-
 *       site fingerprinting; better fit for a careers site the ERM
 *       tells applicants is safe.</li>
 *   <li>Interactive challenge is a checkbox at worst; most humans see
 *       an invisible pass. Distributed / slow bots that survive
 *       rate-limiting fail Turnstile's browser attestation.</li>
 *   <li>Server verification is a single POST to
 *       {@code challenges.cloudflare.com/turnstile/v0/siteverify}
 *       with the token — no SDK, no long-lived clientlib on our
 *       classpath.</li>
 * </ul>
 *
 * <h2>Config</h2>
 * <p>Read from Spring properties (all wired through {@code application.properties}):</p>
 * <ul>
 *   <li>{@code app.captcha.turnstile.enabled} — master switch. Default
 *       {@code false} so local dev + CI don't need the secret. Set
 *       {@code true} in prod.</li>
 *   <li>{@code app.captcha.turnstile.secret} — the Turnstile SITE
 *       secret from the Cloudflare dashboard. Injected via env
 *       {@code TURNSTILE_SECRET}. When enabled but blank, the verifier
 *       fails CLOSED (rejects) so a mis-configured deploy never
 *       silently ships CAPTCHA-less.</li>
 * </ul>
 *
 * <h2>Failure modes</h2>
 * <ul>
 *   <li>Disabled → {@link #verify} returns true unconditionally
 *       (dev convenience).</li>
 *   <li>Enabled + secret blank → returns false (fail closed — refuses
 *       to be silently misconfigured in prod).</li>
 *   <li>Enabled + token blank → returns false.</li>
 *   <li>Enabled + Cloudflare unreachable / timeout → returns false
 *       (fail closed on network too — Turnstile is a synchronous
 *       precondition, not a best-effort log).</li>
 *   <li>Enabled + Cloudflare responded but success=false → returns
 *       false with error codes logged for the operator.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TurnstileVerifier {

    private static final URI SITEVERIFY = URI.create(
            "https://challenges.cloudflare.com/turnstile/v0/siteverify");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;

    @Value("${app.captcha.turnstile.enabled:false}")
    private boolean enabled;

    @Value("${app.captcha.turnstile.secret:}")
    private String secret;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    /**
     * @param token    the {@code cf-turnstile-response} field from the
     *                 client's form submission. May be null/blank; the
     *                 verifier handles that as a rejection when enabled.
     * @param remoteIp the client IP as seen at the edge; passed to
     *                 Cloudflare so their risk model can factor in a
     *                 mismatch between token issuance IP and use IP.
     *                 Null is tolerated but weakens the check.
     * @return true iff the token is valid AND Turnstile accepts it, OR
     *         the verifier is disabled (dev). False when enabled and
     *         anything about the token verification fails.
     */
    public boolean verify(String token, String remoteIp) {
        if (!enabled) {
            // Dev / CI path — no secret configured, no HTTP call, no
            // gate. This is the ONLY branch that returns true without
            // a Cloudflare-signed pass.
            return true;
        }
        if (secret == null || secret.isBlank()) {
            log.warn("[Turnstile] enabled=true but secret is blank — "
                    + "rejecting register. Set TURNSTILE_SECRET in the "
                    + "deploy env to fix.");
            return false;
        }
        if (token == null || token.isBlank()) {
            log.info("[Turnstile] rejected — missing token on register");
            return false;
        }
        try {
            StringBuilder body = new StringBuilder();
            body.append("secret=").append(URLEncoder.encode(secret, StandardCharsets.UTF_8));
            body.append("&response=").append(URLEncoder.encode(token, StandardCharsets.UTF_8));
            if (remoteIp != null && !remoteIp.isBlank()) {
                body.append("&remoteip=").append(URLEncoder.encode(remoteIp, StandardCharsets.UTF_8));
            }
            HttpRequest req = HttpRequest.newBuilder(SITEVERIFY)
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("[Turnstile] siteverify http={} — rejecting",
                        res.statusCode());
                return false;
            }
            JsonNode root = objectMapper.readTree(res.body());
            boolean success = root.path("success").asBoolean(false);
            if (!success) {
                // Log error codes (e.g. "invalid-input-response",
                // "timeout-or-duplicate") so ops can distinguish a
                // legitimate bad token from a config mistake without
                // exposing the codes to the client (info-leak).
                JsonNode errCodes = root.path("error-codes");
                log.info("[Turnstile] rejected — success=false errorCodes={}",
                        errCodes.isMissingNode() ? "[]" : errCodes.toString());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("[Turnstile] siteverify failed with {}: {} — rejecting",
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /** Test seam — package-private for unit tests to flip config without
     *  requiring a full Spring context. */
    void configure(boolean enabled, String secret) {
        this.enabled = enabled;
        this.secret = secret;
    }
}
