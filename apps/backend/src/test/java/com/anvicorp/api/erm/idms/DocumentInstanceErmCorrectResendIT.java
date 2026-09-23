package com.anvicorp.api.erm.idms;

import com.anvicorp.api.admin.editabletemplates.EditableTemplate;
import com.anvicorp.api.admin.editabletemplates.EditableTemplateRepository;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ERM "correct &amp; re-send" —
 * {@link DocumentInstanceService#reopenForErmCorrection}.
 *
 * <p>The case: an ERM fills an offer, signs it, sends it, then notices
 * they typed the wrong date. The intern hasn't signed. The ERM pulls it
 * back to DRAFT on the SAME record, fixes the one field, re-sends.</p>
 *
 * <p>The two behaviours worth pinning hardest are the ones a naive
 * implementation gets backwards:</p>
 * <ul>
 *   <li><b>Values survive, signatures don't.</b> The ERM must fix one
 *       field, not re-key the document — and the intern's partial typing
 *       must not be destroyed either. But neither party's signature can
 *       survive, because the document is about to say something neither
 *       of them signed.</li>
 *   <li><b>SENT_TO_INTERN only.</b> Once the intern submits, pulling the
 *       document back would silently discard work they completed. That
 *       case supersedes instead, on a separate flow.</li>
 * </ul>
 *
 * <p>Fixture shape mirrors {@code DocumentInstanceReopenTemplateIT} —
 * H2 in Postgres compat mode for the JSONB columns, service-layer
 * exercise, hand-built instances so non-DRAFT states can be seeded
 * directly.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:anvi_idms_correct;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "app.webmail.seed.admin-enabled=false"
})
class DocumentInstanceErmCorrectResendIT {

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

    private static final String F_ERM_DATE    = "field-erm-date";
    private static final String F_ERM_SIG     = "field-erm-sig";
    private static final String F_INTERN_TEXT = "field-intern-text";
    private static final String F_INTERN_SIG  = "field-intern-sig";

    @BeforeEach
    void seed() {
        reviewLogs.deleteAll();
        auditLogs.deleteAll();
        values.deleteAll();
        instances.deleteAll();
        templates.deleteAll();
        users.deleteAll();

        caller = users.save(User.builder()
                .email("erm-correct@anvicorp.com")
                .fullName("ERM Caller")
                .roles(new HashSet<>(Set.of(UserRole.SUPER_ADMIN)))
                .build());
        intern = users.save(User.builder()
                .email("intern-correct@anvicorp.com")
                .fullName("Intern Person")
                .roles(new HashSet<>(Set.of(UserRole.INTERN)))
                .build());
        template = templates.save(EditableTemplate.builder()
                .key("offer_correct_test")
                .title("Correct Test Offer")
                .description("v1")
                .active(true)
                .sortOrder(500)
                .createdById(caller.getId())
                .canonicalHtml("<p>offer-html</p>")
                .fieldSchemaJson(schemaJson(
                        entry(F_ERM_DATE,    "Start Date",  "DATE",      "ERM"),
                        entry(F_ERM_SIG,     "ERM Sig",     "SIGNATURE", "ERM"),
                        entry(F_INTERN_TEXT, "Intern Note", "TEXT",      "INTERN"),
                        entry(F_INTERN_SIG,  "Intern Sig",  "SIGNATURE", "INTERN")))
                .build());
    }

    // ── (a) the happy path ───────────────────────────────────────────

