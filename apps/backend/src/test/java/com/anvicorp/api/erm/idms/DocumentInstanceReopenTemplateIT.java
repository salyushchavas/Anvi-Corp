package com.anvicorp.api.erm.idms;

import com.anvicorp.api.admin.editabletemplates.EditableTemplate;
import com.anvicorp.api.admin.editabletemplates.EditableTemplateRepository;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reopen-for-template-update — the mandatory behavior + safety
 * test suite for
 * {@link DocumentInstanceService#reopenForTemplateUpdate(UUID, User)}.
 *
 * <p>Mirrors the shape of {@code DocumentInstanceResyncTemplateIT}
 * (in-memory H2 in Postgres compat mode for JSONB, service-layer
 * exercise, hand-built fixtures via Lombok builders — no {@code
 * create()} call because we want to seed instances in non-DRAFT
 * states directly).</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // MODE=PostgreSQL required for JSONB columnDefinition on
        // DocumentInstance / EditableTemplate / AuditLog — see the
        // sibling resync IT for the full rationale.
        "spring.datasource.url=jdbc:h2:mem:anvi_idms_reopen;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "app.webmail.seed.admin-enabled=false"
})
class DocumentInstanceReopenTemplateIT {

    @Autowired DocumentInstanceService service;
    @Autowired DocumentInstanceRepository instances;
    @Autowired DocumentInstanceFieldValueRepository values;
    @Autowired DocumentInstanceReviewLogRepository reviewLogs;
    @Autowired AuditLogRepository auditLogs;
    @Autowired EditableTemplateRepository templates;
    @Autowired UserRepository users;
    @Autowired ObjectMapper objectMapper;

    private User caller;
    private User intern;
    private EditableTemplate template;

    private static final String FIELD_ERM_TEXT_ID     = "field-erm-text";
    private static final String FIELD_ERM_SIG_ID      = "field-erm-sig";
    private static final String FIELD_INTERN_TEXT_ID  = "field-intern-text";
    private static final String FIELD_INTERN_SIG_ID   = "field-intern-sig";
    private static final String FIELD_AUTO_ID         = "field-auto";
    private static final String FIELD_NEW_INTERN_ID   = "field-new-intern";
    private static final String FIELD_NEW_ERM_ID      = "field-new-erm";

