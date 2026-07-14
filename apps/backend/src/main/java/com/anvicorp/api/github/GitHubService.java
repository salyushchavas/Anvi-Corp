package com.anvicorp.api.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * GitHub App authentication. Mints installation access tokens for the
 * configured org installation and caches them in memory until ~5 minutes
 * before expiry.
 *
 * <h2>Boot semantics</h2>
 * Soft-configured. If any of the four env vars are missing or blank the bean
 * still constructs (boot doesn't fail), {@link #isConfigured()} returns
 * {@code false}, and any token-fetch call throws a clean
 * {@link IllegalStateException}. This lets non-prod environments come up
 * without a GitHub App registered while keeping production strict.
 *
 * <h2>Token cache</h2>
 * One token per JVM instance. The cache renews when the cached value is
 * within {@code REFRESH_WINDOW} of expiry; serialised by a {@link ReentrantLock}
 * so concurrent callers piggy-back on one refresh.
 *
 * <h2>This is the single GitHub auth seam</h2>
 * Future GitHub adapters (workspace provisioning, PR ops, activity polling)
 * MUST call {@link #getInstallationToken()} on this bean — never re-sign a
 * JWT or hit {@code /access_tokens} from another class.
 */
@Service
@Slf4j
public class GitHubService {

    /** Refresh when the cached token has less than this remaining. */
    private static final Duration REFRESH_WINDOW = Duration.ofMinutes(5);

    /** Max JWT lifetime per GitHub spec is 10 minutes; 9 minutes leaves slack for clock skew. */
    private static final Duration JWT_TTL = Duration.ofMinutes(9);

    private final String appId;
    private final String installationId;
    private final String orgName;
    private final PrivateKey privateKey;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReentrantLock refreshLock = new ReentrantLock();

    // Token cache.
    private volatile String cachedToken;
    private volatile Instant cachedExpiresAt;

    public GitHubService(
            @Value("${app.github.app-id:}") String appId,
            @Value("${app.github.installation-id:}") String installationId,
            @Value("${app.github.org-name:}") String orgName,
            @Value("${app.github.private-key:}") String privateKeyPem
    ) {
        this.appId = trimToNull(appId);
        this.installationId = trimToNull(installationId);
        this.orgName = trimToNull(orgName);
        this.privateKey = parsePrivateKeyOrNull(privateKeyPem);

        if (!isConfigured()) {
            log.warn("GitHub App not configured — set GITHUB_APP_ID, "
                    + "GITHUB_INSTALLATION_ID, ORG_NAME, and GITHUB_PRIVATE_KEY "
                    + "to enable. Calls that mint installation tokens will throw "
                    + "IllegalStateException until configured.");
        } else {
            log.info("GitHub App configured (appId={}, installationId={}, org={}).",
                    this.appId, this.installationId, this.orgName);
        }
    }

    public boolean isConfigured() {
        return appId != null && installationId != null && orgName != null && privateKey != null;
    }

    public String getOrgName() {
        return orgName;
    }

    // ── Startup validation ─────────────────────────────────────────────────

    /**
     * Once the bean is fully constructed, probe GitHub with the configured
     * credentials so the operator sees the auth status in startup logs.
     *
     * <p>For a GitHub App the right probe is {@code GET /installation/repositories}
     * (an installation token doesn't have a {@code /user} representation —
     * the App is the actor, not a person). The probe also surfaces the rate
     * limit so misconfigured / throttled tokens are visible immediately.</p>
     *
     * <p>Non-blocking — any failure logs at ERROR and boot continues.</p>
     */
    @PostConstruct
    void validateOnStartup() {
        if (!isConfigured()) {
            log.info("[GitHub] integration disabled (App not configured). Collaborator calls will no-op.");
            return;
        }
        try {
            String token = getInstallationToken();
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://api.github.com/installation/repositories?per_page=1"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.error("[GitHub] TOKEN VALIDATION FAILED — collaborator API calls will fail. "
                                + "Status {}, body: {}",
                        res.statusCode(), truncate(res.body(), 300));
                return;
            }
            JsonNode root = objectMapper.readTree(res.body());
            int totalCount = root.path("total_count").asInt(-1);
            String remaining = res.headers().firstValue("X-RateLimit-Remaining").orElse("?");
            log.info("[GitHub] authenticated for installation {} (org={}) — {} repos accessible, rate-limit remaining={}",
                    installationId, orgName, totalCount, remaining);
        } catch (Exception e) {
            // Token mint can throw IllegalStateException on bad key / wrong
            // installation id; the network call can throw IOException. Treat
            // both as auth-broken and surface to the operator without
            // blocking boot.
            log.error("[GitHub] TOKEN VALIDATION FAILED — collaborator API calls will fail. Reason: {}",
                    e.getMessage());
        }
    }

    /**
     * Returns a non-expired installation access token, minting a new one if
     * the cache is empty or near expiry. Thread-safe.
     */
    public String getInstallationToken() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "GitHub App is not configured — cannot mint installation token.");
        }
        if (isCachedTokenFresh()) return cachedToken;

        refreshLock.lock();
        try {
            // Double-check after acquiring the lock — another thread may have
            // refreshed while we were waiting.
            if (isCachedTokenFresh()) return cachedToken;
            mintInstallationToken();
            return cachedToken;
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean isCachedTokenFresh() {
        return cachedToken != null
                && cachedExpiresAt != null
                && Instant.now().plus(REFRESH_WINDOW).isBefore(cachedExpiresAt);
    }

    /**
     * Sign a short-lived JWT and exchange it for an installation token via
     * the GitHub REST API. Updates the cache fields on success.
     */
    private void mintInstallationToken() {
        Instant now = Instant.now();
        String jwt = Jwts.builder()
                .issuer(appId)
                .issuedAt(Date.from(now.minusSeconds(30))) // 30s clock-skew slack
                .expiration(Date.from(now.plus(JWT_TTL)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();

        String url = "https://api.github.com/app/installations/" + installationId
                + "/access_tokens";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + jwt)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "GitHub /access_tokens responded " + response.statusCode()
                                + ": " + truncate(response.body(), 500));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String token = root.path("token").asText(null);
            String expiresAtStr = root.path("expires_at").asText(null);
            if (token == null || expiresAtStr == null) {
                throw new IllegalStateException(
                        "GitHub /access_tokens response missing token or expires_at: "
                                + truncate(response.body(), 500));
            }
            this.cachedToken = token;
            this.cachedExpiresAt = Instant.parse(expiresAtStr);
            log.info("Refreshed GitHub installation token; expires at {}", cachedExpiresAt);
        } catch (IllegalStateException ise) {
            throw ise;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mint GitHub installation token", e);
        }
    }

    // ── Collaborator API ───────────────────────────────────────────────────

    /**
     * Outcome of a collaborator-add call. {@code invitationId} is present for
     * fresh invites (201 + body with an {@code id}) and absent for 204
     * (already-collaborator) or 422 (already-added) responses where the
     * desired end-state already holds. {@code op} carries an enum-friendly
     * label for logging / DB persistence.
     */
    public record AddCollaboratorResult(Op op, Long invitationId) {
        public enum Op { INVITED, ALREADY_COLLABORATOR, ALREADY_ADDED }
        public boolean isSuccess() {
            return op != null;
        }
    }

    /**
     * Add a GitHub user as a collaborator on {@code owner/repo}. Idempotent:
     * if the user is already a direct collaborator GitHub returns 204 (we
     * treat as {@link AddCollaboratorResult.Op#ALREADY_COLLABORATOR}); if a
     * pending invitation already exists GitHub returns 422 (we treat as
     * {@link AddCollaboratorResult.Op#ALREADY_ADDED}). Both are success in
     * the sense that the desired end-state holds.
     *
     * <p>Throws {@link GitHubIntegrationException} on 403 (insufficient
     * scope), 404 (user not found OR repo not in the installation's scope),
     * or any other non-success status. The message is safe to surface to a
     * TE: it never contains the token.</p>
     *
     * <p>Caller is responsible for the precondition that {@link #isConfigured()}
     * is true — this method assumes the App is wired and will throw if the
     * token mint fails.</p>
     */
    public AddCollaboratorResult addCollaborator(String owner, String repo, String githubUsername) {
        String safeOwner = trimToNull(owner);
        String safeRepo = trimToNull(repo);
        String safeUser = trimToNull(githubUsername);
        if (safeOwner == null || safeRepo == null || safeUser == null) {
            throw new GitHubIntegrationException(
                    "owner, repo, and githubUsername are all required");
        }

        log.info("[GitHub] adding collaborator {} to {}/{}", safeUser, safeOwner, safeRepo);

        String token;
        try {
            token = getInstallationToken();
        } catch (Exception e) {
            log.error("[GitHub] token mint failed before collaborator call for {}/{}: {}",
                    safeOwner, safeRepo, e.getMessage());
            throw new GitHubIntegrationException(
                    "GitHub App authentication failed — check installation id and private key");
        }

        String url = "https://api.github.com/repos/" + safeOwner + "/" + safeRepo
                + "/collaborators/" + safeUser;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                // Default permission is "push" — interns get write access on
                // their own working repository. Other values (pull, admin)
                // would need a per-project knob; the current model is one
                // repo per project so push is the right baseline.
                .PUT(HttpRequest.BodyPublishers.ofString("{\"permission\":\"push\"}"))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.error("[GitHub] call failed: network error adding {} to {}/{}: {}",
                    safeUser, safeOwner, safeRepo, e.getMessage());
            throw new GitHubIntegrationException(
                    "GitHub API call failed: " + e.getMessage());
        }
        int status = res.statusCode();
        String body = res.body();

        if (status == 201) {
            Long invitationId = readInvitationId(body);
            log.info("[GitHub] collaborator invited: {} -> {}/{}, invitation id {}",
                    safeUser, safeOwner, safeRepo, invitationId);
            return new AddCollaboratorResult(AddCollaboratorResult.Op.INVITED, invitationId);
        }
        if (status == 204) {
            log.info("[GitHub] collaborator already added (204 no-content): {} -> {}/{}",
                    safeUser, safeOwner, safeRepo);
            return new AddCollaboratorResult(AddCollaboratorResult.Op.ALREADY_COLLABORATOR, null);
        }
        if (status == 422) {
            log.warn("[GitHub] collaborator {} already added to {}/{} (422) — treating as success",
                    safeUser, safeOwner, safeRepo);
            return new AddCollaboratorResult(AddCollaboratorResult.Op.ALREADY_ADDED, null);
        }
        if (status == 404) {
            log.warn("[GitHub] user {} not found OR repo {}/{} not accessible to this installation (404)",
                    safeUser, safeOwner, safeRepo);
            throw new GitHubIntegrationException(
                    "GitHub user '" + safeUser + "' not found, or repository '"
                            + safeOwner + "/" + safeRepo + "' is not in this App's installation scope.");
        }
        if (status == 403) {
            log.warn("[GitHub] insufficient permissions for {}/{} — check App scopes / installation",
                    safeOwner, safeRepo);
            throw new GitHubIntegrationException(
                    "GitHub App lacks permission for '" + safeOwner + "/" + safeRepo
                            + "' — confirm the installation includes this repo and has Members: write.");
        }
        log.error("[GitHub] call failed: {} {}", status, truncate(body, 300));
        throw new GitHubIntegrationException(
                "GitHub returned " + status + " when adding collaborator");
    }

    /**
     * Phase 8 — remove a GitHub user as collaborator from {@code owner/repo}.
     * Idempotent: a 204 is the canonical success; a 404 means the user was
     * never a collaborator (or the repo is gone) and is treated as success
     * because the desired end-state already holds. Returns {@code true} on
     * any of those outcomes; {@code false} on 403 (App lacks permission) or
     * non-2xx unexpected statuses. Network or token failures throw
     * {@link GitHubIntegrationException} so the caller can capture the
     * reason in the exit-record summary.
     */
    public boolean removeCollaborator(String owner, String repo, String githubUsername) {
        String safeOwner = trimToNull(owner);
        String safeRepo = trimToNull(repo);
        String safeUser = trimToNull(githubUsername);
        if (safeOwner == null || safeRepo == null || safeUser == null) {
            throw new GitHubIntegrationException(
                    "owner, repo, and githubUsername are all required");
        }

        log.info("[GitHub] removing collaborator {} from {}/{}", safeUser, safeOwner, safeRepo);

        String token;
        try {
            token = getInstallationToken();
        } catch (Exception e) {
            log.error("[GitHub] token mint failed before remove-collaborator for {}/{}: {}",
                    safeOwner, safeRepo, e.getMessage());
            throw new GitHubIntegrationException(
                    "GitHub App authentication failed — check installation id and private key");
        }

        String url = "https://api.github.com/repos/" + safeOwner + "/" + safeRepo
                + "/collaborators/" + safeUser;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .DELETE()
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.error("[GitHub] remove-collaborator network error {}/{} -> {}: {}",
                    safeOwner, safeRepo, safeUser, e.getMessage());
            throw new GitHubIntegrationException(
                    "GitHub API call failed: " + e.getMessage());
        }
        int status = res.statusCode();
        if (status == 204) {
            log.info("[GitHub] collaborator removed: {} from {}/{}",
                    safeUser, safeOwner, safeRepo);
            return true;
        }
        if (status == 404) {
            // Either the user was never a collaborator or the repo no longer
            // exists — either way the desired end-state holds.
            log.info("[GitHub] remove-collaborator 404 for {}/{} -> {} (treated as success)",
                    safeOwner, safeRepo, safeUser);
            return true;
        }
        if (status == 403) {
            log.warn("[GitHub] remove-collaborator 403 for {}/{} — App lacks permission",
                    safeOwner, safeRepo);
            return false;
        }
        log.warn("[GitHub] remove-collaborator unexpected {} for {}/{} -> {}: {}",
                status, safeOwner, safeRepo, safeUser, truncate(res.body(), 200));
        return false;
    }

    private Long readInvitationId(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode id = root.path("id");
            return id.isNumber() ? id.asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Rate-limit probe (for admin health endpoint) ───────────────────────

    /**
     * Snapshot of GitHub rate-limit state for the configured App installation.
     * Drives the admin health endpoint without leaking the token.
     */
    public record HealthSnapshot(
            boolean enabled,
            boolean authenticated,
            String orgName,
            String installationId,
            Integer rateLimitRemaining,
            Long rateLimitResetAtEpochSeconds,
            String error
    ) {}

    public HealthSnapshot probeHealth() {
        if (!isConfigured()) {
            return new HealthSnapshot(false, false, null, null, null, null,
                    "GitHub App not configured");
        }
        try {
            String token = getInstallationToken();
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/rate_limit"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                return new HealthSnapshot(true, false, orgName, installationId, null, null,
                        "GitHub /rate_limit returned " + res.statusCode());
            }
            JsonNode root = objectMapper.readTree(res.body());
            JsonNode core = root.path("resources").path("core");
            Integer remaining = core.has("remaining") ? core.get("remaining").asInt() : null;
            Long reset = core.has("reset") ? core.get("reset").asLong() : null;
            return new HealthSnapshot(true, true, orgName, installationId, remaining, reset, null);
        } catch (Exception e) {
            return new HealthSnapshot(true, false, orgName, installationId, null, null,
                    "probe failed: " + e.getMessage());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * Parses a PEM-formatted PKCS#8 private key. Returns {@code null} if the
     * input is blank or malformed (so boot doesn't crash on bad config —
     * isConfigured() will return false and consumers throw at call time
     * with a clean message).
     */
    private static PrivateKey parsePrivateKeyOrNull(String pem) {
        if (pem == null || pem.isBlank()) return null;
        try {
            String body = pem.trim();
            // Env-var-friendly newline encoding: support literal \n inside a
            // single-line var by un-escaping. Existing real newlines pass through.
            body = body.replace("\\n", "\n");
            body = body.replaceAll("-----BEGIN (RSA )?PRIVATE KEY-----", "")
                       .replaceAll("-----END (RSA )?PRIVATE KEY-----", "")
                       .replaceAll("\\s+", "");
            byte[] decoded = Base64.getDecoder().decode(body.getBytes(StandardCharsets.US_ASCII));
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception e) {
            log.warn("Failed to parse GITHUB_PRIVATE_KEY (treating as unconfigured): {}",
                    e.getMessage());
            return null;
        }
    }
}
