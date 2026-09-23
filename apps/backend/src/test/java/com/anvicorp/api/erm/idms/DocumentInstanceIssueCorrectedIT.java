package com.anvicorp.api.erm.idms;

import com.anvicorp.api.admin.editabletemplates.EditableTemplate;
import com.anvicorp.api.admin.editabletemplates.EditableTemplateRepository;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Issue corrected offer" —
 * {@link DocumentInstanceService#issueCorrectedOffer}.
 *
 * <p>The case its sibling can't handle. {@code reopenForErmCorrection}
 * rewinds a sent-but-unsigned offer on the same record; once a party has
 * SIGNED, the document is a record of what was agreed and has to survive
 * as one. So this revokes it and issues a fresh copy carrying its
 * answers forward.</p>
 *
 * <p>Four things here are worth more than the happy path, because each
 * is a way this feature could quietly do damage:</p>
 * <ul>
 *   <li><b>The prior stays REVOKED, never SUPERSEDED</b> — only REVOKED
 *       has somewhere to record why it was withdrawn.</li>
 *   <li><b>Signatures don't carry</b>, and the filter is on the
 *       signature image, not on text — a signature row also holds the
 *       signer's typed name.</li>
 *   <li><b>Atomicity</b> — a failure after the revoke leg must not leave
 *       a destroyed offer with no replacement.</li>
 *   <li><b>The start-date gate holds</b> — this revokes, so it inherits
 *       the same protection Revoke has.</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:anvi_idms_issuecorrected;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "app.webmail.seed.admin-enabled=false"
})
class DocumentInstanceIssueCorrectedIT {

    @Autowired DocumentInstanceService service;
    @Autowired DocumentInstanceRepository instances;
    @Autowired DocumentInstanceFieldValueRepository values;
    @Autowired DocumentInstanceReviewLogRepository reviewLogs;
    @Autowired AuditLogRepository auditLogs;
    @Autowired EditableTemplateRepository templates;
    @Autowired InternLifecycleRepository lifecycles;
    @Autowired UserRepository users;
    @Autowired ObjectMapper objectMapper;

    private User caller;
    private User intern;
    private InternLifecycle lifecycle;
    private EditableTemplate template;

    private static final String F_DATE   = "field-date";
    private static final String F_ROLE   = "field-role";
    private static final String F_AUTO   = "field-auto";
    private static final String F_ERMSIG = "field-erm-sig";
    private static final String F_INTSIG = "field-intern-sig";

    @BeforeEach
    void seed() {
        reviewLogs.deleteAll();
        auditLogs.deleteAll();
        values.deleteAll();
        instances.deleteAll();
        templates.deleteAll();
        lifecycles.deleteAll();
        users.deleteAll();

        caller = users.save(User.builder()
                .email("erm-issue@anvicorp.com")
                .fullName("ERM Caller")
                .roles(new HashSet<>(Set.of(UserRole.SUPER_ADMIN)))
                .build());
        intern = users.save(User.builder()
                .email("intern-issue@anvicorp.com")
                .fullName("Intern Person")
                .roles(new HashSet<>(Set.of(UserRole.INTERN)))
                .build());
        lifecycle = lifecycles.save(InternLifecycle.builder()
                .userId(intern.getId())
                .employeeId("EMP-ISSUE-1")
                .activeStatus(InternLifecycleStatus.ONBOARDING_ASSIGNED.name())
                .hiredAt(Instant.now())
                .reportingStructureComplete(false)
                // Start date comfortably in the future so the revocation
                // gate is open by default; the gate test moves it.
                .tentativeStartDate(LocalDate.now().plusMonths(6))
                .build());
        template = templates.save(EditableTemplate.builder()
                .key("offer_issue_corrected_test")
                .title("Issue Corrected Test Offer")
                .description("v1")
                .active(true)
                .sortOrder(500)
                .createdById(caller.getId())
                .canonicalHtml("<p>offer-html</p>")
                .fieldSchemaJson(schemaJson(
                        entry(F_DATE,   "Start Date", "DATE",      "ERM"),
                        entry(F_ROLE,   "Role",       "TEXT",      "ERM"),
                        entry(F_AUTO,   "Full Name",  "TEXT",      "AUTO"),
                        entry(F_ERMSIG, "ERM Sig",    "SIGNATURE", "ERM"),
                        entry(F_INTSIG, "Intern Sig", "SIGNATURE", "INTERN")))
                .build());
    }

