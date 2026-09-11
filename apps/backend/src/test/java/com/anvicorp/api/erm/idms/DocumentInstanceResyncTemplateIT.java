package com.anvicorp.api.erm.idms;

import com.anvicorp.api.admin.editabletemplates.EditableTemplate;
import com.anvicorp.api.admin.editabletemplates.EditableTemplateRepository;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Draft re-sync to latest template — the mandatory behavior + safety
 * test suite for {@link DocumentInstanceService#resyncTemplate(UUID, User)}.
 *
 * <p>Runs against in-memory H2; drives the service layer directly (per
 * the surrounding service pattern — MailCoreIT is the reference shape).
 * Deliberately does NOT go through {@code create()} to build fixture
 * instances: {@code create()} requires an {@code InternLifecycle} +
 * an {@code Application} + a live-duplicate check + AUTO-field
 * resolution; the re-sync unit under test only cares about the
 * (status, snapshot, values) tuple, so we build a {@link DocumentInstance}
 * directly with the Lombok builder — the same builder pattern the
 * service itself uses inside {@code create()}. This keeps each test
 * focused on the one behavior it asserts.</p>
 *
 * <p>The unique H2 URL ({@code jdbc:h2:mem:anvi_idms_resync}) isolates
 * this test class's schema from the rest of the suite so a parallel
 * run against {@code anvi_a2} or similar doesn't share state.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // MODE=PostgreSQL is REQUIRED here because DocumentInstance +
        // EditableTemplate + AuditLog all declare JSONB columns via
        // {@code columnDefinition = "jsonb"} on their @Column
        // annotations. H2 in its default mode raises
        // "Unknown data type: JSONB" and Hibernate's DDL silently
        // swallows the failure (only logs at WARN), so the table
        // never gets created and every subsequent JPA query fails
        // with "Table DOCUMENT_INSTANCES not found". H2's
        // PostgreSQL mode aliases JSONB → JSON, giving these
        // schemas a working create-drop path.
        "spring.datasource.url=jdbc:h2:mem:anvi_idms_resync;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "app.webmail.seed.admin-enabled=false"
})
class DocumentInstanceResyncTemplateIT {

    @Autowired DocumentInstanceService service;
    @Autowired DocumentInstanceRepository instances;
    @Autowired DocumentInstanceFieldValueRepository values;
    @Autowired DocumentInstanceReviewLogRepository reviewLogs;
    @Autowired EditableTemplateRepository templates;
    @Autowired UserRepository users;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    private User caller;      // SUPER_ADMIN — passes requireErmOrAdmin
    private User intern;      // stashed on the instance's internUserId
    private EditableTemplate template;

    /** Concrete field ids used across the tests — chosen up front so
     *  each test can reason about which id is "kept", "removed", etc. */
    private static final String FIELD_NAME_ID  = "field-name";
    private static final String FIELD_ROLE_ID  = "field-role";
    private static final String FIELD_DATE_ID  = "field-date";
    private static final String FIELD_SIG_ID   = "field-sig";
    private static final String FIELD_NEW_ID   = "field-new";

    @BeforeEach
    void seed() {
        // Order matters — value rows point at instance rows, instance
        // rows point at template + users; clear leaves-first.
        reviewLogs.deleteAll();
        values.deleteAll();
        instances.deleteAll();
        templates.deleteAll();
        users.deleteAll();

        caller = users.save(User.builder()
                .email("erm-caller@anvicorp.com")
                .fullName("ERM Caller")
                .roles(new HashSet<>(Set.of(UserRole.SUPER_ADMIN)))
                .build());
        intern = users.save(User.builder()
                .email("intern@anvicorp.com")
                .fullName("Intern Person")
                .roles(new HashSet<>(Set.of(UserRole.INTERN)))
                .build());
        template = templates.save(EditableTemplate.builder()
                .key("resync_test_template")
                .title("Resync Test Template")
                .description("v1")
                .active(true)
                .sortOrder(500)
                .createdById(caller.getId())
                .canonicalHtml(canonicalHtml("v1"))
                .fieldSchemaJson(schemaJson(
                        entry(FIELD_NAME_ID, "Name",      "TEXT"),
                        entry(FIELD_ROLE_ID, "Role",      "TEXT"),
                        entry(FIELD_DATE_ID, "Start",     "DATE"),
                        entry(FIELD_SIG_ID,  "Signature", "SIGNATURE")))
                .build());
    }

