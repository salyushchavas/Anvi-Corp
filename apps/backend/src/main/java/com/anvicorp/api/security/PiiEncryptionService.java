package com.anvicorp.api.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Phase 4 PII encryption facade. Thin delegate over {@link AesGcmCipher} so
 * onboarding code calls a doc-spec-named service while the actual crypto
 * (AES-256-GCM, 12-byte IV, 16-byte tag) lives in a single audited class.
 *
 * <p>Same key as the I-9 phase — Phase 0 hardened {@code AesGcmCipher} to
 * resolve {@code pii.encryption.key} / {@code app.i9.encryption-key} /
 * {@code PII_ENCRYPTION_KEY} / {@code I9_ENCRYPTION_KEY} in that order, so
 * existing deployments need no env-var changes.</p>
 *
 * <p>{@link #encrypt} / {@link #decrypt} are null-safe. {@link #decrypt}
 * throws on tamper / wrong key — callers must treat that as a 500 with the
 * specific error logged, NOT exposed to the wire.</p>
 */
@Service
@RequiredArgsConstructor
public class PiiEncryptionService {

    private final AesGcmCipher cipher;

    /** UTF-8 plaintext → base64(IV || ciphertext+tag). Null → null. */
    public String encrypt(String plaintext) {
        return cipher.encrypt(plaintext);
    }

    /** Inverse of {@link #encrypt}. Throws on tampered ciphertext. */
    public String decrypt(String encoded) {
        return cipher.decrypt(encoded);
    }
}
