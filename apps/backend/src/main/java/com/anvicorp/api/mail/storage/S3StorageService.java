package com.anvicorp.api.mail.storage;

import jakarta.annotation.PreDestroy;
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
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;

/**
 * Slim S3 (or S3-compatible: Cloudflare R2 / MinIO / Backblaze) storage capability
 * for mail attachments. Soft-configured: "ready" only when bucket + region +
 * credentials are all present; the bean still constructs when unconfigured (boot
 * is unaffected) and every op throws {@link IllegalStateException} until configured.
 *
 * <p>There is deliberately <b>no presigner</b>: attachments are only ever served
 * through the walled download proxy, never via a presigned URL — so the raw key /
 * URL is never handed out.</p>
 *
 * <p>Env (reserved in A1): {@code AWS_S3_BUCKET}, {@code AWS_S3_REGION},
 * {@code AWS_S3_ACCESS_KEY_ID}, {@code AWS_S3_SECRET_ACCESS_KEY}, and the Anvi
 * {@code ANVI_MAIL_S3_PREFIX} key prefix (default {@code anvi-mail/}). Optional
 * {@code AWS_S3_ENDPOINT_OVERRIDE} + {@code AWS_S3_PATH_STYLE} target S3-compatible
 * providers.</p>
 */
@Service
@Slf4j
public class S3StorageService {

    private final String bucket;
    private final String region;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String endpointOverride;
    private final boolean pathStyle;
    private final String keyPrefix;
    private final boolean enabled;

    private volatile S3Client client;

    public S3StorageService(
            @Value("${app.webmail.storage.s3.bucket:}") String bucket,
            @Value("${app.webmail.storage.s3.region:}") String region,
            @Value("${app.webmail.storage.s3.access-key-id:}") String accessKeyId,
            @Value("${app.webmail.storage.s3.secret-access-key:}") String secretAccessKey,
            @Value("${app.webmail.storage.s3.endpoint-override:}") String endpointOverride,
            @Value("${app.webmail.storage.s3.path-style:false}") boolean pathStyle,
            @Value("${app.webmail.storage.s3.key-prefix:}") String keyPrefix,
            @Value("${app.webmail.storage.s3.enabled:true}") boolean enabled) {
        this.bucket = trimToNull(bucket);
        this.region = trimToNull(region);
        this.accessKeyId = trimToNull(accessKeyId);
        this.secretAccessKey = trimToNull(secretAccessKey);
        this.endpointOverride = trimToNull(endpointOverride);
        this.pathStyle = pathStyle;
        this.keyPrefix = normalisePrefix(keyPrefix);
        this.enabled = enabled;
        if (isReady()) {
            log.info("[S3] mail attachment storage configured (bucket={}, region={}{}).",
                    this.bucket, this.region,
                    this.endpointOverride != null ? ", endpoint=" + this.endpointOverride : "");
        } else {
            log.info("[S3] mail attachment storage NOT configured — attachment uploads return 503 "
                    + "until AWS_S3_BUCKET/REGION/ACCESS_KEY_ID/SECRET_ACCESS_KEY are set.");
        }
    }

    /** True when bucket + region + credentials are present AND enabled. */
    public boolean isReady() {
        return enabled && bucket != null && region != null && accessKeyId != null && secretAccessKey != null;
    }

    public void putObject(String key, byte[] bytes, String contentType) {
        ensureReady();
        client().putObject(PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType != null ? contentType : "application/octet-stream")
                .contentLength((long) bytes.length)
                .build(), RequestBody.fromBytes(bytes));
    }

    /** Whole-object download. A missing key surfaces the SDK's NoSuchKeyException to the caller. */
    public byte[] getObject(String key) {
        ensureReady();
        try (ResponseInputStream<GetObjectResponse> resp =
                     client().getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            return resp.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("S3 getObject read failed for key=" + key, e);
        }
    }

    public void deleteObject(String key) {
        ensureReady();
        client().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /**
     * Compose a key from path parts under the configured prefix, e.g.
     * {@code key("mail", "attachments", uuid + ".bin")} →
     * {@code anvi-mail/mail/attachments/<uuid>.bin}.
     */
    public String key(String first, String... rest) {
        StringBuilder sb = new StringBuilder();
        if (!keyPrefix.isEmpty()) sb.append(keyPrefix);
        appendPart(sb, first);
        if (rest != null) {
            for (String p : rest) appendPart(sb, p);
        }
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == '/') sb.deleteCharAt(len - 1);
        return sb.toString();
    }

    @PreDestroy
    void close() {
        try {
            if (client != null) client.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private S3Client client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    var b = S3Client.builder()
                            .region(Region.of(region))
                            .credentialsProvider(StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                            .httpClient(UrlConnectionHttpClient.builder().build());
                    if (endpointOverride != null) {
                        b.endpointOverride(URI.create(endpointOverride));
                    }
                    if (pathStyle) {
                        b.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
                    }
                    client = b.build();
                }
            }
        }
        return client;
    }

    private void ensureReady() {
        if (!isReady()) {
            throw new IllegalStateException(
                    "S3 storage is not configured — set AWS_S3_BUCKET, AWS_S3_REGION, "
                            + "AWS_S3_ACCESS_KEY_ID, AWS_S3_SECRET_ACCESS_KEY on the deployment.");
        }
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part == null) return;
        String t = part.trim();
        int start = 0, end = t.length();
        while (start < end && t.charAt(start) == '/') start++;
        while (end > start && t.charAt(end - 1) == '/') end--;
        if (start == end) return;
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '/') sb.append('/');
        sb.append(t, start, end);
    }

    private static String normalisePrefix(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.startsWith("/")) t = t.substring(1);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t.isEmpty() ? "" : t + "/";
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