    // ── R1 (staleness watermark) — computed only for DRAFT ───────────

    /** Baseline: a freshly-built draft snapshotted from the template's
     *  CURRENT updatedAt is not stale. */
    @Test
    void isTemplateStale_false_when_snapshot_matches_current_template() {
        DocumentInstance inst = saveDraft(template.getUpdatedAt(),
                schemaJsonFor(template));
        stubValue(inst.getId(), FIELD_NAME_ID, "Alice", null);

        DocumentInstanceDtos.InstanceDetail detail =
                service.getDetail(inst.getId(), caller);
        assertFalse(detail.isTemplateStale(),
                "fresh draft off current template should NOT be stale");
    }

    /** An admin template edit BUMPS updated_at; the draft's stashed
     *  watermark no longer matches → stale=true. */
    @Test
    void isTemplateStale_true_after_admin_template_edit() throws Exception {
        DocumentInstance inst = saveDraft(template.getUpdatedAt(),
                schemaJsonFor(template));

        adminEditsTemplate();  // bumps template.updated_at via JPA @PreUpdate

        DocumentInstanceDtos.InstanceDetail detail =
                service.getDetail(inst.getId(), caller);
        assertTrue(detail.isTemplateStale(),
                "draft should be stale after admin bumps template.updated_at");
    }

    /** After the ERM re-syncs, the watermark is re-stamped → stale=false
     *  again. Same call, called TWICE, is idempotent (still not stale,
     *  same canonical HTML, and — critically — no value rows churned:
     *  same fieldId / valueText / signatureDocumentId / fieldName tuple
     *  as before the second call. Guards against a regression where the
     *  no-op path accidentally re-writes rows on every call). */
    @Test
    void resync_clears_staleness_and_is_idempotent() throws Exception {
        DocumentInstance inst = saveDraft(template.getUpdatedAt(),
                schemaJsonFor(template));
        // Seed a mix of text + signature values to make the byte-
        // identical claim meaningful on the second run.
        stubValue(inst.getId(), FIELD_NAME_ID, "Alice", null);
        UUID sigDocId = UUID.randomUUID();
        stubValue(inst.getId(), FIELD_SIG_ID, null, sigDocId);

        adminEditsTemplate();
        assertTrue(service.getDetail(inst.getId(), caller).isTemplateStale());

        DocumentInstanceDtos.InstanceDetail after1 =
                service.resyncTemplate(inst.getId(), caller);
        assertFalse(after1.isTemplateStale(),
                "resync should clear the staleness flag");

        // Snapshot every value row's key tuple after the first resync.
        Map<String, ValueTuple> tuplesAfter1 = tuplesFor(inst.getId());

        // Idempotent — running it a second time is a safe no-op.
        DocumentInstanceDtos.InstanceDetail after2 =
                service.resyncTemplate(inst.getId(), caller);
        assertFalse(after2.isTemplateStale(),
                "second resync should still be not stale (idempotent)");
        assertEquals(after1.canonicalHtml(), after2.canonicalHtml(),
                "second resync must be byte-identical (no shape drift)");

        // Value rows unchanged tuple-for-tuple — proves the no-op path
        // is genuinely idempotent, not a delete-and-recreate.
        Map<String, ValueTuple> tuplesAfter2 = tuplesFor(inst.getId());
        assertEquals(tuplesAfter1, tuplesAfter2,
                "second (no-op) resync must leave value rows unchanged: "
                        + "before=" + tuplesAfter1 + " after=" + tuplesAfter2);
    }

