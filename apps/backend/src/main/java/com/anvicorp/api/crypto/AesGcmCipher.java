package com.anvicorp.api.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * GAP_REPORT C7 — application-level AES-256-GCM cipher for I-9 PII at rest.
 *
 * Format on disk: base64( IV (12 bytes) || ciphertext+tag (GCM output) ).
 * GCM is authenticated — decryption fails loudly on tampered ciphertext,
 * wrong key, or wrong IV.
 *
 * Key source: env var {@code I9_ENCRYPTION_KEY}, base64-encoded 32 raw bytes
 * (256-bit AES key). The bean fails fast at startup if the key is missing,
 * not valid base64, or the wrong length — better a clean refusal than
 * silently writing plaintext.
 *
 * KEY LOSS = DATA LOSS: ciphertext is unrecoverable without the exact same
 * 32-byte key. Key rotation / envelope encryption (DEK + KEK) is a future
 * pass; for this commit there is exactly one key, and losing it loses every
 * I-9 PII field. Operations doc the env var with the rest of the prod secrets.
 *
 * Generate a new key:
 * <pre>
 *   openssl rand -base64 32
 * </pre>
 */
@Component
@Slf4j
public class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;          // GCM standard
    private static final int TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32;         // AES-256

    private final SecretKey key;
    private final SecureRandom rng = new SecureRandom();

    public AesGcmCipher(
            // Resolution chain (first non-blank wins):
            //   1. pii.encryption.key  (Phase 4 doc-spec name)
            //   2. app.i9.encryption-key  (legacy property)
            //   3. PII_ENCRYPTION_KEY env var
            //   4. I9_ENCRYPTION_KEY env var
            // Same 32-byte AES key, just multiple acceptable names for ops.
            @Value("${pii.encryption.key:${app.i9.encryption-key:${PII_ENCRYPTION_KEY:${I9_ENCRYPTION_KEY:}}}}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "I9_ENCRYPTION_KEY is not set. Generate one with " +
                    "`openssl rand -base64 32` and set the I9_ENCRYPTION_KEY env var " +
                    "(or app.i9.encryption-key property) before starting the application.");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "I9_ENCRYPTION_KEY is not valid base64: " + e.getMessage(), e);
        }
        if (raw.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "I9_ENCRYPTION_KEY must decode to exactly " + KEY_LENGTH_BYTES
                            + " bytes (AES-256). Decoded length: " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
        log.info("AES-256-GCM I-9 cipher initialized (key length OK).");
    }

    /** Encrypts UTF-8 of {@code plaintext}. Null in → null out. Fresh IV per call. */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            rng.nextBytes(iv);
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // IV || ciphertext+tag — single base64 envelope on disk.
            ByteBuffer bb = ByteBuffer.allocate(iv.length + ct.length);
            bb.put(iv);
            bb.put(ct);
            return Base64.getEncoder().encodeToString(bb.array());
        } catch (Exception e) {
            // Crypto failure on write is unrecoverable — surface it loudly so
            // the transaction rolls back instead of persisting silent garbage.
            throw new IllegalStateException("I-9 encryption failed", e);
        }
    }

    /**
     * Decrypts an envelope produced by {@link #encrypt}. Null in → null out.
     * Throws {@link IllegalStateException} on tampered ciphertext / wrong key /
     * non-envelope payload (e.g. legacy plaintext rows pre-migration).
     */
    public String decrypt(String encoded) {
        if (encoded == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length <= IV_LENGTH_BYTES) {
                throw new IllegalStateException(
                        "Encrypted I-9 value too short to contain IV + ciphertext (got "
                                + all.length + " bytes)");
            }
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LENGTH_BYTES);
            byte[] ct = Arrays.copyOfRange(all, IV_LENGTH_BYTES, all.length);
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] pt = c.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (IllegalStateException ise) {
            throw ise;
        } catch (Exception e) {
            // Includes AEADBadTagException (tamper / wrong key) and
            // IllegalArgumentException (non-base64 input — e.g. a legacy
            // plaintext SSN that pre-dates encryption rollout).
            throw new IllegalStateException(
                    "I-9 decryption failed (possible legacy plaintext row or wrong key): "
                            + e.getMessage(), e);
        }
    }
}
