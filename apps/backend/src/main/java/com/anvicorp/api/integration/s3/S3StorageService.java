package com.anvicorp.api.integration.s3;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * AWS S3 storage capability. Mirrors the soft-configured pattern in
 * {@code GitHubService} + {@code ZoomService}: integration is considered
 * <b>ready</b> only when all four required env vars are present
 * ({@code AWS_S3_BUCKET}, {@code AWS_REGION}, {@code AWS_ACCESS_KEY_ID},
 * {@code AWS_SECRET_ACCESS_KEY}). When not ready, every public method
 * throws {@link IllegalStateException} with a clean message; the bean
 * still constructs so boot is unaffected.
 *
 * <h2>S3-compatible endpoints</h2>
 * Set {@code AWS_S3_ENDPOINT_OVERRIDE} to point at Cloudflare R2, MinIO,
 * Backblaze B2, etc. Force path-style addressing via
 * {@code AWS_S3_PATH_STYLE=true} when the provider doesn't support
 * virtual-host-style URLs (R2 is fine either way; MinIO needs path-style).
 *
 * <h2>Key layout</h2>
 * The {@link #key(String, String...)} helper composes a canonical key
 * with an optional brand prefix (default empty), making the multi-brand
 * key layout — e.g. {@code <brand>/documents/<userId>/<uuid>.bin} —
 * consistent everywhere we hand out a key. Concrete callers (documents,
 * resumes) will compose their own prefixes via the helper rather than
 * hand-rolling string concatenation.
 *
 * <h2>What this phase does NOT do</h2>
 * Wire any existing read/write path. {@code DocumentVaultService} +
 * {@code ResumeService} are untouched. This is capability only.
 */
@Service("careersS3StorageService")
@Slf4j
public class S3StorageService {

    private final String bucket;
    private final String region;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String endpointOverride;
    private final boolean pathStyleAccess;
    /**
     * Brand-scoped key prefix (e.g. {@code "skyzen"}). Empty by default;
     * single-brand deploys don't need it. Multi-brand deploys set it to
     * keep buckets shared but namespaces isolated. Always sanitised to
     * end with a single trailing slash when non-empty (or empty string).
     */
    @Getter
    private final String brandKeyPrefix;

    @Getter
    private final boolean enabled;

    /** Last successful bucket-probe outcome, surfaced by the admin health endpoint. */
    @Getter
    private volatile String authenticatedBucket;
    @Getter
    private volatile String lastProbeError;

    private volatile S3Client client;
    private volatile S3Presigner presigner;

    public S3StorageService(
            @Value("${app.s3.bucket:}") String bucket,
            @Value("${app.s3.region:}") String region,
            @Value("${app.s3.access-key-id:}") String accessKeyId,
            @Value("${app.s3.secret-access-key:}") String secretAccessKey,
            @Value("${app.s3.endpoint-override:}") String endpointOverride,
            @Value("${app.s3.path-style:false}") boolean pathStyleAccess,
            @Value("${app.s3.brand-prefix:}") String brandKeyPrefix,
            @Value("${app.s3.enabled:true}") boolean enabled
    ) {
        this.bucket = trimToNull(bucket);
        this.region = trimToNull(region);
        this.accessKeyId = trimToNull(accessKeyId);
        this.secretAccessKey = trimToNull(secretAccessKey);
        this.endpointOverride = trimToNull(endpointOverride);
        this.pathStyleAccess = pathStyleAccess;
        this.brandKeyPrefix = normaliseBrandPrefix(brandKeyPrefix);
        this.enabled = enabled;

        if (!enabled) {
            log.info("[S3] force-disabled (AWS_S3_ENABLED=false). Capability available "
                    + "but every call will throw until re-enabled.");
        } else if (!hasCredentials()) {
            log.info("[S3] not configured — set AWS_S3_BUCKET, AWS_REGION, "
                    + "AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY to enable. "
                    + "Documents + resumes continue to use the volume.");
        }
    }

    /** True when bucket + region + credentials are all present AND enabled. */
    public boolean isReady() {
        return enabled && hasCredentials();
    }

    public boolean hasCredentials() {
        return bucket != null && region != null
                && accessKeyId != null && secretAccessKey != null;
    }

    public boolean isForceDisabled() {
        return !enabled;
    }

    public String getBucket() {
        return bucket;
    }

    public String getRegion() {
        return region;
    }

    public String getEndpointOverride() {
        return endpointOverride;
    }

    // ── Boot probe ─────────────────────────────────────────────────────────

    @PostConstruct
    void onStartup() {
        if (!isReady()) return;
        // Non-blocking — startup keeps moving if S3 is slow / unreachable.
        Thread probe = new Thread(() -> {
            try {
                client().headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                authenticatedBucket = bucket;
                lastProbeError = null;
                log.info("[S3] authenticated against bucket={} region={}{}",
                        bucket, region,
                        endpointOverride != null ? " endpoint=" + endpointOverride : "");
            } catch (Exception e) {
                lastProbeError = e.getMessage();
                log.warn("[S3] BUCKET PROBE FAILED for {}: {}", bucket, e.getMessage());
            }
        }, "s3-startup-probe");
        probe.setDaemon(true);
        probe.start();
    }

    @PreDestroy
    void onShutdown() {
        try {
            if (client != null) client.close();
        } catch (Exception ignored) {}
        try {
            if (presigner != null) presigner.close();
        } catch (Exception ignored) {}
    }

    // ── Object CRUD ────────────────────────────────────────────────────────

    /**
     * Upload bytes. Idempotent at the bucket level (PUT overwrites). Caller
     * is responsible for key derivation via {@link #key(String, String...)}.
     */
    public void putObject(String key, byte[] bytes, String contentType) {
        ensureReady();
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .contentLength((long) bytes.length)
                .build();
        client().putObject(req, RequestBody.fromBytes(bytes));
    }

    /** Streaming overload — preferred for large files. */
    public void putObject(String key, InputStream in, long length, String contentType) {
        ensureReady();
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .contentLength(length)
                .build();
        client().putObject(req, RequestBody.fromInputStream(in, length));
    }

    /**
     * Download whole object as bytes. Use {@link #getObjectStream(String)}
     * for large files to avoid the bytes copy.
     */
    public byte[] getObject(String key) {
        ensureReady();
        try (ResponseInputStream<GetObjectResponse> resp = getObjectStream(key)) {
            return resp.readAllBytes();
        } catch (java.io.IOException e) {
            throw new RuntimeException("S3 getObject read failed for key=" + key, e);
        }
    }

    public ResponseInputStream<GetObjectResponse> getObjectStream(String key) {
        ensureReady();
        return client().getObject(GetObjectRequest.builder()
                .bucket(bucket).key(key).build());
    }

    public void deleteObject(String key) {
        ensureReady();
        client().deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket).key(key).build());
    }

    /**
     * Best-effort batch delete. Splits into 1000-key chunks (S3 hard cap on
     * a single DeleteObjects request), de-duplicates the input, and skips
     * null/blank keys. Any per-key server-side failure is logged at WARN
     * with the key + error code so operators can clean up manually — the
     * method never throws for individual failures. Silent no-op when the
     * input is empty OR the service is not ready (dev without S3 creds).
     *
     * <p>If the whole batch endpoint fails (some S3-compatible providers
     * don't implement DeleteObjects) we fall back to per-key
     * {@link #deleteObject} so a single unsupported endpoint doesn't
     * strand every object in the request.</p>
     *
     * @return the count of keys S3 acknowledged as deleted (successful
     *         removals + prior-not-present, since S3 delete is idempotent).
     */
    public int deleteObjects(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        if (!isReady()) {
            log.info("[S3] deleteObjects skipped ({} keys) — S3 not ready", keys.size());
            return 0;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String k : keys) {
            if (k != null && !k.isBlank()) unique.add(k);
        }
        if (unique.isEmpty()) return 0;

        int deleted = 0;
        List<String> batch = new ArrayList<>(1000);
        for (String k : unique) {
            batch.add(k);
            if (batch.size() >= 1000) {
                deleted += runDeleteBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) deleted += runDeleteBatch(batch);
        return deleted;
    }

    private int runDeleteBatch(List<String> batch) {
        List<ObjectIdentifier> ids = new ArrayList<>(batch.size());
        for (String k : batch) ids.add(ObjectIdentifier.builder().key(k).build());
        try {
            DeleteObjectsResponse resp = client().deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder().objects(ids).quiet(false).build())
                    .build());
            int okCount = resp.deleted() != null ? resp.deleted().size() : 0;
            if (resp.errors() != null && !resp.errors().isEmpty()) {
                for (S3Error err : resp.errors()) {
                    log.warn("[S3] batch-delete FAILED key={} code={} message={}",
                            err.key(), err.code(), err.message());
                }
            }
            return okCount;
        } catch (Exception e) {
            log.warn("[S3] batch-delete request threw for {} keys (first={}): {} — "
                    + "falling back to per-key delete",
                    batch.size(), batch.get(0), e.getMessage());
            int ok = 0;
            for (String k : batch) {
                try {
                    deleteObject(k);
                    ok++;
                } catch (Exception ke) {
                    log.warn("[S3] per-key fallback delete FAILED key={}: {}",
                            k, ke.getMessage());
                }
            }
            return ok;
        }
    }

    /**
     * True iff the object exists. Swallows {@link NoSuchKeyException} only;
     * other failures (auth, network) propagate so callers know the answer
     * isn't "definitely absent".
     */
    public boolean exists(String key) {
        ensureReady();
        try {
            client().headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // 404 from non-standard endpoints (R2 sometimes) doesn't always
            // map to NoSuchKeyException — treat HTTP 404 as absent.
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    /** Returns null when the object doesn't exist. */
    public HeadObjectResponse headObject(String key) {
        ensureReady();
        try {
            return client().headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return null;
            throw e;
        }
    }

    /**
     * Presigned GET URL — caller can hand the URL straight to a browser
     * (image preview, PDF inline view) without proxying the bytes through
     * this backend. TTL clamped to [1 minute, 7 days] (AWS limit).
     */
    public String presignGetUrl(String key, Duration ttl) {
        return presignGetUrl(key, ttl, null, null);
    }

    /**
     * Presigned GET URL with response-header overrides.
     *
     * <p>S3 supports {@code response-content-type} and
     * {@code response-content-disposition} query params on presigned
     * GETs — they replace the object's stored Content-Type + Content-
     * Disposition when serving the response. Useful when the on-disk
     * object was written with {@code application/octet-stream} + a
     * generic {@code .bin} storage key but the browser should save
     * with the true type + a meaningful filename.</p>
     *
     * <p>Not suitable for PII-encrypted content (like
     * {@code EDITABLE_DOC_INSTANCE_PDF} vault docs): the presigned URL
     * still serves the raw stored bytes — which is the AES envelope,
     * not the cleartext. Those callers must proxy through
     * {@link com.anvicorp.api.intern.DocumentVaultService#readDocument}
     * so the decrypt-on-read branch runs.</p>
     */
    public String presignGetUrl(String key, Duration ttl,
                                 String responseContentType,
                                 String responseContentDisposition) {
        ensureReady();
        Duration clamped = clampPresignTtl(ttl);
        GetObjectRequest.Builder getReq = GetObjectRequest.builder()
                .bucket(bucket).key(key);
        if (responseContentType != null && !responseContentType.isBlank()) {
            getReq.responseContentType(responseContentType);
        }
        if (responseContentDisposition != null && !responseContentDisposition.isBlank()) {
            getReq.responseContentDisposition(responseContentDisposition);
        }
        PresignedGetObjectRequest signed = presigner().presignGetObject(
                GetObjectPresignRequest.builder()
                        .signatureDuration(clamped)
                        .getObjectRequest(getReq.build())
                        .build());
        return signed.url().toString();
    }

    /**
     * Presigned PUT URL — the browser can upload bytes DIRECTLY to S3
     * without proxying through this backend. Required for anything larger
     * than the multipart cap ({@code spring.servlet.multipart.max-file-size},
     * currently 10 MB) — notably interview recordings (up to 2 GiB).
     *
     * <p>The client MUST send the exact same {@code Content-Type} header
     * on the PUT that we sign here — S3 rejects the upload with a signature
     * mismatch otherwise. Callers should pass the client-declared content
     * type as-is (already validated at the endpoint layer) so the two match.</p>
     *
     * <p>TTL clamped to [1 minute, 7 days] (AWS limit). Recommended: 15-30
     * minutes for large uploads — long enough to survive a slow connection,
     * short enough to keep leaked URLs harmless.</p>
     */
    public String presignPutUrl(String key, String contentType, Duration ttl) {
        ensureReady();
        Duration clamped = clampPresignTtl(ttl);
        PresignedPutObjectRequest signed = presigner().presignPutObject(
                PutObjectPresignRequest.builder()
                        .signatureDuration(clamped)
                        .putObjectRequest(PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .contentType(contentType != null
                                        ? contentType : "application/octet-stream")
                                .build())
                        .build());
        return signed.url().toString();
    }

    // ── Key helper ─────────────────────────────────────────────────────────

    /**
     * Compose a key from path parts under the optional brand prefix.
     * Examples:
     * <pre>{@code
     *   key("documents", userId, uuid + ".bin")
     *     → "documents/{userId}/{uuid}.bin"   (no brand prefix)
     *     → "acme/documents/{userId}/{uuid}.bin"   (brand="acme")
     * }</pre>
     * Each part is trimmed of stray slashes. Empty or null parts are
     * dropped. Brand prefix (if any) is always emitted first.
     */
    public String key(String first, String... rest) {
        StringBuilder sb = new StringBuilder();
        if (!brandKeyPrefix.isEmpty()) {
            sb.append(brandKeyPrefix); // already ends with "/"
        }
        appendPart(sb, first);
        if (rest != null) {
            for (String p : rest) appendPart(sb, p);
        }
        // Strip trailing slash if a part was empty.
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '/') sb.deleteCharAt(len - 1);
        return sb.toString();
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part == null) return;
        String trimmed = part.trim();
        if (trimmed.isEmpty()) return;
        // Strip leading + trailing slashes; preserve interior ones.
        int start = 0, end = trimmed.length();
        while (start < end && trimmed.charAt(start) == '/') start++;
        while (end > start && trimmed.charAt(end - 1) == '/') end--;
        if (start == end) return;
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '/') sb.append('/');
        sb.append(trimmed, start, end);
    }

    /** Convenience: a fresh per-file UUID-based name with a {@code .bin} suffix. */
    public static String randomObjectName() {
        return UUID.randomUUID() + ".bin";
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private S3Client client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) client = buildClient();
            }
        }
        return client;
    }

    private S3Presigner presigner() {
        if (presigner == null) {
            synchronized (this) {
                if (presigner == null) presigner = buildPresigner();
            }
        }
        return presigner;
    }

    private S3Client buildClient() {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .httpClient(UrlConnectionHttpClient.builder().build());
        if (endpointOverride != null) {
            builder.endpointOverride(URI.create(endpointOverride));
        }
        if (pathStyleAccess) {
            builder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    private S3Presigner buildPresigner() {
        var builder = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        if (endpointOverride != null) {
            builder.endpointOverride(URI.create(endpointOverride));
        }
        if (pathStyleAccess) {
            builder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    private void ensureReady() {
        if (!isReady()) {
            throw new IllegalStateException(
                    "S3 storage is not enabled or credentials are missing. "
                            + "Set AWS_S3_BUCKET, AWS_REGION, AWS_ACCESS_KEY_ID, "
                            + "AWS_SECRET_ACCESS_KEY on the deployment.");
        }
    }

    /** AWS limit: presigned URL TTL must be 1 second to 7 days. */
    private static Duration clampPresignTtl(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) return Duration.ofMinutes(15);
        Duration min = Duration.ofMinutes(1);
        Duration max = Duration.ofDays(7);
        if (ttl.compareTo(min) < 0) return min;
        if (ttl.compareTo(max) > 0) return max;
        return ttl;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Normalise a brand-prefix: trim, strip leading slashes, ensure single trailing slash, or empty. */
    private static String normaliseBrandPrefix(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        while (t.startsWith("/")) t = t.substring(1);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        if (t.isEmpty()) return "";
        return t + "/";
    }
}
