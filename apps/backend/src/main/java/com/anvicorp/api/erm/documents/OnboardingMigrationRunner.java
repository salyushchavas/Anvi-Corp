package com.anvicorp.api.erm.documents;

import com.anvicorp.api.entity.DocumentPacket;
import com.anvicorp.api.entity.DocumentTask;
import com.anvicorp.api.entity.DocumentTaskReviewLog;
import com.anvicorp.api.repository.DocumentPacketRepository;
import com.anvicorp.api.repository.DocumentTaskRepository;
import com.anvicorp.api.repository.DocumentTaskReviewLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ERM Phase 8 — one-shot migration that converts the legacy
 * {@code OnboardingPacket} / {@code OnboardingItem} pair into the new
 * {@code DocumentPacket} / {@code DocumentTask} pair.
 *
 * <p>ERM Phase 8.2 — rewritten to emit {@code documentKey} directly
 * (the {@link SkyzenDocument} enum value) rather than a now-defunct
 * {@code template_id}.</p>
 *
 * <p>Idempotent: gated by a row in {@code migration_log} with
 * {@code migration_key = ONBOARDING_TO_DOCUMENT_PACKETS_V1}. Runs at
 * boot AFTER {@code DocumentTemplateSeeder} (which is now itself gone
 * — order kept at 25 just to follow any other early seeders). Reads
 * via raw JDBC so it stays independent of the now-deleted Onboarding*
 * JPA entities.</p>
 *
 * <p>Category mapping (legacy item category → SkyzenDocument enum):</p>
 * <ul>
 *   <li>{@code W4} → {@link SkyzenDocument#W4_2026}</li>
 *   <li>{@code I9} → {@link SkyzenDocument#I9_FORM_2026}</li>
 *   <li>{@code ACH}, {@code EMERGENCY_CONTACT} → {@link SkyzenDocument#EMPLOYEE_DATA_SHEET}</li>
 *   <li>{@code HANDBOOK_ACK} → {@link SkyzenDocument#EMPLOYEE_HANDBOOK}</li>
 *   <li>{@code I983} → {@link SkyzenDocument#I983_2029}</li>
 * </ul>
 */
@Component
@Order(25)
@RequiredArgsConstructor
@Slf4j
public class OnboardingMigrationRunner implements CommandLineRunner {

    private static final String MIGRATION_KEY = "ONBOARDING_TO_DOCUMENT_PACKETS_V1";

    private static final Map<String, SkyzenDocument> CATEGORY_TO_DOC = Map.of(
            "W4", SkyzenDocument.W4_2026,
            "I9", SkyzenDocument.I9_FORM_2026,
            "ACH", SkyzenDocument.EMPLOYEE_DATA_SHEET,
            "EMERGENCY_CONTACT", SkyzenDocument.EMPLOYEE_DATA_SHEET,
            "HANDBOOK_ACK", SkyzenDocument.EMPLOYEE_HANDBOOK,
            "I983", SkyzenDocument.I983_2029);

    private final JdbcTemplate jdbc;
    private final DocumentPacketRepository packetRepository;
    private final DocumentTaskRepository taskRepository;
    private final DocumentTaskReviewLogRepository reviewLogRepository;

    @Override
    @Transactional
    public void run(String... args) {
        if (alreadyMigrated()) {
            log.info("[OnboardingMigration] {} already executed; skipping",
                    MIGRATION_KEY);
            return;
        }
        if (!legacyTablesExist()) {
            // Fresh deploy — no legacy data. Mark migration done so the
            // SchemaFixupRunner's conditional DROP can proceed next boot
            // (idempotent — DROPs are no-ops on non-existent tables).
            postSuccess(0, "Fresh deploy — no legacy tables to migrate");
            log.info("[OnboardingMigration] Fresh deploy — no legacy onboarding tables; "
                    + "marked {} complete", MIGRATION_KEY);
            return;
        }

        List<Map<String, Object>> packets;
        try {
            packets = jdbc.queryForList(
                    "SELECT id, user_id, intern_lifecycle_id, status, "
                            + "       assigned_by_id, assigned_at, accepted_at "
                            + "  FROM onboarding_packets");
        } catch (DataAccessException e) {
            log.warn("[OnboardingMigration] legacy packet query failed; "
                    + "assuming no rows: {}", e.getMessage());
            postSuccess(0, "No legacy rows readable; treated as fresh deploy");
            return;
        }
        if (packets.isEmpty()) {
            postSuccess(0, "No legacy packets to migrate");
            return;
        }

        int packetsMigrated = 0;
        int tasksMigrated = 0;
        int tasksWithMissingMapping = 0;

        for (Map<String, Object> p : packets) {
            UUID legacyPacketId = uuid(p.get("id"));
            UUID lifecycleId = uuid(p.get("intern_lifecycle_id"));
            String legacyStatus = (String) p.get("status");
            UUID assignedById = uuid(p.get("assigned_by_id"));
            Instant assignedAt = instantOf((java.sql.Timestamp) p.get("assigned_at"));
            Instant acceptedAt = instantOf((java.sql.Timestamp) p.get("accepted_at"));
            if (lifecycleId == null) continue;
            // Skip if a new-model packet already exists for this lifecycle.
            try {
                Integer existing = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM document_packets "
                                + " WHERE intern_lifecycle_id = ?",
                        Integer.class, lifecycleId);
                if (existing != null && existing > 0) {
                    log.debug("[OnboardingMigration] DocumentPacket already exists "
                            + "for lifecycle {} — skipping legacy {}", lifecycleId, legacyPacketId);
                    continue;
                }
            } catch (Exception e) {
                // continue with creation; UNIQUE partial index will catch dupes
            }

            String newStatus = mapPacketStatus(legacyStatus);
            DocumentPacket newPacket = DocumentPacket.builder()
                    .internLifecycleId(lifecycleId)
                    .assignedById(assignedById != null
                            ? assignedById
                            : UUID.fromString("00000000-0000-0000-0000-000000000001"))
                    .status(newStatus)
                    .assignedAt(assignedAt != null ? assignedAt : Instant.now())
                    .completedAt("COMPLETED".equals(newStatus) ? acceptedAt : null)
                    .customInstructions("[Migrated from legacy onboarding_packets row "
                            + legacyPacketId + "]")
                    .build();
            try {
                newPacket = packetRepository.save(newPacket);
                packetsMigrated++;
            } catch (Exception e) {
                log.warn("[OnboardingMigration] failed to save DocumentPacket "
                        + "for legacy {} (non-fatal): {}", legacyPacketId, e.getMessage());
                continue;
            }

            // Migrate child items.
            List<Map<String, Object>> items;
            try {
                items = jdbc.queryForList(
                        "SELECT id, category, status, form_data_json, document_id, "
                                + "       submitted_at, reviewed_at, reviewed_by_id, "
                                + "       erm_comments, internal_notes, last_review_reason_code "
                                + "  FROM onboarding_items WHERE packet_id = ?",
                        legacyPacketId);
            } catch (Exception e) {
                log.warn("[OnboardingMigration] item query failed for packet {}: {}",
                        legacyPacketId, e.getMessage());
                continue;
            }
            for (Map<String, Object> it : items) {
                String category = (String) it.get("category");
                SkyzenDocument doc = CATEGORY_TO_DOC.get(category);
                if (doc == null) {
                    tasksWithMissingMapping++;
                    log.warn("[OnboardingMigration] no SkyzenDocument match for category {} "
                            + "(legacy item {}); skipping row", category, it.get("id"));
                    continue;
                }
                DocumentTask t = DocumentTask.builder()
                        .packetId(newPacket.getId())
                        .documentKey(doc.name())
                        .status(mapTaskStatus((String) it.get("status")))
                        .uploadedFileId(uuid(it.get("document_id")))
                        .submittedAt(instantOf((java.sql.Timestamp) it.get("submitted_at")))
                        .reviewedAt(instantOf((java.sql.Timestamp) it.get("reviewed_at")))
                        .reviewedById(uuid(it.get("reviewed_by_id")))
                        .reviewReasonCode((String) it.get("last_review_reason_code"))
                        .reviewComments((String) it.get("erm_comments"))
                        .internalNote((String) it.get("internal_notes"))
                        .version(1)
                        .taskInstructions("[Migrated from legacy structured form; "
                                + "original form data preserved in review log under OnboardingItem id="
                                + it.get("id") + "]")
                        .build();
                try {
                    DocumentTask saved = taskRepository.save(t);
                    tasksMigrated++;
                    String legacyJson = (String) it.get("form_data_json");
                    reviewLogRepository.save(DocumentTaskReviewLog.builder()
                            .taskId(saved.getId())
                            .actorUserId(null)
                            .eventType("MIGRATED_FROM_LEGACY_FORM")
                            .newStatus(saved.getStatus())
                            .comments("Migrated from legacy OnboardingItem "
                                    + it.get("id") + " (category=" + category + ")")
                            .payloadJson(legacyJson != null && legacyJson.length() <= 8000
                                    ? legacyJson : null)
                            .build());
                } catch (Exception e) {
                    log.warn("[OnboardingMigration] failed to save DocumentTask "
                            + "for legacy item {}: {}", it.get("id"), e.getMessage());
                }
            }
        }

        postSuccess(packetsMigrated, "packets=" + packetsMigrated
                + ", tasks=" + tasksMigrated
                + ", tasksWithMissingMapping=" + tasksWithMissingMapping);
        log.info("[OnboardingMigration] Migrated {} packet(s) / {} task(s) "
                + "({} with missing category mapping)",
                packetsMigrated, tasksMigrated, tasksWithMissingMapping);
    }

    private boolean alreadyMigrated() {
        try {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM migration_log WHERE migration_key = ?",
                    Integer.class, MIGRATION_KEY);
            return cnt != null && cnt > 0;
        } catch (Exception e) {
            // migration_log doesn't exist yet — first boot. Not migrated.
            return false;
        }
    }

    private boolean legacyTablesExist() {
        try {
            jdbc.queryForObject(
                    "SELECT 1 FROM onboarding_packets LIMIT 1", Integer.class);
            return true;
        } catch (DataAccessException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void postSuccess(int rowsMigrated, String notes) {
        try {
            jdbc.update(
                    "INSERT INTO migration_log "
                            + "  (id, migration_key, executed_at, rows_migrated, notes) "
                            + "VALUES (?, ?, NOW(), ?, ?) "
                            + "ON CONFLICT (migration_key) DO NOTHING",
                    UUID.randomUUID(), MIGRATION_KEY, rowsMigrated, notes);
        } catch (Exception e) {
            log.warn("[OnboardingMigration] failed to write migration_log row: {}",
                    e.getMessage());
        }
    }

    private static String mapPacketStatus(String legacy) {
        if (legacy == null) return "ASSIGNED";
        return switch (legacy) {
            case "ACCEPTED" -> "COMPLETED";
            case "REJECTED" -> "IN_PROGRESS";   // surface for ERM rework
            case "DRAFT", "ASSIGNED" -> "ASSIGNED";
            default -> "IN_PROGRESS";
        };
    }

    private static String mapTaskStatus(String legacy) {
        if (legacy == null) return "PENDING";
        return switch (legacy) {
            case "ACCEPTED" -> "ACCEPTED";
            case "SUBMITTED" -> "SUBMITTED";
            case "REJECTED" -> "REJECTED";
            case "RESEND_REQUESTED" -> "RESEND_REQUESTED";
            default -> "PENDING";
        };
    }

    private static UUID uuid(Object o) {
        if (o == null) return null;
        try { return UUID.fromString(String.valueOf(o)); }
        catch (Exception e) { return null; }
    }

    private static Instant instantOf(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