    // ── (a) the happy path from a FINALIZED prior ────────────────────

    @Test
    void a_finalized_prior_stays_revoked_and_the_corrected_offer_is_prefilled() {
        DocumentInstance prior = saveInstance(DocumentInstanceStatus.FINALIZED);
        stubValue(prior.getId(), F_DATE, "2026-01-01", null, "ERM");
        stubValue(prior.getId(), F_ROLE, "Software Intern", null, "ERM");
        stubValue(prior.getId(), F_AUTO, "STALE Name", null, "AUTO");

        DocumentInstanceDtos.IssueCorrectedResponse res = service.issueCorrectedOffer(
                prior.getId(),
                new DocumentInstanceDtos.IssueCorrectedRequest(
                        "WRONG_DATE", "start date was wrong", null),
                caller);

        // D2 — prior REVOKED, and explicitly NOT superseded.
        DocumentInstance priorAfter = instances.findById(prior.getId()).orElseThrow();
        assertEquals(DocumentInstanceStatus.REVOKED, priorAfter.getStatus(),
                "the signed prior must be revoked, preserving it as a record");
        assertNotEquals(DocumentInstanceStatus.SUPERSEDED, priorAfter.getStatus(),
                "REVOKED is the honest state — only it can hold revokeReasonCode");
        assertEquals("WRONG_DATE", priorAfter.getRevokeReasonCode());

        // D1 — new offer, DRAFT, linked.
        UUID newId = UUID.fromString(res.instance().id().toString());
        DocumentInstance fresh = instances.findById(newId).orElseThrow();
        assertEquals(DocumentInstanceStatus.DRAFT, fresh.getStatus(),
                "the ERM reviews the pre-fill before anything reaches the intern");
        assertEquals(prior.getId(), fresh.getSupersedesId(),
                "supersedesId is the lineage link back to the revoked original");
        assertNotEquals(prior.getId(), fresh.getId());

        // D3 — values carried.
        assertEquals("2026-01-01",
                values.findByInstanceIdAndFieldId(newId, F_DATE).orElseThrow().getValueText(),
                "the ERM fixes one field rather than re-keying the offer");
        assertEquals("Software Intern",
                values.findByInstanceIdAndFieldId(newId, F_ROLE).orElseThrow().getValueText());
        assertEquals(2, res.carriedCount(), "two ERM answers carried");
        assertTrue(res.droppedFieldNames().isEmpty(),
                "nothing should drop when the template hasn't moved");

        // AUTO resolved fresh, NOT copied from the prior's stale row.
        String auto = values.findByInstanceIdAndFieldId(newId, F_AUTO)
                .map(DocumentInstanceFieldValue::getValueText).orElse(null);
        assertNotEquals("STALE Name", auto,
                "AUTO fields resolve against current platform data — a copied "
                        + "stale value would overwrite the correct one");

        // Audit.
        AuditLog audit = auditLogs.findAll().stream()
                .filter(a -> "ISSUE_CORRECTED".equals(a.getAction()))
                .findFirst().orElseThrow(() -> new AssertionError("no ISSUE_CORRECTED audit"));
        String after = String.valueOf(audit.getAfterJson());
        assertTrue(after.contains(prior.getId().toString()), "audit names the prior: " + after);
        assertTrue(after.contains(newId.toString()), "audit names the new offer: " + after);
        assertTrue(after.contains("\"carriedCount\":2") || after.contains("carriedCount"),
                "audit records how much carried: " + after);
    }

    // ── (b) already-revoked prior ────────────────────────────────────

