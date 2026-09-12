package com.anvicorp.api.erm.idms;

import com.anvicorp.api.admin.editabletemplates.EditableTemplate;
import com.anvicorp.api.admin.editabletemplates.EditableTemplateRepository;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SALUTATION field type — the backend whitelist check inside
 * {@link DocumentInstanceService} that closes the crafted-client
 * gap on the fixed-choice honorific field. The frontend renders a
 * dropdown of Mr./Ms./Mx./Dr. but a curl / Postman POST could still
 * ship any string; this suite proves the service rejects off-list
 * values with 400 and accepts every allowed value.
 *
 * <p>Mirrors the shape of the sibling IDMS ITs — {@code @SpringBootTest}
 * + H2 in Postgres compat mode (JSONB), service-layer exercise, hand-
 * built fixtures via Lombok builders. Uses {@code fillFields} for the
 * DRAFT case (the only status where ERM writes are permitted; the
 * broader validation guarantees are covered by
 * {@code DocumentInstanceReopenTemplateIT} + {@code
 * DocumentInstanceResyncTemplateIT}).</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // MODE=PostgreSQL required for JSONB columnDefinition on
        // DocumentInstance / EditableTemplate / AuditLog — same
        // rationale as the sibling ITs.
        "spring.datasource.url=jdbc:h2:mem:anvi_idms_salut;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "app.webmail.seed.admin-enabled=false"
})
class DocumentInstanceSalutationIT {

    @Autowired DocumentInstanceService service;
    @Autowired DocumentInstanceRepository instances;
    @Autowired DocumentInstanceFieldValueRepository values;
    @Autowired DocumentInstanceReviewLogRepository reviewLogs;
    @Autowired EditableTemplateRepository templates;
    @Autowired UserRepository users;
    @Autowired ObjectMapper objectMapper;

    private User caller;
    private User intern;
    private EditableTemplate template;

    private static final String FIELD_SALUT_ID = "field-salut";

    @BeforeEach
    void seed() {
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
                .key("salutation_test_template")
                .title("Salutation Test Template")
                .description("v1")
                .active(true)
                .sortOrder(500)
                .createdById(caller.getId())
                .canonicalHtml("<p>Dear <span class=\"doc-field\" "
                        + "data-field-id=\"" + FIELD_SALUT_ID
                        + "\">Mr.</span> Smith,</p>")
                .fieldSchemaJson(schemaJson(
                        "{\"id\":\"" + FIELD_SALUT_ID + "\","
                                + "\"name\":\"Salutation\","
                                + "\"type\":\"SALUTATION\","
                                + "\"assignee\":\"ERM\","
                                + "\"required\":true}"))
                .build());
    }

    /** POST /fill with value="Sir" on a SALUTATION field → 400. The
     *  crafted-client gap the whitelist closes. */
    @Test
    void fill_with_off_list_value_is_rejected_with_400() {
        DocumentInstance inst = saveDraft();
        DocumentInstanceDtos.FillFieldsRequest req =
                new DocumentInstanceDtos.FillFieldsRequest(
                        Map.of(FIELD_SALUT_ID, "Sir"),
                        /*expectedUpdatedAt*/ null);
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.fillFields(inst.getId(), req, caller));
        assertTrue(ex.getMessage().toLowerCase().contains("salutation"),
                "error should name the salutation constraint: " + ex.getMessage());
        // Value was not saved.
        assertTrue(values.findByInstanceIdAndFieldId(inst.getId(), FIELD_SALUT_ID).isEmpty(),
                "rejected fill must not create a value row");
    }

    /** POST /fill with value="Ms." on a SALUTATION field → accepted,
     *  stored as valueText verbatim. */
    @Test
    void fill_with_allowed_value_is_stored_as_valueText() {
        DocumentInstance inst = saveDraft();
        DocumentInstanceDtos.FillFieldsRequest req =
                new DocumentInstanceDtos.FillFieldsRequest(
                        Map.of(FIELD_SALUT_ID, "Ms."),
                        /*expectedUpdatedAt*/ null);
        service.fillFields(inst.getId(), req, caller);

        DocumentInstanceFieldValue v = values
                .findByInstanceIdAndFieldId(inst.getId(), FIELD_SALUT_ID)
                .orElseThrow(() -> new AssertionError(
                        "expected a value row after accepted fill"));
        assertEquals("Ms.", v.getValueText(),
                "salutation value should be persisted verbatim in valueText");
    }

    /** All four allowed values pass. Guards against a typo drift
     *  between the frontend {@code SALUTATIONS} constant and the
     *  backend {@code ALLOWED_SALUTATIONS} whitelist — the two must
     *  stay in lockstep. */
    @Test
    void every_allowed_salutation_is_accepted() {
        for (String value : List.of("Mr.", "Ms.", "Mx.", "Dr.")) {
            DocumentInstance inst = saveDraft();
            DocumentInstanceDtos.FillFieldsRequest req =
                    new DocumentInstanceDtos.FillFieldsRequest(
                            Map.of(FIELD_SALUT_ID, value),
                            null);
            service.fillFields(inst.getId(), req, caller);
            DocumentInstanceFieldValue v = values
                    .findByInstanceIdAndFieldId(inst.getId(), FIELD_SALUT_ID)
                    .orElseThrow(() -> new AssertionError(
                            "value \"" + value + "\" was rejected"));
            assertEquals(value, v.getValueText());
            // Clean up between iterations so the next instance is fresh.
            values.deleteAll();
            instances.deleteAll();
        }
    }

    /** Blank / absent salutation value passes through the whitelist —
     *  required-ness is enforced by the completeness check that runs
     *  at Send time, not by applyFieldValues. */
    @Test
    void blank_salutation_value_is_not_rejected_by_the_whitelist() {
        DocumentInstance inst = saveDraft();
        DocumentInstanceDtos.FillFieldsRequest req =
                new DocumentInstanceDtos.FillFieldsRequest(
                        Map.of(FIELD_SALUT_ID, ""),
                        null);
        // Should not throw — the whitelist only fires on non-blank
        // off-list values.
        service.fillFields(inst.getId(), req, caller);
    }

    // ── Helpers ─────────────────────────────────────────────────

    private String schemaJson(String entryJson) {
        return "[" + entryJson + "]";
    }

    private DocumentInstance saveDraft() {
        return instances.save(DocumentInstance.builder()
                .templateId(template.getId())
                .internLifecycleId(UUID.randomUUID())
                .internUserId(intern.getId())
                .createdByErmId(caller.getId())
                .status(DocumentInstanceStatus.DRAFT)
                .version(1)
                .internLocked(false)
                .templateTitle(template.getTitle())
                .templateKey(template.getKey())
                .snapshotCanonicalHtml(template.getCanonicalHtml())
                .snapshotFieldSchemaJson(template.getFieldSchemaJson())
                .snapshotTemplateUpdatedAt(template.getUpdatedAt())
                .build());
    }
}
