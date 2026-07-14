package com.anvicorp.api.bootstrap;

import com.anvicorp.api.security.AesGcmCipher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * GAP_REPORT C7 — one-time migrator: walks every {@code i9_forms} row, finds
 * columns that still hold plaintext (not the AES-GCM envelope), and rewrites
 * them with encrypted ciphertext.
 *
 * <h2>Gating</h2>
 * Activated ONLY when the {@code i9-migrate} Spring profile is on:
 * <pre>
 *   SPRING_PROFILES_ACTIVE=i9-migrate
 * </pre>
 * Idempotent: rerunning after a successful migration is a no-op (every value
 * decrypts cleanly → looks already-encrypted → skipped). Crucially, this also
 * means it can be safely composed with {@code prod} if you need to run a
 * one-off encrypted-migration boot before re-removing the profile.
 *
 * <h2>How it bypasses the converter</h2>
 * Uses native SQL ({@code EntityManager.createNativeQuery}) for both read and
 * write — native queries do NOT trigger JPA AttributeConverters. The handful
 * of converter-attached columns we widen below have to be touched via native
 * SQL or the converter would decrypt-then-re-encrypt, exploding on the
 * legacy-plaintext rows we're trying to migrate.
 *
 * <h2>Use case</h2>
 * Dev / staging databases that have realistic test rows in {@code i9_forms}
 * and you don't want to lose them. Production has demo-only I-9 data and the
 * feature is flag-off, so the recommended path there is TRUNCATE + start
 * fresh (see COMPLIANCE_GATES_CHANGES.md).
 *
 * <h2>Failure modes</h2>
 * If a value can't be parsed and can't be decrypted, it's left alone and
 * logged at WARN — the runner won't rollback the whole transaction because
 * one row is malformed. The migrator runs per-column in its own transaction
 * boundary so partial progress is preserved.
 */
@Component
@Profile("i9-migrate")
@Order(Integer.MAX_VALUE) // run AFTER SchemaFixupRunner widens the columns
@RequiredArgsConstructor
@Slf4j
public class I9PlaintextEncryptionMigrator implements CommandLineRunner {

    /**
     * Columns to migrate. Order matches {@code I9Form.@Convert} annotations.
     * Keep in sync if new encrypted fields are added.
     */
    private static final List<String> STRING_COLUMNS = List.of(
            "ssn",
            "alien_registration_number",
            "foreign_passport_number",
            "list_a_document_number",
            "list_b_document_number",
            "list_c_document_number"
    );

    private static final String DATE_COLUMN = "date_of_birth";

    private final AesGcmCipher cipher;

    @PersistenceContext
    private EntityManager em;

    @Override
    public void run(String... args) {
        log.info("== I9 plaintext->ciphertext migrator START ==");
        for (String col : STRING_COLUMNS) {
            try {
                int n = migrateColumn(col);
                log.info("Migrated {} row(s) for i9_forms.{}", n, col);
            } catch (Exception e) {
                log.error("Migration of i9_forms.{} failed (continuing): {}",
                        col, e.getMessage(), e);
            }
        }
        try {
            int n = migrateColumn(DATE_COLUMN);
            log.info("Migrated {} row(s) for i9_forms.{}", n, DATE_COLUMN);
        } catch (Exception e) {
            log.error("Migration of i9_forms.{} failed (continuing): {}",
                    DATE_COLUMN, e.getMessage(), e);
        }
        log.info("== I9 plaintext->ciphertext migrator END ==");
    }

    /**
     * Per-column migrator. SELECTs plaintext via native SQL, encrypts when the
     * value doesn't already decrypt cleanly, UPDATEs via native SQL. Returns
     * the number of rows that were actually rewritten (skipped + malformed
     * counted in the log, not the return value).
     */
    @Transactional
    public int migrateColumn(String column) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT id, " + column + " FROM i9_forms WHERE " + column + " IS NOT NULL")
                .getResultList();

        int rewritten = 0;
        int skipped = 0;
        int malformed = 0;
        for (Object[] r : rows) {
            UUID id = (UUID) r[0];
            String value = (String) r[1];
            if (value == null || value.isBlank()) continue;
            if (looksAlreadyEncrypted(value)) {
                skipped++;
                continue;
            }
            // Plaintext detected — encrypt and UPDATE. For date_of_birth the
            // value coming back from native SQL is the Postgres-coerced text
            // ("yyyy-MM-dd"), which is exactly what EncryptedLocalDateConverter
            // expects to wrap. No parsing/formatting needed here.
            try {
                String encrypted = cipher.encrypt(value);
                int affected = em.createNativeQuery(
                                "UPDATE i9_forms SET " + column + " = :v WHERE id = :id")
                        .setParameter("v", encrypted)
                        .setParameter("id", id)
                        .executeUpdate();
                rewritten += affected;
            } catch (Exception e) {
                malformed++;
                log.warn("Could not encrypt i9_forms.{} for id={}: {}",
                        column, id, e.getMessage());
            }
        }
        log.info("i9_forms.{} — rewritten={}, skipped(already-encrypted)={}, malformed={}",
                column, rewritten, skipped, malformed);
        return rewritten;
    }

    /**
     * Heuristic: a value looks already-encrypted if it decodes as base64,
     * is at least IV+tag bytes long, AND the cipher decrypts it cleanly under
     * the current key. Cheap try-decrypt — wrong key or tampered ciphertext
     * throws and we treat the value as plaintext.
     */
    private boolean looksAlreadyEncrypted(String value) {
        try {
            cipher.decrypt(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