    @Test
    void b_already_revoked_prior_skips_the_revoke_leg() {
        DocumentInstance prior = saveInstance(DocumentInstanceStatus.REVOKED);
        prior.setRevokeReasonCode("ORIGINAL_REASON");
        prior.setRevokedAt(Instant.now());
        prior = instances.saveAndFlush(prior);
        stubValue(prior.getId(), F_ROLE, "Software Intern", null, "ERM");

        DocumentInstanceDtos.IssueCorrectedResponse res = service.issueCorrectedOffer(
                prior.getId(),
                new DocumentInstanceDtos.IssueCorrectedRequest("REISSUE", null, null),
                caller);

        DocumentInstance priorAfter = instances.findById(prior.getId()).orElseThrow();
        assertEquals(DocumentInstanceStatus.REVOKED, priorAfter.getStatus());
        assertEquals("ORIGINAL_REASON", priorAfter.getRevokeReasonCode(),
                "a second revoke must not overwrite the original reason");
        assertEquals(1, res.carriedCount());
        assertEquals(prior.getId(),
                instances.findById(res.instance().id()).orElseThrow().getSupersedesId());
    }

    /** An already-revoked prior needs no revoke, so the start-date gate
     *  must NOT block re-issuing for an intern who has since started. */
    @Test
    void b2_already_revoked_prior_is_not_blocked_by_the_start_date_gate() {
        lifecycle.setStartedAt(Instant.now());
        lifecycles.saveAndFlush(lifecycle);
        DocumentInstance prior = saveInstance(DocumentInstanceStatus.REVOKED);

        DocumentInstanceDtos.IssueCorrectedResponse res = service.issueCorrectedOffer(
                prior.getId(),
                new DocumentInstanceDtos.IssueCorrectedRequest("REISSUE", null, null),
                caller);
        assertEquals("DRAFT", res.instance().status(),
                "an already-revoked prior has nothing left to revoke, so the "
                        + "gate must not stand between the intern and a "
                        + "corrected offer");
    }

    // ── (c) signatures never carry ───────────────────────────────────

    /**
     * (c) A signature row carries BOTH an image reference and the
     * signer's typed name in valueText. Filtering on "has text" would
     * carry the name across without the image — a name on a legal
     * document with no signature behind it. The filter is on
     * signatureDocumentId.
     */
    @Test
    void c_signatures_are_not_carried_even_though_they_hold_typed_names() {
        DocumentInstance prior = saveInstance(DocumentInstanceStatus.INTERN_SUBMITTED);
        stubValue(prior.getId(), F_DATE, "2026-01-01", null, "ERM");
        stubValue(prior.getId(), F_ERMSIG, "Alice ERM", UUID.randomUUID(), "ERM");
        stubValue(prior.getId(), F_INTSIG, "Bob Intern", UUID.randomUUID(), "INTERN");

        DocumentInstanceDtos.IssueCorrectedResponse res = service.issueCorrectedOffer(
                prior.getId(),
                new DocumentInstanceDtos.IssueCorrectedRequest("WRONG_DATE", null, null),
                caller);
        UUID newId = res.instance().id();

        assertTrue(values.findByInstanceIdAndFieldId(newId, F_ERMSIG).isEmpty(),
                "the ERM signature must not carry — the new offer is signed fresh");
        assertTrue(values.findByInstanceIdAndFieldId(newId, F_INTSIG).isEmpty(),
                "the intern signature must not carry, typed name included");
        assertEquals(1, res.carriedCount(),
                "only the non-signature answer carried");
        // And the prior keeps its signatures as history.
        assertTrue(values.findByInstanceIdAndFieldId(prior.getId(), F_ERMSIG).isPresent(),
                "the revoked prior must remain a faithful record of what was signed");
        assertTrue(values.findByInstanceIdAndFieldId(prior.getId(), F_INTSIG).isPresent());
    }

    // ── (d) template drift ───────────────────────────────────────────