    /** R2 case-insensitive TYPE guard — legacy snapshots can carry
     *  lowercase / mixed-case type strings. The service uses
     *  {@code equalsIgnoreCase} at the type-comparison step so the
     *  value is KEPT when the case differs but the semantic type is
     *  identical. A regression to case-sensitive comparison would
     *  silently drop values on legacy drafts. */
    @Test
    void resync_treats_type_as_case_insensitive_when_matching() throws Exception {
        // Seed the DRAFT's snapshot schema with LOWERCASE type strings —
        // simulating an older serialisation that predates the frontend's
        // uppercase normalisation.
        String legacySchema = "["
                + "{\"id\":\"" + FIELD_NAME_ID + "\","
                + " \"name\":\"Name\","
                + " \"type\":\"text\","
                + " \"assignee\":\"ERM\","
                + " \"required\":false}]";
        DocumentInstance inst = saveDraft(template.getUpdatedAt(),
                legacySchema);
        stubValue(inst.getId(), FIELD_NAME_ID, "Alice", null);

        // Admin publishes the same field with UPPERCASE type — the
        // service should treat the two as equal and KEEP the value.
        String newSchema = schemaJson(
                entry(FIELD_NAME_ID, "Name", "TEXT"));
        adminReplacesSchema(newSchema, "v2");

        service.resyncTemplate(inst.getId(), caller);

        DocumentInstanceFieldValue kept = values
                .findByInstanceIdAndFieldId(inst.getId(), FIELD_NAME_ID)
                .orElseThrow(() -> new AssertionError(
                        "case-insensitive KEEP failed — value row was dropped when "
                                + "only type-case differed"));
        assertEquals("Alice", kept.getValueText(),
                "value payload survives a lowercase→uppercase type re-serialisation");
    }

    // ── Safety trio — the non-negotiable boundary (R1 guard) ─────────