    /**
     * (a) SENT_TO_INTERN → DRAFT. Values preserved (both parties'),
     * BOTH signatures dropped, backward-hop columns cleared, audit
     * payload correct.
     *
     * <p>The intern is seeded as signed-but-not-submitted, which is a
     * real reachable state — {@code requireCanFill} lets the intern sign
     * on SENT_TO_INTERN; only submit moves the status on.</p>
     */
    @Test
    void a_sent_to_draft_preserves_values_drops_both_signatures() {
        DocumentInstance inst = saveInstance(DocumentInstanceStatus.SENT_TO_INTERN);
        // Forward-only state that must not survive the hop.
        inst.setLastErmViewedAt(Instant.now());
        inst.setFinalPdfDocumentId(UUID.randomUUID());
        inst.setReturnReasonCode("STALE_FROM_EARLIER_CYCLE");
        inst.setReturnComments("stale");
        inst = instances.saveAndFlush(inst);
        int versionBefore = inst.getVersion();

        UUID ermSigDoc = UUID.randomUUID();
        UUID internSigDoc = UUID.randomUUID();
        stubValue(inst.getId(), F_ERM_DATE,    "2026-01-01", null);
        stubValue(inst.getId(), F_ERM_SIG,     null,         ermSigDoc);
        stubValue(inst.getId(), F_INTERN_TEXT, "intern typed this", null);
        stubValue(inst.getId(), F_INTERN_SIG,  null,         internSigDoc);

        DocumentInstanceDtos.InstanceDetail after = service.reopenForErmCorrection(
                inst.getId(),
                new DocumentInstanceDtos.CorrectRequest("WRONG_DATE", "typo'd the start date", null),
                caller);

        assertEquals("DRAFT", after.status(),
                "a sent-but-unsigned offer must come back to DRAFT");

        DocumentInstance p = instances.findById(inst.getId()).orElseThrow();
        assertEquals(DocumentInstanceStatus.DRAFT, p.getStatus());
        // Backward-hop mutations.
        assertNull(p.getLastErmViewedAt(), "verify gate must be re-earned");
        assertNull(p.getFinalPdfDocumentId(), "no stale rendered PDF may survive");
        assertFalse(p.getInternLocked(), "internLocked must clear for a clean draft");
        assertNull(p.getReturnReasonCode(),
                "the intern-facing return reason is not where an ERM's own "
                        + "correction note belongs");
        assertNull(p.getReturnComments());
        assertNull(p.getUnlockedFieldIdsJson());
        assertEquals(versionBefore + 1, p.getVersion(), "version must bump");

        // D5 — values preserved, BOTH parties'.
        assertEquals("2026-01-01",
                values.findByInstanceIdAndFieldId(inst.getId(), F_ERM_DATE)
                        .orElseThrow().getValueText(),
                "the ERM must correct one field, not re-key the document");
        assertEquals("intern typed this",
                values.findByInstanceIdAndFieldId(inst.getId(), F_INTERN_TEXT)
                        .orElseThrow().getValueText(),
                "the intern's partial typing must not be destroyed");

        // D4 — BOTH signatures dropped.
        assertTrue(values.findByInstanceIdAndFieldId(inst.getId(), F_ERM_SIG).isEmpty(),
                "the ERM signed the value they are now changing — sig must drop");
        assertTrue(values.findByInstanceIdAndFieldId(inst.getId(), F_INTERN_SIG).isEmpty(),
                "the intern signed a document whose ERM value is about to change, "
                        + "and never saw the new one — sig must drop");

        // Audit.
        AuditLog audit = auditLogs.findAll().stream()
                .filter(x -> "CORRECT_REOPEN".equals(x.getAction()))
                .findFirst().orElseThrow(() -> new AssertionError("no CORRECT_REOPEN audit row"));
        String meta = String.valueOf(audit.getAfterJson());
        assertTrue(meta.contains("SENT_TO_INTERN"), "audit must record fromStatus: " + meta);
        assertTrue(meta.contains("DRAFT"), "audit must record toStatus: " + meta);
        assertTrue(meta.contains("WRONG_DATE"), "audit must record the reason: " + meta);
        assertTrue(meta.contains(F_ERM_SIG) && meta.contains(F_INTERN_SIG),
                "audit must name both invalidated signature fields: " + meta);

        assertTrue(reviewLogs.findAll().stream()
                        .anyMatch(r -> "CORRECT_REOPEN".equals(r.getAction())),
                "a review-log row must record the correction");
    }

    /** (a2) The ERM can actually edit again once it's back in DRAFT —
     *  the whole point of the round trip. */
    @Test
    void a2_erm_can_edit_the_wrong_field_after_correction() {
        DocumentInstance inst = saveInstance(DocumentInstanceStatus.SENT_TO_INTERN);
        stubValue(inst.getId(), F_ERM_DATE, "2026-01-01", null);

        // Before: the ERM edit lock holds on a sent document.
        assertThrows(ConflictException.class, () -> service.fillFields(
                inst.getId(),
                new DocumentInstanceDtos.FillFieldsRequest(
                        java.util.Map.of(F_ERM_DATE, "2026-02-02"), null),
                caller),
                "a SENT document must not be ERM-editable");

        service.reopenForErmCorrection(inst.getId(), null, caller);

        // After: the correction lands.
        service.fillFields(
                inst.getId(),
                new DocumentInstanceDtos.FillFieldsRequest(
                        java.util.Map.of(F_ERM_DATE, "2026-02-02"), null),
                caller);
        assertEquals("2026-02-02",
                values.findByInstanceIdAndFieldId(inst.getId(), F_ERM_DATE)
                        .orElseThrow().getValueText());
    }