    /** (d) The admin changed the template between the prior and now. A
     *  removed field's answer is dropped AND named; a type-changed
     *  field's answer is dropped; a newly-added field is simply empty. */
    @Test
    void d_template_drift_drops_and_names_the_answers_that_cannot_carry() {
        DocumentInstance prior = saveInstance(DocumentInstanceStatus.VERIFIED);
        stubValue(prior.getId(), F_DATE, "2026-01-01",   null, "ERM");
        stubValue(prior.getId(), F_ROLE, "Software Intern", null, "ERM");

        // F_ROLE removed entirely; F_DATE re-typed DATE -> TEXT; a new
        // field added that the prior never had.
        template = templates.findById(template.getId()).orElseThrow();
        template.setFieldSchemaJson(schemaJson(
                entry(F_DATE,   "Start Date", "TEXT",      "ERM"),   // type changed
                entry(F_AUTO,   "Full Name",  "TEXT",      "AUTO"),
                entry("field-new", "New Field", "TEXT",    "ERM"),   // added
                entry(F_ERMSIG, "ERM Sig",    "SIGNATURE", "ERM")));
        templates.saveAndFlush(template);

        DocumentInstanceDtos.IssueCorrectedResponse res = service.issueCorrectedOffer(
                prior.getId(),
                new DocumentInstanceDtos.IssueCorrectedRequest("TEMPLATE_MOVED", null, null),
                caller);
        UUID newId = res.instance().id();

        assertEquals(0, res.carriedCount(),
                "one removed + one type-changed = nothing survives");
        assertEquals(2, res.droppedFieldNames().size(),
                "both losses must be reported: " + res.droppedFieldNames());
        assertTrue(res.droppedFieldNames().contains("Role"),
                "a removed field is named so the ERM can re-check: "
                        + res.droppedFieldNames());
        assertTrue(res.droppedFieldNames().contains("Start Date"),
                "a type-changed field is named too: " + res.droppedFieldNames());
        assertTrue(values.findByInstanceIdAndFieldId(newId, F_ROLE).isEmpty());
        assertTrue(values.findByInstanceIdAndFieldId(newId, F_DATE).isEmpty());
        assertTrue(values.findByInstanceIdAndFieldId(newId, "field-new").isEmpty(),
                "a newly-added field starts empty for the ERM to fill");
    }

    // ── (e) atomicity ────────────────────────────────────────────────

    /**
     * (e) The guarantee that matters most. If the create leg fails after
     * the revoke leg has run, the whole transaction must roll back —
     * otherwise a signed offer is destroyed with nothing to replace it.
     *
     * <p>Forced with a realistic failure: an admin deactivated the
     * template between the offer being signed and the correction, which
     * {@code create()} rejects.</p>
     */
    @Test
    void e_a_failure_after_the_revoke_leg_rolls_the_whole_thing_back() {
        DocumentInstance prior = saveInstance(DocumentInstanceStatus.FINALIZED);
        stubValue(prior.getId(), F_DATE, "2026-01-01", null, "ERM");

        template = templates.findById(template.getId()).orElseThrow();
        template.setActive(false);
        templates.saveAndFlush(template);

        assertThrows(BadRequestException.class, () -> service.issueCorrectedOffer(
                prior.getId(),
                new DocumentInstanceDtos.IssueCorrectedRequest("WRONG_DATE", null, null),
                caller));

        DocumentInstance priorAfter = instances.findById(prior.getId()).orElseThrow();
        assertEquals(DocumentInstanceStatus.FINALIZED, priorAfter.getStatus(),
                "the revoke MUST roll back — a destroyed offer with no "
                        + "replacement is the worst outcome this feature has");
        assertEquals(1, instances.findAll().size(),
                "no orphan corrected offer may survive the rollback");
        assertEquals("2026-01-01",
                values.findByInstanceIdAndFieldId(prior.getId(), F_DATE)
                        .orElseThrow().getValueText());
    }

    // ── (f) the start-date gate ──────────────────────────────────────

    @Test
    void f_start_date_gate_blocks_issue_corrected_and_changes_nothing() {
        lifecycle.setStartedAt(Instant.now());
        lifecycles.saveAndFlush(lifecycle);
        DocumentInstance prior = saveInstance(DocumentInstanceStatus.FINALIZED);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.issueCorrectedOffer(
                        prior.getId(),
                        new DocumentInstanceDtos.IssueCorrectedRequest("WRONG_DATE", null, null),
                        caller));
        assertTrue(ex.getMessage().toLowerCase().contains("started"),
                "must surface the gate's own reason: " + ex.getMessage());

