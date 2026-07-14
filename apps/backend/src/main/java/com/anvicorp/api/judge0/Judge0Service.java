package com.anvicorp.api.judge0;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Judge0 (RapidAPI) sandbox adapter — submits intern source code to the
 * remote runner and polls until the verdict lands. Untrusted code MUST
 * always flow through this service; nothing in the JVM directly executes
 * candidate-supplied code.
 *
 * <h2>Boot semantics</h2>
 * Soft-configured. If {@code JUDGE0_RAPIDAPI_KEY} is unset, the bean still
 * constructs and {@link #isConfigured()} returns false; {@link #executeAndAwait}
 * returns {@link CodeExecutionResult#notConfigured()} and {@link #listLanguages}
 * returns an empty list. Boot never fails on missing config.
 *
 * <h2>Error mapping (typed, not exceptions)</h2>
 * <ul>
 *   <li>RapidAPI 429 (quota) → {@link CodeExecutionResult#quotaExceeded()}.</li>
 *   <li>Polling exhausts before Judge0 returns a terminal status →
 *       {@link CodeExecutionResult#pollTimedOut()}.</li>
 *   <li>Any other non-2xx / network failure →
 *       {@link CodeExecutionResult#unavailable()}.</li>
 * </ul>
 * Compile errors and runtime errors come back as real Judge0 statuses
 * (6 / 7-12) and are surfaced verbatim — they are valid execution
 * outcomes, not exceptions.
 *
 * <h2>Secrets</h2>
 * The API key is read from {@code JUDGE0_RAPIDAPI_KEY} only, never
 * committed to source control, and never written to any log line.
 */
@Service
@Slf4j
public class Judge0Service {

    // Sandbox limits — passed on every submit so a runaway loop or an
    // out-of-memory crash bounds the resources Judge0 burns on our quota.
    static final int CPU_TIME_LIMIT_SECONDS = 5;
    static final int WALL_TIME_LIMIT_SECONDS = 10;
    static final int MEMORY_LIMIT_KB = 128_000;

    // Polling shape — short interval, hard cap. 15 * 400ms = 6s ceiling
    // before we give up and return pollTimedOut(). Judge0's CE worker is
    // usually sub-second for trivial code; this leaves slack for queue
    // latency without letting the request hang.
    private static final int POLL_MAX_ATTEMPTS = 15;
    private static final long POLL_INTERVAL_MS = 400L;

    // Defensive cap on source code length surfaced by the service layer
    // (the controller enforces its own with @Size on the DTO; this one
    // is the last line of defence against a non-DTO caller).
    static final int MAX_SOURCE_LENGTH = 50_000;

    // Languages cache — Judge0's language list is effectively static.
    // Refresh every 24h to pick up new runners without a restart.
    private static final Duration LANGUAGES_TTL = Duration.ofHours(24);

    private final String apiKey;
    private final String baseUrl;
    private final String host;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Language cache fields.
    private final ReentrantLock languagesLock = new ReentrantLock();
    private volatile List<LanguageResponse> cachedLanguages;
    private volatile Instant languagesFetchedAt;

    public Judge0Service(
            @Value("${app.judge0.api-key:}") String apiKey,
            @Value("${app.judge0.base-url:https://judge0-ce.p.rapidapi.com}") String baseUrl,
            @Value("${app.judge0.host:judge0-ce.p.rapidapi.com}") String host
    ) {
        this.apiKey = trimToNull(apiKey);
        this.baseUrl = trimTrailingSlash(trimToNull(baseUrl));
        this.host = trimToNull(host);

        if (!isConfigured()) {
            log.warn("Judge0 not configured — set JUDGE0_RAPIDAPI_KEY to enable. "
                    + "Code-execution endpoints will return a typed "
                    + "'sandbox not configured' response until set.");
        } else {
            log.info("Judge0 configured (host={}).", this.host);
        }
    }

    public boolean isConfigured() {
        return apiKey != null && baseUrl != null && host != null;
    }

    // ── Public surface ──────────────────────────────────────────────────────

    /**
     * Submits source code to Judge0, polls until a terminal status lands,
     * and returns a normalized result. Never throws — all error modes
     * map to a typed {@link CodeExecutionResult}.
     */
    public CodeExecutionResult executeAndAwait(String sourceCode, int languageId, String stdin) {
        if (!isConfigured()) return CodeExecutionResult.notConfigured();
        if (sourceCode == null || sourceCode.isEmpty()) {
            // Belt-and-braces — the controller's @Size guard already covers
            // the empty / oversize cases; this just keeps the service safe
            // when invoked from anywhere else.
            return CodeExecutionResult.unavailable();
        }
        if (sourceCode.length() > MAX_SOURCE_LENGTH) {
            return CodeExecutionResult.unavailable();
        }

        String token;
        try {
            token = submitSubmission(sourceCode, languageId, stdin);
        } catch (QuotaExceededException qee) {
            return CodeExecutionResult.quotaExceeded();
        } catch (Exception e) {
            log.warn("Judge0 submit failed: {}", e.getMessage());
            return CodeExecutionResult.unavailable();
        }
        if (token == null || token.isBlank()) {
            log.warn("Judge0 submit returned no token");
            return CodeExecutionResult.unavailable();
        }

        for (int attempt = 0; attempt < POLL_MAX_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return CodeExecutionResult.unavailable();
            }
            try {
                CodeExecutionResult terminal = pollOnce(token);
                if (terminal != null) return terminal;
            } catch (QuotaExceededException qee) {
                return CodeExecutionResult.quotaExceeded();
            } catch (Exception e) {
                log.warn("Judge0 poll attempt {} failed: {}", attempt, e.getMessage());
                return CodeExecutionResult.unavailable();
            }
        }
        return CodeExecutionResult.pollTimedOut();
    }

    /**
     * Cached fetch of Judge0's language list. First call hits Judge0 under
     * a lock; subsequent calls return the cached value until {@link #LANGUAGES_TTL}
     * elapses, then the next call refreshes.
     */
    public List<LanguageResponse> listLanguages() {
        if (!isConfigured()) return List.of();
        if (isLanguagesCacheFresh()) return cachedLanguages;

        languagesLock.lock();
        try {
            if (isLanguagesCacheFresh()) return cachedLanguages;
            cachedLanguages = fetchLanguages();
            languagesFetchedAt = Instant.now();
            return cachedLanguages;
        } catch (Exception e) {
            log.warn("Judge0 /languages fetch failed: {}", e.getMessage());
            // Return whatever's cached (even if stale) rather than nothing;
            // if nothing was ever cached, return empty.
            return cachedLanguages != null ? cachedLanguages : List.of();
        } finally {
            languagesLock.unlock();
        }
    }

    // ── HTTP plumbing ──────────────────────────────────────────────────────

    private String submitSubmission(String sourceCode, int languageId, String stdin)
            throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "source_code", sourceCode,
                "language_id", languageId,
                "stdin", stdin == null ? "" : stdin,
                "cpu_time_limit", CPU_TIME_LIMIT_SECONDS,
                "wall_time_limit", WALL_TIME_LIMIT_SECONDS,
                "memory_limit", MEMORY_LIMIT_KB
        ));
        HttpRequest request = baseRequest("/submissions?base64_encoded=false&wait=false")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            throw new QuotaExceededException();
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "Judge0 /submissions responded " + response.statusCode()
                            + ": " + truncate(response.body(), 500));
        }
        JsonNode root = objectMapper.readTree(response.body());
        return root.path("token").asText(null);
    }

    /**
     * One poll attempt. Returns a terminal {@link CodeExecutionResult} if
     * Judge0 reports status.id > 2; returns {@code null} if still
     * In Queue (1) or Processing (2) so the caller keeps polling.
     */
    private CodeExecutionResult pollOnce(String token) throws Exception {
        String path = "/submissions/" + token
                + "?base64_encoded=false"
                + "&fields=stdout,stderr,compile_output,status,time,memory,exit_code";
        HttpRequest request = baseRequest(path)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            throw new QuotaExceededException();
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "Judge0 /submissions/{token} responded " + response.statusCode()
                            + ": " + truncate(response.body(), 500));
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode status = root.path("status");
        int statusId = status.path("id").asInt(-99);
        // 1 = In Queue, 2 = Processing — keep polling.
        if (statusId == 1 || statusId == 2) return null;

        return new CodeExecutionResult(
                emptyToNull(root.path("stdout").asText(null)),
                emptyToNull(root.path("stderr").asText(null)),
                emptyToNull(root.path("compile_output").asText(null)),
                statusId,
                status.path("description").asText(null),
                emptyToNull(root.path("time").asText(null)),
                root.path("memory").isInt() ? root.path("memory").asInt() : null,
                root.path("exit_code").isInt() ? root.path("exit_code").asInt() : null
        );
    }

    private List<LanguageResponse> fetchLanguages() throws Exception {
        HttpRequest request = baseRequest("/languages").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "Judge0 /languages responded " + response.statusCode()
                            + ": " + truncate(response.body(), 500));
        }
        JsonNode arr = objectMapper.readTree(response.body());
        List<LanguageResponse> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode row : arr) {
                int id = row.path("id").asInt(-1);
                String name = row.path("name").asText(null);
                if (id > 0 && name != null) {
                    out.add(new LanguageResponse(id, name));
                }
            }
        }
        return List.copyOf(out);
    }

    private HttpRequest.Builder baseRequest(String pathAndQuery) {
        return HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(Duration.ofSeconds(15))
                .header("X-RapidAPI-Key", apiKey)
                .header("X-RapidAPI-Host", host);
    }

    private boolean isLanguagesCacheFresh() {
        return cachedLanguages != null
                && languagesFetchedAt != null
                && Instant.now().minus(LANGUAGES_TTL).isBefore(languagesFetchedAt);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return null;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        return s.isEmpty() ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Internal sentinel for the 429 path so the caller can map it cleanly. */
    private static final class QuotaExceededException extends RuntimeException {
        QuotaExceededException() { super("Judge0 quota exceeded"); }
    }
}