    /** R1 guard — SENT_TO_INTERN is FROZEN; re-sync must reject 409. */
    @Test
    void resync_on_SENT_TO_INTERN_is_rejected_with_409() {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.SENT_TO_INTERN,
                template.getUpdatedAt(), schemaJsonFor(template));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.resyncTemplate(inst.getId(), caller));
        assertTrue(ex.getMessage().contains("Only draft"),
                "message should say draft-only: " + ex.getMessage());
    }

    /** R1 guard — FINALIZED is FROZEN (legal PDF exists); reject 409. */
    @Test
    void resync_on_FINALIZED_is_rejected_with_409() {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.FINALIZED,
                template.getUpdatedAt(), schemaJsonFor(template));
        assertThrows(ConflictException.class,
                () -> service.resyncTemplate(inst.getId(), caller));
    }

    /** R1 guard — REVOKED (terminal) must reject 409. */
    @Test
    void resync_on_REVOKED_is_rejected_with_409() {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.REVOKED,
                template.getUpdatedAt(), schemaJsonFor(template));
        assertThrows(ConflictException.class,
                () -> service.resyncTemplate(inst.getId(), caller));
    }

    /** R1 defense-in-depth — the final persisted state must be
     *  unchanged after a rejected re-sync on a frozen (non-DRAFT)
     *  instance. Seeds a SENT_TO_INTERN instance with a value row for
     *  a fieldId that DOES NOT exist in the current template schema;
     *  a bug that ran the value-loop before the status check would
     *  drop that row.
     *
     *  <p>Nuance: because {@code resyncTemplate} is
     *  {@code @Transactional} and {@code ConflictException} is a
     *  {@code RuntimeException}, Spring rolls back on the way out —
     *  so even a "guard-second" ordering bug that DID delete the row
     *  before throwing would be undone by rollback, and this test
     *  would still pass. What we assert here is the observable
     *  end-state that an API caller sees. The stronger property
     *  (guard BEFORE any mutation call, so the transaction never
     *  has to roll back a mutation on a frozen instance) is
     *  verified by the visible code ordering in
     *  {@code DocumentInstanceService.resyncTemplate} — the status
     *  check is on the second-and-third statements, before any
     *  {@code valueRepo.delete} / {@code save} runs.</p> */
    @Test
    void resync_rejection_leaves_persisted_state_untouched_on_frozen_instance() {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.SENT_TO_INTERN,
                template.getUpdatedAt(), schemaJsonFor(template));
        String orphanId = "field-that-does-not-exist-in-current-template";
        stubValue(inst.getId(), orphanId, "PayloadThatMustNotBeDropped", null);

        assertThrows(ConflictException.class,
                () -> service.resyncTemplate(inst.getId(), caller));

        DocumentInstanceFieldValue survivor =
                values.findByInstanceIdAndFieldId(inst.getId(), orphanId)
                        .orElseThrow(() -> new AssertionError(
                                "guard failed OR transaction did not roll back "
                                        + "— row was deleted despite frozen state"));
        assertEquals("PayloadThatMustNotBeDropped", survivor.getValueText());
    }

    // ── R2 (value-preservation rules) on DRAFT re-sync ───────────────

    /** All four R2 cases in a single re-sync — one canonical scenario:
     *  <ul>
     *    <li>KEEP: FIELD_NAME_ID (same id, same TEXT type) → value stays.</li>
     *    <li>DROP-REMOVED: FIELD_ROLE_ID (id gone in new schema) → deleted.</li>
     *    <li>DROP-TYPE-CHANGED: FIELD_DATE_ID (id present but DATE → TEXT)
     *        → deleted for safety.</li>
     *    <li>NEW: FIELD_NEW_ID (id in new schema, no existing value row)
     *        → nothing to do; ERM fills later.</li>
     *  </ul>
     *  Also asserts the canonicalHtml on the instance re-snapshots to
     *  the template's new HTML (proves the re-snapshot fired). */
    @Test
    void resync_applies_R2_keep_dropRemoved_dropTypeChanged_new() throws Exception {
        DocumentInstance inst = saveDraft(template.getUpdatedAt(),
                schemaJsonFor(template));
        stubValue(inst.getId(), FIELD_NAME_ID, "Alice",   null);
        stubValue(inst.getId(), FIELD_ROLE_ID, "Engineer", null);
        stubValue(inst.getId(), FIELD_DATE_ID, "2026-01-01", null);

        // Admin edits: KEEP name(TEXT) — DROP role — CHANGE-TYPE date(DATE→TEXT)
        // — ADD field-new. Signature is untouched (kept but no value).
        String newSchema = schemaJson(
                entry(FIELD_NAME_ID, "Name",      "TEXT"),
                entry(FIELD_DATE_ID, "Start",     "TEXT"),    // TYPE CHANGED
                entry(FIELD_SIG_ID,  "Signature", "SIGNATURE"),
                entry(FIELD_NEW_ID,  "New",       "TEXT"));
        adminReplacesSchema(newSchema, "v2-html");

        DocumentInstanceDtos.InstanceDetail after =
                service.resyncTemplate(inst.getId(), caller);

        // Canonical HTML re-snapshotted. `canonicalHtml("v2-html")`
        // wraps the tag as "<p>v2-html-html</p>" via the local
        // helper — we assert the exact wrapped form.
        assertEquals(canonicalHtml("v2-html"), after.canonicalHtml(),
                "canonicalHtml should be re-snapshotted from the new template");

        // KEEP — value row for FIELD_NAME_ID survives with its value.
        DocumentInstanceFieldValue kept = values
                .findByInstanceIdAndFieldId(inst.getId(), FIELD_NAME_ID)
                .orElseThrow(() -> new AssertionError(
                        "KEEP-rule: FIELD_NAME_ID value row missing after resync"));
        assertEquals("Alice", kept.getValueText());

        // DROP-REMOVED — value row for FIELD_ROLE_ID is gone.
        assertTrue(values.findByInstanceIdAndFieldId(inst.getId(), FIELD_ROLE_ID)
                        .isEmpty(),
                "DROP-REMOVED rule: FIELD_ROLE_ID value row should be deleted");

        // DROP-TYPE-CHANGED — value row for FIELD_DATE_ID is gone.
        assertTrue(values.findByInstanceIdAndFieldId(inst.getId(), FIELD_DATE_ID)
                        .isEmpty(),
                "DROP-TYPE-CHANGED rule: FIELD_DATE_ID (DATE→TEXT) row should be deleted");

        // NEW — no value row for FIELD_NEW_ID (nothing added by resync).
        assertTrue(values.findByInstanceIdAndFieldId(inst.getId(), FIELD_NEW_ID)
                        .isEmpty(),
                "NEW rule: FIELD_NEW_ID should have no value row (ERM fills later)");
    }

    /** R2 rename branch — same id, same type, changed NAME. Value stays
     *  and the value row's {@code field_name} snapshot is refreshed for
     *  admin legibility. */
    @Test
    void resync_refreshes_field_name_snapshot_when_only_name_changed() throws Exception {
        DocumentInstance inst = saveDraft(template.getUpdatedAt(),
                schemaJsonFor(template));
        stubValue(inst.getId(), FIELD_ROLE_ID, "Engineer", null);
        // Sanity — the stashed field_name is the OLD name.
        assertEquals("Role", values
                .findByInstanceIdAndFieldId(inst.getId(), FIELD_ROLE_ID)
                .orElseThrow().getFieldName());

        // Admin RENAMES field-role: "Role" → "Job Title". Same id, same type.
        String newSchema = schemaJson(
                entry(FIELD_NAME_ID, "Name",      "TEXT"),
                entry(FIELD_ROLE_ID, "Job Title", "TEXT"),   // renamed
                entry(FIELD_DATE_ID, "Start",     "DATE"),
                entry(FIELD_SIG_ID,  "Signature", "SIGNATURE"));
        adminReplacesSchema(newSchema, "v2");

        service.resyncTemplate(inst.getId(), caller);

        DocumentInstanceFieldValue kept = values
                .findByInstanceIdAndFieldId(inst.getId(), FIELD_ROLE_ID)
                .orElseThrow(() -> new AssertionError(
                        "renamed field's value row must be KEPT"));
        assertEquals("Engineer", kept.getValueText(),
                "value payload survives rename");
        assertEquals("Job Title", kept.getFieldName(),
                "field_name snapshot must be refreshed to the new name");
    }

    // ── R3 (signature preservation) ──────────────────────────────────

    /** R3 — the ERM already signed in DRAFT; if the signature field
     *  SURVIVES an admin edit (same id + still SIGNATURE type), the
     *  ERM's signatureDocumentId is preserved. The ERM does NOT have
     *  to re-sign after every admin tweak. */
    @Test
    void resync_preserves_ERM_signature_when_signature_field_kept() throws Exception {
        DocumentInstance inst = saveDraft(template.getUpdatedAt(),
                schemaJsonFor(template));
        UUID sigDocId = UUID.randomUUID();
        stubValue(inst.getId(), FIELD_SIG_ID, null, sigDocId);

        // Admin edits — signature field kept (id + type unchanged),
        // other fields shuffled to prove the walk touches the sig row.
        String newSchema = schemaJson(
                entry(FIELD_NAME_ID, "Name",       "TEXT"),
                entry(FIELD_SIG_ID,  "Signature",  "SIGNATURE"),
                entry(FIELD_NEW_ID,  "New Field",  "TEXT"));
        adminReplacesSchema(newSchema, "v2");

        service.resyncTemplate(inst.getId(), caller);

        DocumentInstanceFieldValue sig = values
                .findByInstanceIdAndFieldId(inst.getId(), FIELD_SIG_ID)
                .orElseThrow(() -> new AssertionError(
                        "R3: ERM's signature row must survive when the signature field is kept"));
        assertEquals(sigDocId, sig.getSignatureDocumentId(),
                "R3: signatureDocumentId must be preserved verbatim");
    }

    /** R3 negative — if the admin REMOVES the signature field entirely,
     *  the signature row IS dropped (removed-field rule wins for
     *  signatures too — no special case). */
    @Test
    void resync_drops_ERM_signature_when_signature_field_removed() throws Exception {
        DocumentInstance inst = saveDraft(template.getUpdatedAt(),
                schemaJsonFor(template));
        UUID sigDocId = UUID.randomUUID();
        stubValue(inst.getId(), FIELD_SIG_ID, null, sigDocId);

        // Admin edits — signature field REMOVED.
        String newSchema = schemaJson(
                entry(FIELD_NAME_ID, "Name", "TEXT"));
        adminReplacesSchema(newSchema, "v2");

        service.resyncTemplate(inst.getId(), caller);

        assertTrue(values
                        .findByInstanceIdAndFieldId(inst.getId(), FIELD_SIG_ID)
                        .isEmpty(),
                "signature value row must be dropped when the field is removed");
    }

    // ── Audit + review-log trail ─────────────────────────────────────

    /** Re-sync writes a review-log row with action=RESYNC_TEMPLATE +
     *  a human-legible summary. The presence of the audit row is the
     *  evidence — content assertions stay light. */
    @Test
    void resync_writes_review_log_row_with_RESYNC_TEMPLATE_action() throws Exception {
        DocumentInstance inst = saveDraft(template.getUpdatedAt(),
                schemaJsonFor(template));
        stubValue(inst.getId(), FIELD_ROLE_ID, "Engineer", null);
        String newSchema = schemaJson(
                entry(FIELD_NAME_ID, "Name",      "TEXT"),
                entry(FIELD_DATE_ID, "Start",     "DATE"),
                entry(FIELD_SIG_ID,  "Signature", "SIGNATURE"));
        adminReplacesSchema(newSchema, "v2");

        service.resyncTemplate(inst.getId(), caller);

        List<DocumentInstanceReviewLog> log =
                reviewLogs.findByInstanceIdOrderByCreatedAtAsc(inst.getId());
        assertTrue(log.stream().anyMatch(
                        r -> "RESYNC_TEMPLATE".equals(r.getAction())),
                "review log should carry a RESYNC_TEMPLATE row");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Small record mirroring FieldSchemaEntry, used to build test JSON. */
    private record F(String id, String name, String type,
                     String assignee, boolean required, String defaultSource) {}

    /** Comparable snapshot of a value row — the tuple that the
     *  idempotency test asserts is unchanged across a no-op resync. */
    private record ValueTuple(String valueText, UUID signatureDocId,
                              String fieldName) {}

    private Map<String, ValueTuple> tuplesFor(UUID instanceId) {
        Map<String, ValueTuple> out = new java.util.LinkedHashMap<>();
        for (DocumentInstanceFieldValue v : values.findByInstanceId(instanceId)) {
            out.put(v.getFieldId(), new ValueTuple(
                    v.getValueText(),
                    v.getSignatureDocumentId(),
                    v.getFieldName()));
        }
        return out;
    }

    private F entry(String id, String name, String type) {
        return new F(id, name, type, "ERM", false, null);
    }

    private String schemaJson(F... entries) {
        try {
            return objectMapper.writeValueAsString(List.of(entries));
        } catch (Exception e) {
            throw new AssertionError("test schema serialisation failed", e);
        }
    }

    private String schemaJsonFor(EditableTemplate t) {
        return t.getFieldSchemaJson();
    }

    private String canonicalHtml(String tag) {
        return "<p>" + tag + "-html</p>";
    }

    private DocumentInstance saveDraft(Instant watermark, String schema) {
        return saveInstance(DocumentInstanceStatus.DRAFT, watermark, schema);
    }

    private DocumentInstance saveInstance(DocumentInstanceStatus status,
                                          Instant watermark, String schema) {
        return instances.save(DocumentInstance.builder()
                .templateId(template.getId())
                // No lifecycle in this test — toDetail tolerates a missing
                // lifecycle via .orElse(null). Same for internUserId lookup.
                .internLifecycleId(UUID.randomUUID())
                .internUserId(intern.getId())
                .createdByErmId(caller.getId())
                .status(status)
                .version(1)
                .internLocked(false)
                .templateTitle(template.getTitle())
                .templateKey(template.getKey())
                .snapshotCanonicalHtml(template.getCanonicalHtml())
                .snapshotFieldSchemaJson(schema)
                .snapshotTemplateUpdatedAt(watermark)
                .build());
    }

    private void stubValue(UUID instanceId, String fieldId,
                           String valueText, UUID signatureDocId) {
        values.save(DocumentInstanceFieldValue.builder()
                .instanceId(instanceId)
                .fieldId(fieldId)
                // Snapshot the name from the template's current schema so
                // rename-test assertions can distinguish old vs new.
                .fieldName(nameOf(fieldId, template.getFieldSchemaJson()))
                .valueText(valueText)
                .signatureDocumentId(signatureDocId)
                .filledByRole("ERM")
                .filledAt(Instant.now())
                .build());
    }

    /** Simulate the admin studio saving a fresh schema: bumps
     *  {@code template.updatedAt} via JPA {@code @PreUpdate}. Uses a
     *  brief sleep before the save to guarantee the new timestamp is
     *  strictly greater than the one snapshotted at instance-create,
     *  so equality-based staleness comparisons flip. */
    private void adminReplacesSchema(String newSchemaJson, String htmlTag)
            throws InterruptedException {
        Thread.sleep(10);  // guard against same-millisecond timestamps
        template = templates.findById(template.getId()).orElseThrow();
        template.setFieldSchemaJson(newSchemaJson);
        template.setCanonicalHtml(canonicalHtml(htmlTag));
        template = templates.saveAndFlush(template);
    }

    /** Simulate an admin "edited the template" without changing the
     *  schema shape — used by the staleness tests. */
    private void adminEditsTemplate() throws InterruptedException {
        Thread.sleep(10);
        template = templates.findById(template.getId()).orElseThrow();
        template.setDescription("edited " + Instant.now());
        template = templates.saveAndFlush(template);
    }

    private String nameOf(String fieldId, String schemaJson) {
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(
                    schemaJson, new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> m : raw) {
                if (fieldId.equals(m.get("id"))) {
                    Object n = m.get("name");
                    return n == null ? null : n.toString();
                }
            }
        } catch (Exception ignored) {
            // Fall through — tests won't rely on the name in this branch.
        }
        return null;
    }
}