        assertEquals(DocumentInstanceStatus.FINALIZED,
                instances.findById(prior.getId()).orElseThrow().getStatus(),
                "a blocked gate must revoke nothing");
        assertEquals(1, instances.findAll().size(), "and create nothing");
    }

    // ── (g) one-live constraint ──────────────────────────────────────

    @Test
    void g_exactly_one_live_offer_remains_afterwards() {
        DocumentInstance prior = saveInstance(DocumentInstanceStatus.FINALIZED);

        DocumentInstanceDtos.IssueCorrectedResponse res = service.issueCorrectedOffer(
                prior.getId(),
                new DocumentInstanceDtos.IssueCorrectedRequest("WRONG_DATE", null, null),
                caller);

        List<DocumentInstance> live = instances.findAll().stream()
                .filter(d -> d.getInternLifecycleId().equals(lifecycle.getId()))
                .filter(d -> d.getTemplateId().equals(template.getId()))
                .filter(d -> !DocumentInstanceStatus.NOT_LIVE.contains(d.getStatus()))
                .toList();
        assertEquals(1, live.size(),
                "revoking the prior frees the one-live slot for exactly one "
                        + "replacement: " + live);
        assertEquals(res.instance().id(), live.get(0).getId());
    }

    // ── guard ────────────────────────────────────────────────────────

    @Test
    void guard_points_draft_and_sent_at_their_own_paths() {
        DocumentInstance draft = saveInstance(DocumentInstanceStatus.DRAFT);
        ConflictException d = assertThrows(ConflictException.class,
                () -> service.issueCorrectedOffer(draft.getId(),
                        new DocumentInstanceDtos.IssueCorrectedRequest("X", null, null), caller));
        assertTrue(d.getMessage().contains("draft"), d.getMessage());

        // A DRAFT already occupies the live slot, so use a fresh
        // lifecycle for the SENT case rather than tripping the unique.
        instances.deleteAll();
        DocumentInstance sent = saveInstance(DocumentInstanceStatus.SENT_TO_INTERN);
        ConflictException s = assertThrows(ConflictException.class,
                () -> service.issueCorrectedOffer(sent.getId(),
                        new DocumentInstanceDtos.IssueCorrectedRequest("X", null, null), caller));
        assertTrue(s.getMessage().contains("Correct & re-send"),
                "a sent-unsigned offer must be pointed at the cheaper "
                        + "same-record path: " + s.getMessage());
    }

    @Test
    void reason_code_is_required_because_the_intern_is_told() {
        DocumentInstance prior = saveInstance(DocumentInstanceStatus.FINALIZED);
        assertThrows(BadRequestException.class, () -> service.issueCorrectedOffer(
                prior.getId(),
                new DocumentInstanceDtos.IssueCorrectedRequest("  ", null, null), caller));
        assertEquals(DocumentInstanceStatus.FINALIZED,
                instances.findById(prior.getId()).orElseThrow().getStatus());
    }

    @Test
    void canErmIssueCorrected_tracks_state_and_the_gate() {
        DocumentInstance finalized = saveInstance(DocumentInstanceStatus.FINALIZED);
        DocumentInstanceDtos.InstanceActions a =
                service.getDetail(finalized.getId(), caller).actions();
        assertTrue(a.canErmIssueCorrected(), "offered on a signed offer");
        assertFalse(a.canErmCorrect(), "and NOT confused with correct-and-resend");

        // Gate closed → visible-but-disabled with the reason.
        lifecycle.setStartedAt(Instant.now());
        lifecycles.saveAndFlush(lifecycle);
        DocumentInstanceDtos.InstanceActions blocked =
                service.getDetail(finalized.getId(), caller).actions();
        assertFalse(blocked.canErmIssueCorrected());
        assertTrue(blocked.issueCorrectedBlockedReason() != null
                        && blocked.issueCorrectedBlockedReason().toLowerCase().contains("started"),
                "the ERM must see WHY, not a missing button: "
                        + blocked.issueCorrectedBlockedReason());
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
                .internLifecycleId(lifecycle.getId())
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

    private void stubValue(UUID instanceId, String fieldId, String valueText,
                           UUID signatureDocId, String role) {
        values.save(DocumentInstanceFieldValue.builder()
                .instanceId(instanceId)
                .fieldId(fieldId)
                .fieldName(fieldId)
                .valueText(valueText)
                .signatureDocumentId(signatureDocId)
                .filledByRole(role)
                .filledAt(Instant.now())
                .build());
    }
}