    // ── (b) the guard ────────────────────────────────────────────────

    /**
     * (b) Every state except SENT_TO_INTERN is rejected. INTERN_SUBMITTED
     * is the important one: the intern has completed and signed, so
     * pulling the document back would discard their work silently.
     */
    @Test
    void b_guard_rejects_every_state_except_sent_to_intern() {
        for (DocumentInstanceStatus s : List.of(
                DocumentInstanceStatus.INTERN_SUBMITTED,
                DocumentInstanceStatus.VERIFIED,
                DocumentInstanceStatus.FINALIZED,
                DocumentInstanceStatus.REVOKED,
                DocumentInstanceStatus.RETURNED,
                DocumentInstanceStatus.SUPERSEDED,
                DocumentInstanceStatus.VOIDED)) {
            DocumentInstance inst = saveInstance(s);
            UUID sigDoc = UUID.randomUUID();
            stubValue(inst.getId(), F_ERM_SIG, null, sigDoc);

            ConflictException ex = assertThrows(ConflictException.class,
                    () -> service.reopenForErmCorrection(inst.getId(), null, caller),
                    "must reject " + s);
            assertTrue(ex.getMessage().contains("sent, unsigned"),
                    "message should say why, got: " + ex.getMessage());

            // The guard fires BEFORE any mutation — a rejected call is a
            // byte-identical no-op, signature included.
            DocumentInstance p = instances.findById(inst.getId()).orElseThrow();
            assertEquals(s, p.getStatus(), s + " must be untouched");
            assertEquals(sigDoc,
                    values.findByInstanceIdAndFieldId(inst.getId(), F_ERM_SIG)
                            .orElseThrow().getSignatureDocumentId(),
                    "a rejected correction must not drop signatures on " + s);
        }
    }

    // ── (c) idempotency ──────────────────────────────────────────────

    /** (c) Already DRAFT → no-op. No second audit row, and crucially no
     *  second signature sweep (which would clear a signature the ERM
     *  just re-made). */
    @Test
    void c_already_draft_is_an_idempotent_noop() {
        DocumentInstance inst = saveInstance(DocumentInstanceStatus.DRAFT);
        UUID freshSig = UUID.randomUUID();
        stubValue(inst.getId(), F_ERM_SIG, null, freshSig);
        int versionBefore = inst.getVersion();

        service.reopenForErmCorrection(inst.getId(), null, caller);

        DocumentInstance p = instances.findById(inst.getId()).orElseThrow();
        assertEquals(DocumentInstanceStatus.DRAFT, p.getStatus());
        assertEquals(versionBefore, p.getVersion(), "no version bump on a no-op");
        assertEquals(freshSig,
                values.findByInstanceIdAndFieldId(inst.getId(), F_ERM_SIG)
                        .orElseThrow().getSignatureDocumentId(),
                "a re-entrant call must not clear a signature made since");
        assertTrue(auditLogs.findAll().stream()
                        .noneMatch(x -> "CORRECT_REOPEN".equals(x.getAction())),
                "a no-op must not write an audit row");
    }

    // ── (d) non-signature values ─────────────────────────────────────

    /** (d) A non-signature field stays filled — restated on its own so a
     *  future change to the signature sweep can't quietly widen into
     *  deleting value rows. */
    @Test
    void d_non_signature_values_survive() {
        DocumentInstance inst = saveInstance(DocumentInstanceStatus.SENT_TO_INTERN);
        stubValue(inst.getId(), F_ERM_DATE,    "2026-03-03", null);
        stubValue(inst.getId(), F_INTERN_TEXT, "note", null);

        service.reopenForErmCorrection(inst.getId(), null, caller);

        assertEquals(2, values.findAll().stream()
                        .filter(v -> v.getInstanceId().equals(inst.getId())).count(),
                "no value row may be deleted when there are no signatures");
        assertEquals("2026-03-03",
                values.findByInstanceIdAndFieldId(inst.getId(), F_ERM_DATE)
                        .orElseThrow().getValueText());
    }

