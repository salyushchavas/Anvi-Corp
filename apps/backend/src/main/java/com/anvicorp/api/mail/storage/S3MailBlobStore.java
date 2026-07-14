package com.anvicorp.api.mail.storage;

import com.anvicorp.api.crypto.AesGcmCipher;
import com.anvicorp.api.mail.exception.MailApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Mail attachment blob store: encrypts bytes with the shared {@link AesGcmCipher}
 * (AES-256-GCM, the same key that encrypts message bodies) BEFORE upload and
 * decrypts only on fetch, so the S3 object is NEVER plaintext.
 *
 * <p>The cipher exposes only String I/O, so bytes are base64-wrapped before
 * encryption and unwrapped after decryption (we don't modify the shared cipher).
 * Objects live under the configured Anvi prefix ({@code anvi-mail/mail/attachments/});
 * the storage key is an opaque {@code "s3:<key>"} with a server-generated UUID name —
 * the real filename lives only in the DB row.</p>
 *
 * <p>Encryption is whole-file in memory: a 25 MB upload peaks at a few multiples of
 * that across the plaintext, base64 string, and ciphertext copies. That is acceptable
 * at the 25 MB cap ({@code app.webmail.attachment-max-bytes}); raising the cap much
 * higher would need a streaming byte cipher (new crypto, not an edit to the shared
 * {@link AesGcmCipher}).</p>
 */
@Component
@RequiredArgsConstructor
public class S3MailBlobStore {

    private static final String PREFIX = "s3:";

    private final S3StorageService s3;
    private final AesGcmCipher cipher;

    public boolean isReady() {
        return s3.isReady();
    }

    /** Encrypt + upload; returns the opaque "s3:&lt;key&gt;" storage key. */
    public String store(byte[] plain) {
        String key = s3.key("mail", "attachments", UUID.randomUUID() + ".bin");
        s3.putObject(key, encrypt(plain), "application/octet-stream");
        return PREFIX + key;
    }

    /** Fetch + decrypt. Missing object → 404 (not 500). */
    public byte[] fetch(String storageKey) {
        try {
            return decrypt(s3.getObject(stripPrefix(storageKey)));
        } catch (NoSuchKeyException e) {
            throw new MailApiException(HttpStatus.NOT_FOUND, "Attachment object not found",
                    "MAIL_ATTACHMENT_MISSING");
        }
    }

    /** Best-effort delete; a missing object is fine. */
    public void delete(String storageKey) {
        try {
            s3.deleteObject(stripPrefix(storageKey));
        } catch (NoSuchKeyException ignored) {
            // already gone
        }
    }

    private byte[] encrypt(byte[] plain) {
        String envelope = cipher.encrypt(Base64.getEncoder().encodeToString(plain));
        return envelope.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] decrypt(byte[] stored) {
        String envelope = new String(stored, StandardCharsets.UTF_8);
        return Base64.getDecoder().decode(cipher.decrypt(envelope));
    }

    private static String stripPrefix(String storageKey) {
        return storageKey.startsWith(PREFIX) ? storageKey.substring(PREFIX.length()) : storageKey;
    }
}