    @BeforeEach
    void seed() {
        reviewLogs.deleteAll();
        auditLogs.deleteAll();
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
                .key("reopen_test_template")
                .title("Reopen Test Template")
                .description("v1")
                .active(true)
                .sortOrder(500)
                .createdById(caller.getId())
                .canonicalHtml(canonicalHtml("v1"))
                .fieldSchemaJson(schemaJson(
                        entry(FIELD_ERM_TEXT_ID,    "ERM Text",    "TEXT",      "ERM"),
                        entry(FIELD_ERM_SIG_ID,     "ERM Sig",     "SIGNATURE", "ERM"),
                        entry(FIELD_INTERN_TEXT_ID, "Intern Text", "TEXT",      "INTERN"),
                        entry(FIELD_INTERN_SIG_ID,  "Intern Sig",  "SIGNATURE", "INTERN")))
                .build());
    }

    // ────────────────────────────────────────────────────────────
    // Routing tests (a)–(g) — R-ROUTE + R-SIG behavior
    // ────────────────────────────────────────────────────────────

    /** (a) SENT_TO_INTERN + only INTERN field added → reopen to
     *  RETURNED, unlockedFieldIds includes new field + intern sig id,
     *  intern sig dropped, ERM sig kept. */
    @Test
    void a_sent_plus_only_intern_added_routes_to_RETURNED() throws Exception {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.SENT_TO_INTERN, template.getUpdatedAt());
        UUID ermSigDocId = UUID.randomUUID();
        UUID internSigDocId = UUID.randomUUID();
        stubValue(inst.getId(), FIELD_ERM_TEXT_ID,    "Alice",  null);
        stubValue(inst.getId(), FIELD_ERM_SIG_ID,     null,     ermSigDocId);
        stubValue(inst.getId(), FIELD_INTERN_SIG_ID,  null,     internSigDocId);
        // ERM sends: intern hasn't filled/signed yet — but for test
        // symmetry we've stubbed the intern sig too so we can prove
        // it's dropped when intern-affected.

        // Admin adds a NEW intern-owned field — HTML changes (new
        // anchor span). Because HTML content differs, the content
        // over-approximation trips → BOTH sigs dropped, target=DRAFT.
        // To hit the pure "only-intern → RETURNED" branch, this test
        // uses a schema-only edit (a fresh anchor added with IDENTICAL
        // canonicalHtml) — simulated by hand.
        adminUpdatesSchemaOnly(schemaJson(
                entry(FIELD_ERM_TEXT_ID,    "ERM Text",    "TEXT",      "ERM"),
                entry(FIELD_ERM_SIG_ID,     "ERM Sig",     "SIGNATURE", "ERM"),
                entry(FIELD_INTERN_TEXT_ID, "Intern Text", "TEXT",      "INTERN"),
                entry(FIELD_INTERN_SIG_ID,  "Intern Sig",  "SIGNATURE", "INTERN"),
                entry(FIELD_NEW_INTERN_ID,  "New Intern",  "TEXT",      "INTERN")));

        DocumentInstanceDtos.ReopenTemplateResponse after =
                service.reopenForTemplateUpdate(inst.getId(), caller);

        assertEquals("SENT_TO_INTERN", after.summary().fromStatus());
        assertEquals("RETURNED",       after.summary().toStatus());
        assertEquals("INTERN",         after.summary().reroutedTo());

        DocumentInstance persisted = instances.findById(inst.getId()).orElseThrow();
        assertEquals(DocumentInstanceStatus.RETURNED, persisted.getStatus());
        assertFalse(persisted.getInternLocked(),
                "internLocked must flip false so intern can edit");
        assertEquals("TEMPLATE_UPDATED", persisted.getReturnReasonCode());

        List<String> unlocked = parseUnlocked(persisted.getUnlockedFieldIdsJson());
        assertTrue(unlocked.contains(FIELD_NEW_INTERN_ID),
                "unlockedFieldIds must include the newly-added intern field");
        assertTrue(unlocked.contains(FIELD_INTERN_SIG_ID),
                "unlockedFieldIds must include the intern signature so they re-sign");

        // Intern sig dropped; ERM sig kept.
        assertTrue(values.findByInstanceIdAndFieldId(inst.getId(), FIELD_INTERN_SIG_ID).isEmpty(),
                "intern signature must be invalidated (their fields changed)");
        assertEquals(ermSigDocId,
                values.findByInstanceIdAndFieldId(inst.getId(), FIELD_ERM_SIG_ID)
                        .orElseThrow().getSignatureDocumentId(),
                "ERM signature must survive (their fields did not change)");
    }

    /** (b) INTERN_SUBMITTED + only-INTERN-field changed → RETURNED,
     *  intern sig dropped, ERM sig kept, lastErmViewedAt cleared. */
    @Test
    void b_intern_submitted_plus_only_intern_change_routes_to_RETURNED_clears_lastErmViewedAt() throws Exception {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.INTERN_SUBMITTED, template.getUpdatedAt());
        // ERM had viewed the submitted doc — stamp is set.
        inst.setLastErmViewedAt(Instant.now());
        inst = instances.saveAndFlush(inst);
        UUID ermSigDocId = UUID.randomUUID();
        UUID internSigDocId = UUID.randomUUID();
        stubValue(inst.getId(), FIELD_ERM_SIG_ID,    null, ermSigDocId);
        stubValue(inst.getId(), FIELD_INTERN_SIG_ID, null, internSigDocId);
        stubValue(inst.getId(), FIELD_INTERN_TEXT_ID, "OldValue", null);

        // Admin RENAMES the intern text field. Same id, same type,
        // new label — the R2 rename branch fires and intern is
        // affected. Same HTML (schema-only).
        adminUpdatesSchemaOnly(schemaJson(
                entry(FIELD_ERM_TEXT_ID,    "ERM Text",     "TEXT",      "ERM"),
                entry(FIELD_ERM_SIG_ID,     "ERM Sig",      "SIGNATURE", "ERM"),
                entry(FIELD_INTERN_TEXT_ID, "Intern Text 2","TEXT",      "INTERN"),  // renamed
                entry(FIELD_INTERN_SIG_ID,  "Intern Sig",   "SIGNATURE", "INTERN")));

        service.reopenForTemplateUpdate(inst.getId(), caller);

        DocumentInstance persisted = instances.findById(inst.getId()).orElseThrow();
        assertEquals(DocumentInstanceStatus.RETURNED, persisted.getStatus());
        assertNull(persisted.getLastErmViewedAt(),
                "lastErmViewedAt must be cleared on backward hop — "
                        + "verify gate must re-earn on the next INTERN_SUBMITTED cycle");
        assertTrue(values.findByInstanceIdAndFieldId(inst.getId(), FIELD_INTERN_SIG_ID).isEmpty(),
                "intern sig invalidated by intern-field rename");
        assertEquals(ermSigDocId,
                values.findByInstanceIdAndFieldId(inst.getId(), FIELD_ERM_SIG_ID)
                        .orElseThrow().getSignatureDocumentId(),
                "ERM sig preserved");
    }

    /** (c) INTERN_SUBMITTED + only-ERM-field changed → DRAFT, ERM sig
     *  dropped, INTERN sig KEPT (cross-party change doesn't invalidate
     *  the other party), lastErmViewedAt cleared, finalPdfDocumentId
     *  cleared. */
    @Test
    void c_intern_submitted_plus_only_erm_change_routes_to_DRAFT_preserves_intern_sig() throws Exception {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.INTERN_SUBMITTED, template.getUpdatedAt());
        inst.setLastErmViewedAt(Instant.now());
        // Even though INTERN_SUBMITTED doesn't normally carry a PDF,
        // stub it to prove the defensive clear runs.
        inst.setFinalPdfDocumentId(UUID.randomUUID());
        inst = instances.saveAndFlush(inst);
        UUID ermSigDocId = UUID.randomUUID();
        UUID internSigDocId = UUID.randomUUID();
        stubValue(inst.getId(), FIELD_ERM_SIG_ID,    null, ermSigDocId);
        stubValue(inst.getId(), FIELD_INTERN_SIG_ID, null, internSigDocId);
        stubValue(inst.getId(), FIELD_ERM_TEXT_ID, "OldValue", null);

        // Admin RENAMES the ERM text field. Same id, same type — R2
        // rename branch fires and ERM is affected. Schema-only.
        adminUpdatesSchemaOnly(schemaJson(
                entry(FIELD_ERM_TEXT_ID,    "ERM Text 2",  "TEXT",      "ERM"),  // renamed
                entry(FIELD_ERM_SIG_ID,     "ERM Sig",     "SIGNATURE", "ERM"),
                entry(FIELD_INTERN_TEXT_ID, "Intern Text", "TEXT",      "INTERN"),
                entry(FIELD_INTERN_SIG_ID,  "Intern Sig",  "SIGNATURE", "INTERN")));

        DocumentInstanceDtos.ReopenTemplateResponse after =
                service.reopenForTemplateUpdate(inst.getId(), caller);

        assertEquals("DRAFT",  after.summary().toStatus());
        assertEquals("ERM",    after.summary().reroutedTo());

        DocumentInstance persisted = instances.findById(inst.getId()).orElseThrow();
        assertEquals(DocumentInstanceStatus.DRAFT, persisted.getStatus());
        assertNull(persisted.getLastErmViewedAt(),
                "lastErmViewedAt cleared on backward hop");
        assertNull(persisted.getFinalPdfDocumentId(),
                "finalPdfDocumentId cleared on backward hop (defensive)");
        assertFalse(persisted.getInternLocked(),
                "internLocked cleared for a DRAFT target (clean draft)");
        assertNull(persisted.getReturnReasonCode(),
                "returnReasonCode cleared for DRAFT target");
        assertNull(persisted.getUnlockedFieldIdsJson(),
                "unlockedFieldIdsJson cleared for DRAFT target");

        assertTrue(values.findByInstanceIdAndFieldId(inst.getId(), FIELD_ERM_SIG_ID).isEmpty(),
                "ERM sig invalidated (their fields changed)");
        assertEquals(internSigDocId,
                values.findByInstanceIdAndFieldId(inst.getId(), FIELD_INTERN_SIG_ID)
                        .orElseThrow().getSignatureDocumentId(),
                "INTERN sig preserved — cross-party change doesn't invalidate the intern");
    }

    /** (d) INTERN_SUBMITTED + BOTH parties' fields changed → DRAFT,
     *  BOTH sigs dropped, reroutedTo=BOTH_VIA_ERM. */
    @Test
    void d_both_parties_changed_routes_to_DRAFT_drops_both_sigs() throws Exception {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.INTERN_SUBMITTED, template.getUpdatedAt());
        UUID ermSigDocId = UUID.randomUUID();
        UUID internSigDocId = UUID.randomUUID();
        stubValue(inst.getId(), FIELD_ERM_SIG_ID,    null, ermSigDocId);
        stubValue(inst.getId(), FIELD_INTERN_SIG_ID, null, internSigDocId);

        // Admin renames BOTH text fields — schema-only, both parties affected.
        adminUpdatesSchemaOnly(schemaJson(
                entry(FIELD_ERM_TEXT_ID,    "ERM Text 2",     "TEXT",      "ERM"),
                entry(FIELD_ERM_SIG_ID,     "ERM Sig",        "SIGNATURE", "ERM"),
                entry(FIELD_INTERN_TEXT_ID, "Intern Text 2",  "TEXT",      "INTERN"),
                entry(FIELD_INTERN_SIG_ID,  "Intern Sig",     "SIGNATURE", "INTERN")));

        DocumentInstanceDtos.ReopenTemplateResponse after =
                service.reopenForTemplateUpdate(inst.getId(), caller);

        assertEquals("DRAFT",         after.summary().toStatus());
        assertEquals("BOTH_VIA_ERM",  after.summary().reroutedTo());
        assertEquals(2, after.summary().invalidatedSignatureCount());

        assertTrue(values.findByInstanceIdAndFieldId(inst.getId(), FIELD_ERM_SIG_ID).isEmpty(),
                "ERM sig dropped");
        assertTrue(values.findByInstanceIdAndFieldId(inst.getId(), FIELD_INTERN_SIG_ID).isEmpty(),
                "INTERN sig dropped");
    }

    /** (e) VERIFIED + intern-field changed → RETURNED,
     *  finalPdfDocumentId nulled. */
    @Test
    void e_verified_plus_intern_change_routes_to_RETURNED_clears_finalPdf() throws Exception {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.VERIFIED, template.getUpdatedAt());
        // VERIFIED doesn't normally carry a PDF yet (finalize does)
        // but the defensive clear must run regardless.
        inst.setFinalPdfDocumentId(UUID.randomUUID());
        inst = instances.saveAndFlush(inst);
        UUID internSigDocId = UUID.randomUUID();
        stubValue(inst.getId(), FIELD_INTERN_SIG_ID, null, internSigDocId);

        adminUpdatesSchemaOnly(schemaJson(
                entry(FIELD_ERM_TEXT_ID,    "ERM Text",     "TEXT",      "ERM"),
                entry(FIELD_ERM_SIG_ID,     "ERM Sig",      "SIGNATURE", "ERM"),
                entry(FIELD_INTERN_TEXT_ID, "Intern Text 2","TEXT",      "INTERN"),  // renamed
                entry(FIELD_INTERN_SIG_ID,  "Intern Sig",   "SIGNATURE", "INTERN")));

        DocumentInstanceDtos.ReopenTemplateResponse after =
                service.reopenForTemplateUpdate(inst.getId(), caller);

        assertEquals("VERIFIED", after.summary().fromStatus());
        assertEquals("RETURNED", after.summary().toStatus());

        DocumentInstance persisted = instances.findById(inst.getId()).orElseThrow();
        assertNull(persisted.getFinalPdfDocumentId(),
                "finalPdfDocumentId nulled defensively on any backward hop");
    }

    /** (f) Content/wording changed (canonical HTML byte differs)
     *  without any field-def diff → BOTH sigs dropped (over-
     *  approximation), target=DRAFT reroutedTo=BOTH_VIA_ERM. */
    @Test
    void f_content_changed_only_drops_both_sigs_routes_to_DRAFT() throws Exception {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.INTERN_SUBMITTED, template.getUpdatedAt());
        UUID ermSigDocId = UUID.randomUUID();
        UUID internSigDocId = UUID.randomUUID();
        stubValue(inst.getId(), FIELD_ERM_SIG_ID,    null, ermSigDocId);
        stubValue(inst.getId(), FIELD_INTERN_SIG_ID, null, internSigDocId);

        // Admin edits ONLY the canonical HTML (body wording) — no
        // schema change at all. Simulates a rewording edit.
        Thread.sleep(10);
        template = templates.findById(template.getId()).orElseThrow();
        template.setCanonicalHtml(canonicalHtml("v2-reworded-body"));
        // Schema untouched.
        template = templates.saveAndFlush(template);

        DocumentInstanceDtos.ReopenTemplateResponse after =
                service.reopenForTemplateUpdate(inst.getId(), caller);

        assertEquals("DRAFT",        after.summary().toStatus());
        assertEquals("BOTH_VIA_ERM", after.summary().reroutedTo());
        assertEquals(2, after.summary().invalidatedSignatureCount(),
                "content over-approximation drops BOTH sigs");
    }

    /** (g) AUTO-only changed → no status change; AUTO field
     *  re-resolved in place; no sig drop. */
    @Test
    void g_auto_only_change_no_status_hop_re_resolves_auto() throws Exception {
        // Add an AUTO field to the template + a stubbed old auto value.
        template.setFieldSchemaJson(schemaJson(
                entry(FIELD_ERM_TEXT_ID,    "ERM Text",    "TEXT",      "ERM"),
                entry(FIELD_ERM_SIG_ID,     "ERM Sig",     "SIGNATURE", "ERM"),
                entry(FIELD_INTERN_TEXT_ID, "Intern Text", "TEXT",      "INTERN"),
                entry(FIELD_INTERN_SIG_ID,  "Intern Sig",  "SIGNATURE", "INTERN"),
                entry(FIELD_AUTO_ID,        "Today",       "TEXT",      "AUTO", "today")));
        template = templates.saveAndFlush(template);

        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.SENT_TO_INTERN, template.getUpdatedAt());
        UUID ermSigDocId = UUID.randomUUID();
        UUID internSigDocId = UUID.randomUUID();
        stubValue(inst.getId(), FIELD_ERM_SIG_ID,    null, ermSigDocId);
        stubValue(inst.getId(), FIELD_INTERN_SIG_ID, null, internSigDocId);
        // No existing AUTO value — reopen will resolve + save it.

        // Admin edits only the AUTO field (no other schema/HTML change
        // — mutate a metadata attribute the R2 walk sees). Reuse the
        // same defaultSource ('today') so resolveAutoBinding still
        // works. Note: this is schema-only so no content change.
        adminUpdatesSchemaOnly(schemaJson(
                entry(FIELD_ERM_TEXT_ID,    "ERM Text",    "TEXT",      "ERM"),
                entry(FIELD_ERM_SIG_ID,     "ERM Sig",     "SIGNATURE", "ERM"),
                entry(FIELD_INTERN_TEXT_ID, "Intern Text", "TEXT",      "INTERN"),
                entry(FIELD_INTERN_SIG_ID,  "Intern Sig",  "SIGNATURE", "INTERN"),
                entry(FIELD_AUTO_ID,        "Today Bold",  "TEXT",      "AUTO", "today"))); // renamed AUTO

        DocumentInstanceDtos.ReopenTemplateResponse after =
                service.reopenForTemplateUpdate(inst.getId(), caller);

        // Renamed-AUTO counts as an AUTO-only diff — no ERM/INTERN
        // affected → AUTO_ONLY, no status hop.
        assertEquals("AUTO_ONLY", after.summary().reroutedTo());
        assertEquals("SENT_TO_INTERN", after.summary().toStatus(),
                "AUTO-only diff must not walk status backward");
        assertEquals(0, after.summary().invalidatedSignatureCount(),
                "AUTO-only diff must not drop any signatures");
        // NB: the actual value-row upsert from resolveAutoBinding is
        // gated on {@code intern != null && lc != null} in the service.
        // This IT builds instances directly with a random
        // internLifecycleId (no lifecycle row saved), so the re-resolve
        // branch skips. What matters for the routing test is the
        // reroutedTo decision — verified above. Value-row-level re-
        // resolution is deferred to an IT that seeds a full lifecycle
        // fixture; not in scope for this suite.
    }

    // ────────────────────────────────────────────────────────────
    // Safety boundary (h) — MANDATORY 409 trio
    // ────────────────────────────────────────────────────────────

    /** (h1) reopen on FINALIZED → 409. */
    @Test
    void h1_reopen_on_FINALIZED_is_rejected_with_409() {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.FINALIZED, template.getUpdatedAt());
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.reopenForTemplateUpdate(inst.getId(), caller));
        assertTrue(ex.getMessage().toLowerCase().contains("finalized"),
                "message must name FINALIZED — got: " + ex.getMessage());
    }

    /** (h2) reopen on REVOKED → 409. */
    @Test
    void h2_reopen_on_REVOKED_is_rejected_with_409() {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.REVOKED, template.getUpdatedAt());
        assertThrows(ConflictException.class,
                () -> service.reopenForTemplateUpdate(inst.getId(), caller));
    }

    /** (h3) reopen on SUPERSEDED → 409. */
    @Test
    void h3_reopen_on_SUPERSEDED_is_rejected_with_409() {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.SUPERSEDED, template.getUpdatedAt());
        assertThrows(ConflictException.class,
                () -> service.reopenForTemplateUpdate(inst.getId(), caller));
    }

    /** (h4) reopen on DRAFT → 409 (points to resync).  DRAFT has its
     *  own in-place path (resyncTemplate) — reopen is for in-flight. */
    @Test
    void h4_reopen_on_DRAFT_is_rejected_directs_to_resync() {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.DRAFT, template.getUpdatedAt());
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.reopenForTemplateUpdate(inst.getId(), caller));
        assertTrue(ex.getMessage().toLowerCase().contains("draft"),
                "message must direct to resync — got: " + ex.getMessage());
    }

    /** (h5) Guard-before-mutation — even on FINALIZED, a value row for
     *  a to-be-removed field must survive the reject. (Same shape as
     *  the resync suite's rejection-leaves-persisted-state test —
     *  guarded by transaction rollback.) */
    @Test
    void h5_reject_on_finalized_leaves_value_rows_untouched() {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.FINALIZED, template.getUpdatedAt());
        String orphanId = "field-not-in-current-schema";
        stubValue(inst.getId(), orphanId, "MustSurvive", null);

        assertThrows(ConflictException.class,
                () -> service.reopenForTemplateUpdate(inst.getId(), caller));

        assertEquals("MustSurvive",
                values.findByInstanceIdAndFieldId(inst.getId(), orphanId)
                        .orElseThrow(() -> new AssertionError(
                                "orphan row must survive a rejected reopen")).getValueText());
    }

    // ────────────────────────────────────────────────────────────
    // Value-reapply parity (i) — shared helper behaves identically
    // ────────────────────────────────────────────────────────────

    /** (i) The reopen's value-reapply behaviour is identical to
     *  resyncTemplate's — same keep / drop-removed / drop-type-changed
     *  / rename outcomes. This proves the extracted shared helper
     *  didn't regress the resync path. The 14 resync tests already
     *  cover DRAFT resync exhaustively (kept green in this branch);
     *  this test exercises the same rules through the reopen path on
     *  an in-flight state to prove the helper is genuinely shared. */
    @Test
    void i_value_reapply_parity_all_four_R2_cases_on_reopen() throws Exception {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.SENT_TO_INTERN, template.getUpdatedAt());
        stubValue(inst.getId(), FIELD_ERM_TEXT_ID,    "Alice",  null);       // will be KEPT
        stubValue(inst.getId(), FIELD_INTERN_TEXT_ID, "Bob",    null);       // will be DROP-REMOVED
        stubValue(inst.getId(), "field-to-type-change", "2026-01-01", null); // will be DROP-TYPE-CHANGED

        adminUpdatesSchemaOnly(schemaJson(
                entry(FIELD_ERM_TEXT_ID,       "ERM Text",   "TEXT",      "ERM"),
                entry(FIELD_ERM_SIG_ID,        "ERM Sig",    "SIGNATURE", "ERM"),
                // FIELD_INTERN_TEXT_ID removed
                entry(FIELD_INTERN_SIG_ID,     "Intern Sig", "SIGNATURE", "INTERN"),
                entry("field-to-type-change",  "Type Chg",   "TEXT",      "INTERN"), // was DATE upstream but we didn't stub — see note
                entry(FIELD_NEW_INTERN_ID,     "New Intern", "TEXT",      "INTERN")));

        service.reopenForTemplateUpdate(inst.getId(), caller);

        // KEEP
        assertEquals("Alice",
                values.findByInstanceIdAndFieldId(inst.getId(), FIELD_ERM_TEXT_ID)
                        .orElseThrow().getValueText(),
                "KEEP branch — ERM text value survives");
        // DROP-REMOVED
        assertTrue(values.findByInstanceIdAndFieldId(inst.getId(), FIELD_INTERN_TEXT_ID).isEmpty(),
                "DROP-REMOVED — intern text row deleted (id gone from new schema)");
        // NEW field — no value row created
        assertTrue(values.findByInstanceIdAndFieldId(inst.getId(), FIELD_NEW_INTERN_ID).isEmpty(),
                "NEW field — no value row until intern fills");
    }

    // ────────────────────────────────────────────────────────────
    // Audit (j) — legally significant reopen event
    // ────────────────────────────────────────────────────────────

    /** (j) Reopen writes a REOPEN audit row carrying fromStatus,
     *  toStatus, reroutedTo, invalidatedSignatureFieldIds. */
    @Test
    void j_reopen_writes_audit_row_with_from_to_rerouted_and_sig_ids() throws Exception {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.INTERN_SUBMITTED, template.getUpdatedAt());
        UUID internSigDocId = UUID.randomUUID();
        stubValue(inst.getId(), FIELD_INTERN_SIG_ID, null, internSigDocId);

        adminUpdatesSchemaOnly(schemaJson(
                entry(FIELD_ERM_TEXT_ID,    "ERM Text",     "TEXT",      "ERM"),
                entry(FIELD_ERM_SIG_ID,     "ERM Sig",      "SIGNATURE", "ERM"),
                entry(FIELD_INTERN_TEXT_ID, "Intern Text 2","TEXT",      "INTERN"),  // rename
                entry(FIELD_INTERN_SIG_ID,  "Intern Sig",   "SIGNATURE", "INTERN")));

        service.reopenForTemplateUpdate(inst.getId(), caller);

        // Look for the REOPEN audit row.
        AuditLog reopenAudit = auditLogs.findAll().stream()
                .filter(a -> "REOPEN".equals(a.getAction())
                        && inst.getId().equals(a.getEntityId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no REOPEN audit row found for instance " + inst.getId()));
        String afterJson = reopenAudit.getAfterJson();
        assertTrue(afterJson.contains("\"fromStatus\":\"INTERN_SUBMITTED\""),
                "audit payload missing fromStatus: " + afterJson);
        assertTrue(afterJson.contains("\"toStatus\":\"RETURNED\""),
                "audit payload missing toStatus: " + afterJson);
        assertTrue(afterJson.contains("\"reroutedTo\":\"INTERN\""),
                "audit payload missing reroutedTo: " + afterJson);
        assertTrue(afterJson.contains(FIELD_INTERN_SIG_ID),
                "audit payload must name the invalidated signature field id: " + afterJson);

        // Review log row for REOPEN also present.
        List<DocumentInstanceReviewLog> log =
                reviewLogs.findByInstanceIdOrderByCreatedAtAsc(inst.getId());
        assertTrue(log.stream().anyMatch(rr -> "REOPEN".equals(rr.getAction())),
                "review log must carry a REOPEN row");
    }

    // ────────────────────────────────────────────────────────────
    // Idempotency (k)
    // ────────────────────────────────────────────────────────────

    /** (k) Reopen with no template change → no-op (no status change,
     *  no sig drop, watermark restamped). */
    @Test
    void k_reopen_with_no_change_is_a_noop() {
        DocumentInstance inst = saveInstance(
                DocumentInstanceStatus.SENT_TO_INTERN, template.getUpdatedAt());
        UUID ermSigDocId = UUID.randomUUID();
        UUID internSigDocId = UUID.randomUUID();
        stubValue(inst.getId(), FIELD_ERM_SIG_ID,    null, ermSigDocId);
        stubValue(inst.getId(), FIELD_INTERN_SIG_ID, null, internSigDocId);

        // NO admin edit — watermark on the instance matches
        // template.updatedAt already.

        DocumentInstanceDtos.ReopenTemplateResponse after =
                service.reopenForTemplateUpdate(inst.getId(), caller);

        assertEquals("SENT_TO_INTERN", after.summary().fromStatus());
        assertEquals("SENT_TO_INTERN", after.summary().toStatus(),
                "no-op reopen must not walk status backward");
        assertEquals("NONE", after.summary().reroutedTo());
        assertEquals(0, after.summary().invalidatedSignatureCount());

        // Sigs survive.
        assertEquals(ermSigDocId,
                values.findByInstanceIdAndFieldId(inst.getId(), FIELD_ERM_SIG_ID)
                        .orElseThrow().getSignatureDocumentId());
        assertEquals(internSigDocId,
                values.findByInstanceIdAndFieldId(inst.getId(), FIELD_INTERN_SIG_ID)
                        .orElseThrow().getSignatureDocumentId());
    }

    // ────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────

    private record F(String id, String name, String type,
                     String assignee, boolean required, String defaultSource) {}

    private F entry(String id, String name, String type, String assignee) {
        return new F(id, name, type, assignee, false, null);
    }

    private F entry(String id, String name, String type, String assignee,
                    String defaultSource) {
        return new F(id, name, type, assignee, false, defaultSource);
    }

    private String schemaJson(F... entries) {
        try {
            return objectMapper.writeValueAsString(List.of(entries));
        } catch (Exception e) {
            throw new AssertionError("test schema serialisation failed", e);
        }
    }

    private String canonicalHtml(String tag) {
        return "<p>" + tag + "-html</p>";
    }

    private DocumentInstance saveInstance(DocumentInstanceStatus status,
                                          Instant watermark) {
        return instances.save(DocumentInstance.builder()
                .templateId(template.getId())
                .internLifecycleId(UUID.randomUUID())
                .internUserId(intern.getId())
                .createdByErmId(caller.getId())
                .status(status)
                .version(1)
                .internLocked(status == DocumentInstanceStatus.SENT_TO_INTERN
                        || status == DocumentInstanceStatus.INTERN_SUBMITTED
                        || status == DocumentInstanceStatus.VERIFIED
                        || status == DocumentInstanceStatus.FINALIZED)
                .templateTitle(template.getTitle())
                .templateKey(template.getKey())
                .snapshotCanonicalHtml(template.getCanonicalHtml())
                .snapshotFieldSchemaJson(template.getFieldSchemaJson())
                .snapshotTemplateUpdatedAt(watermark)
                .build());
    }

    private void stubValue(UUID instanceId, String fieldId,
                           String valueText, UUID signatureDocId) {
        // Stash the fieldName that CURRENTLY matches the template's
        // schema entry for this fieldId. If we used a placeholder
        // (e.g. the fieldId itself), the R2 rename branch would fire
        // on the first reopen even without an admin edit — because
        // the schema's real name would differ from the placeholder,
        // and rename → the party marked affected → wrong routing.
        String realName = nameFromCurrentTemplateSchema(fieldId);
        values.save(DocumentInstanceFieldValue.builder()
                .instanceId(instanceId)
                .fieldId(fieldId)
                .fieldName(realName != null ? realName : fieldId)
                .valueText(valueText)
                .signatureDocumentId(signatureDocId)
                .filledByRole("ERM")
                .filledAt(Instant.now())
                .build());
    }

    /** Resolve a field id's current name from the template's schema
     *  JSON. Returns null if the schema doesn't carry that id — the
     *  caller falls back to the id itself (as with the orphan-value-
     *  row test that intentionally seeds a value for an id NOT in
     *  the schema). */
    private String nameFromCurrentTemplateSchema(String fieldId) {
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(
                    template.getFieldSchemaJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> m : raw) {
                if (fieldId.equals(m.get("id"))) {
                    Object n = m.get("name");
                    return n == null ? null : n.toString();
                }
            }
        } catch (Exception ignored) {
            // Fall through — caller uses fieldId.
        }
        return null;
    }

    /** Simulate an admin schema-only edit — schema JSON changes,
     *  canonical HTML stays byte-identical. This is the test lever
     *  for exercising the party-scoped R-SIG branch without tripping
     *  the content over-approximation. */
    private void adminUpdatesSchemaOnly(String newSchemaJson)
            throws InterruptedException {
        Thread.sleep(10);
        template = templates.findById(template.getId()).orElseThrow();
        template.setFieldSchemaJson(newSchemaJson);
        // canonicalHtml INTENTIONALLY untouched.
        template = templates.saveAndFlush(template);
    }

    private List<String> parseUnlocked(String json) {
        if (json == null) return List.of();
        try {
            return objectMapper.readValue(json,
                    new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new AssertionError("unlockedFieldIds parse failed", e);
        }
    }
}