    /** No snapshot re-take — an unrelated admin template edit must not
     *  ride along on a "fix my typo" action. */
    @Test
    void does_not_resnapshot_the_template() {
        DocumentInstance inst = saveInstance(DocumentInstanceStatus.SENT_TO_INTERN);
        String snapshotBefore = inst.getSnapshotCanonicalHtml();
        String schemaBefore = inst.getSnapshotFieldSchemaJson();

        // Admin edits the template AFTER the doc was sent.
        template = templates.findById(template.getId()).orElseThrow();
        template.setCanonicalHtml("<p>ADMIN-EDITED-html</p>");
        templates.saveAndFlush(template);

        service.reopenForErmCorrection(inst.getId(), null, caller);

        DocumentInstance p = instances.findById(inst.getId()).orElseThrow();
        assertEquals(snapshotBefore, p.getSnapshotCanonicalHtml(),
                "correcting a typo must NOT silently adopt an unrelated "
                        + "admin template edit");
        assertEquals(schemaBefore, p.getSnapshotFieldSchemaJson());
    }

    /** Reason is optional — fixing your own typo shouldn't require
     *  filling in a form. Defaults to ERM_CORRECTION in the audit. */
    @Test
    void reason_is_optional_and_defaults_in_the_audit() {
        DocumentInstance inst = saveInstance(DocumentInstanceStatus.SENT_TO_INTERN);
        service.reopenForErmCorrection(inst.getId(), null, caller);

        AuditLog audit = auditLogs.findAll().stream()
                .filter(x -> "CORRECT_REOPEN".equals(x.getAction()))
                .findFirst().orElseThrow();
        assertTrue(String.valueOf(audit.getAfterJson()).contains("ERM_CORRECTION"),
                "a missing reason must still record something legible: "
                        + audit.getAfterJson());
    }

    /** canErmCorrect is true exactly in the sent-but-unsigned window. */
    @Test
    void canErmCorrect_flag_tracks_the_sent_window() {
        DocumentInstance sent = saveInstance(DocumentInstanceStatus.SENT_TO_INTERN);
        assertTrue(service.getDetail(sent.getId(), caller).actions().canErmCorrect(),
                "the action must be offered on a sent document");

        DocumentInstance draft = saveInstance(DocumentInstanceStatus.DRAFT);
        assertFalse(service.getDetail(draft.getId(), caller).actions().canErmCorrect(),
                "a draft is already editable — nothing to correct back to");

        DocumentInstance submitted = saveInstance(DocumentInstanceStatus.INTERN_SUBMITTED);
        assertFalse(service.getDetail(submitted.getId(), caller).actions().canErmCorrect(),
                "once the intern submits, correcting supersedes rather than reopens");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private record F(String id, String name, String type,
                     String assignee, boolean required, String defaultSource) {}

    private F entry(String id, String name, String type, String assignee) {
        return new F(id, name, type, assignee, false, null);
    }

    private String schemaJson(F... entries) {
        try {
            return objectMapper.writeValueAsString(List.of(entries));
        } catch (Exception e) {
            throw new AssertionError("test schema serialisation failed", e);
        }
    }

    private DocumentInstance saveInstance(DocumentInstanceStatus status) {
        return instances.save(DocumentInstance.builder()
                .templateId(template.getId())
                .internLifecycleId(UUID.randomUUID())
                .internUserId(intern.getId())
                .createdByErmId(caller.getId())
                .status(status)
                .version(1)
                .internLocked(status != DocumentInstanceStatus.DRAFT)
                .templateTitle(template.getTitle())
                .templateKey(template.getKey())
                .snapshotCanonicalHtml(template.getCanonicalHtml())
                .snapshotFieldSchemaJson(template.getFieldSchemaJson())
                .snapshotTemplateUpdatedAt(template.getUpdatedAt())
                .build());
    }

    private void stubValue(UUID instanceId, String fieldId,
                           String valueText, UUID signatureDocId) {
        values.save(DocumentInstanceFieldValue.builder()
                .instanceId(instanceId)
                .fieldId(fieldId)
                .fieldName(fieldId)
                .valueText(valueText)
                .signatureDocumentId(signatureDocId)
                .filledByRole(signatureDocId != null ? "ERM" : "ERM")
                .filledAt(Instant.now())
                .build());
    }
}
