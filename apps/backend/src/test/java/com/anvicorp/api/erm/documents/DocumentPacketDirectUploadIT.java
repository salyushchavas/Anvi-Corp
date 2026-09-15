package com.anvicorp.api.erm.documents;

import com.anvicorp.api.admin.onboardingtemplates.OnboardingDocumentTemplate;
import com.anvicorp.api.admin.onboardingtemplates.OnboardingDocumentTemplateRepository;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.DocumentPacket;
import com.anvicorp.api.entity.DocumentTask;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.DocumentPacketRepository;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.DocumentTaskRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.multipart.MultipartFile;

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
 * ERM direct-upload of a missed document — the direct-onboarded-hire-only
 * path that attaches a finished file as an ACCEPTED task on the intern's
 * existing packet, with no intern step and no email nudge.
 *
 * <p>Guardrails proven by this suite (from the parent design):</p>
 * <ul>
 *   <li>G1 — non-direct hires 403.</li>
 *   <li>G3 — the Document row is invisible to the intern gallery on its
 *       own; both a Document AND a companion ACCEPTED DocumentTask are
 *       persisted (the presence + shape of the task row is the proof).</li>
 *   <li>G4 — duplicate documentKey on the same packet 409s.</li>
 *   <li>Missing packet 409 (not 404 — a direct hire without a packet =
 *       broken onboarding to surface, not silently paper over).</li>
 *   <li>The {@code toPacketDetail} DTO flag {@code internDirectOnboarded}
 *       correctly reads the {@code tos_version} marker.</li>
 * </ul>
 *
 * <p>Uses the H2 in-memory Postgres-compat DB the sibling document ITs
 * (idms/resync/reopen) already use. Repos are wired directly; no MockMvc —
 * the service layer is what enforces the guards.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:anvi_packet_direct;"
                + "DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "app.webmail.seed.admin-enabled=false"
})
class DocumentPacketDirectUploadIT {

    @Autowired DocumentPacketService service;
    @Autowired DocumentPacketRepository packets;
    @Autowired DocumentTaskRepository tasks;
    @Autowired DocumentRepository documents;
    @Autowired OnboardingDocumentTemplateRepository templates;
    @Autowired InternLifecycleRepository lifecycles;
    @Autowired UserRepository users;
    @Autowired AuditLogRepository audits;

    private User erm;
    private User directIntern;
    private User regularIntern;
    private InternLifecycle directLifecycle;
    private InternLifecycle regularLifecycle;
    private DocumentPacket directPacket;
    private DocumentPacket regularPacket;
    private OnboardingDocumentTemplate template;

    /** The doc-key we upload in the happy path — a template row is
     *  required for {@code requireTemplate} to resolve
     *  category/sensitivity, so we seed one. */
    private static final String DOC_KEY_W4 = "W4_TEST";

    @BeforeEach
    void seed() {
        // Order: audits → tasks → packets → documents → lifecycles →
        // users → templates. Peel from the leaves outward.
        audits.deleteAll();
        tasks.deleteAll();
        packets.deleteAll();
        documents.deleteAll();
        lifecycles.deleteAll();
        users.deleteAll();
        templates.deleteAll();

        erm = users.save(User.builder()
                .email("erm-caller@anvicorp.com")
                .fullName("ERM Caller")
                .roles(new HashSet<>(Set.of(UserRole.SUPER_ADMIN)))
                .tosVersion("2026-05-27")
                .build());
        // Direct hire — the EMPLOYER_REGISTERED literal is the flag.
        directIntern = users.save(User.builder()
                .email("direct-intern@anvicorp.com")
                .fullName("Direct Intern")
                .roles(new HashSet<>(Set.of(UserRole.INTERN)))
                .tosVersion("EMPLOYER_REGISTERED")
                .build());
        // Regular / platform hire — dated ToS literal.
        regularIntern = users.save(User.builder()
                .email("regular-intern@anvicorp.com")
                .fullName("Regular Intern")
                .roles(new HashSet<>(Set.of(UserRole.INTERN)))
                .tosVersion("2026-05-27")
                .build());

        directLifecycle = lifecycles.save(minimalLifecycle(directIntern.getId(),
                "EMP-DIRECT-001"));
        regularLifecycle = lifecycles.save(minimalLifecycle(regularIntern.getId(),
                "EMP-REGULAR-001"));

        // Direct-hire packet — mirrors DirectOnboardingService Step 9's
        // shape (status=COMPLETED, no intern step ever needed).
        directPacket = packets.save(DocumentPacket.builder()
                .internLifecycleId(directLifecycle.getId())
                .assignedById(erm.getId())
                .status("COMPLETED")
                .assignedAt(Instant.now())
                .allSubmittedAt(Instant.now())
                .completedAt(Instant.now())
                .customInstructions("Direct-onboard test packet")
                .build());
        // Regular-hire packet — mirrors assignPacket shape.
        regularPacket = packets.save(DocumentPacket.builder()
                .internLifecycleId(regularLifecycle.getId())
                .assignedById(erm.getId())
                .status("ASSIGNED")
                .assignedAt(Instant.now())
                .customInstructions("Regular test packet")
                .build());

        template = templates.save(OnboardingDocumentTemplate.builder()
                .key(DOC_KEY_W4)
                .title("Federal W-4 (test)")
                .category("TAX")
                .sensitivity("FINANCIAL")
                .documentType("TEMPLATE")
                .active(true)
                .build());
    }

    // ────────────────────────────────────────────────────────────
    // (a) Happy path — direct hire, new doc key → 1 Document + 1 ACCEPTED task
    // ────────────────────────────────────────────────────────────

    @Test
    void a_direct_hire_upload_creates_document_and_accepted_task() {
        MultipartFile file = pdfFile("w4-signed.pdf");
        DocumentDtos.DocumentPacketDetail detail = service.directUploadDocument(
                directPacket.getId(), DOC_KEY_W4, file, erm);

        assertNotNull(detail, "returned packet detail should be non-null");
        // Packet status untouched (G2).
        assertEquals("COMPLETED",
                packets.findById(directPacket.getId()).orElseThrow().getStatus(),
                "G2: direct upload must NOT flip packet status");

        // Exactly one Document row + one DocumentTask row on this packet.
        List<DocumentTask> pkTasks = tasks.findByPacketIdOrderByCreatedAtAsc(directPacket.getId());
        assertEquals(1, pkTasks.size(), "expected one task after direct upload");
        DocumentTask task = pkTasks.get(0);
        assertEquals("ACCEPTED", task.getStatus(),
                "G3: task is born ACCEPTED (not PENDING) so it renders "
                        + "as completed on the intern gallery");
        assertEquals(DOC_KEY_W4, task.getDocumentKey());
        assertNotNull(task.getUploadedFileId(),
                "G3: task must carry uploaded_file_id linking the Document row");
        assertNotNull(task.getSubmittedAt());
        assertNotNull(task.getReviewedAt());
        assertEquals(erm.getId(), task.getReviewedById());

        Document doc = documents.findById(task.getUploadedFileId()).orElseThrow();
        assertEquals(directIntern.getId(), doc.getOwnerUserId(),
                "Document owner is the intern");
        assertEquals(erm.getId(), doc.getUploadedById(),
                "Document uploaded_by is the ERM caller");
        assertEquals("TAX", doc.getCategory());

        // Audit row landed with the right action + linkage.
        List<AuditLog> row = audits.findAll();
        assertTrue(row.stream().anyMatch(
                        a -> "DOCUMENT_TASK_DIRECT_UPLOAD".equals(a.getAction())
                                && task.getId().equals(a.getEntityId())
                                && directIntern.getId().equals(a.getSubjectUserId())),
                "audit row missing or mis-linked: " + row);
    }

    // ────────────────────────────────────────────────────────────
    // (b) G1 — regular hire → 403
    // ────────────────────────────────────────────────────────────

    @Test
    void b_regular_hire_upload_is_rejected_with_403() {
        MultipartFile file = pdfFile("w4-attempt.pdf");
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> service.directUploadDocument(
                        regularPacket.getId(), DOC_KEY_W4, file, erm));
        assertTrue(ex.getMessage().toLowerCase().contains("direct-onboarded"),
                "message should name the direct-onboarded constraint: "
                        + ex.getMessage());
        // Nothing landed.
        assertTrue(tasks.findByPacketIdOrderByCreatedAtAsc(regularPacket.getId()).isEmpty(),
                "no task row must be created on a rejected direct-upload");
        assertTrue(documents.findAll().isEmpty(),
                "no Document row must be persisted on a rejected direct-upload");
    }

    // ────────────────────────────────────────────────────────────
    // (c) G4 — duplicate documentKey on same packet → 409
    // ────────────────────────────────────────────────────────────

    @Test
    void c_duplicate_document_key_is_rejected_with_409() {
        // Seed a first upload so a task with DOC_KEY_W4 already exists on
        // the direct packet.
        service.directUploadDocument(directPacket.getId(), DOC_KEY_W4,
                pdfFile("first.pdf"), erm);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.directUploadDocument(
                        directPacket.getId(), DOC_KEY_W4,
                        pdfFile("second.pdf"), erm));
        assertTrue(ex.getMessage().toLowerCase().contains("already"),
                "message should say the doc is already on the packet: "
                        + ex.getMessage());
        // Only ONE task row exists on the packet.
        assertEquals(1,
                tasks.findByPacketIdOrderByCreatedAtAsc(directPacket.getId()).size(),
                "duplicate upload must NOT create a second task row");
    }

    // ────────────────────────────────────────────────────────────
    // (d) Missing packet → 409 (per the ruling, not 404)
    // ────────────────────────────────────────────────────────────

    @Test
    void d_missing_packet_is_rejected_with_409() {
        UUID unknownPacketId = UUID.randomUUID();
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.directUploadDocument(
                        unknownPacketId, DOC_KEY_W4, pdfFile("orphan.pdf"), erm));
        assertTrue(ex.getMessage().toLowerCase().contains("no document packet")
                        || ex.getMessage().toLowerCase().contains("did not complete"),
                "message should hint the packet is missing: " + ex.getMessage());
        // No auto-created packet.
        assertNull(packets.findById(unknownPacketId).orElse(null),
                "endpoint must NOT auto-create the missing packet");
    }

    // ────────────────────────────────────────────────────────────
    // (e) toPacketDetail — the internDirectOnboarded flag is honest
    // ────────────────────────────────────────────────────────────

    @Test
    void e_toPacketDetail_flag_reflects_tos_version() {
        DocumentDtos.DocumentPacketDetail direct = service.getPacket(directPacket.getId(), erm);
        assertTrue(direct.internDirectOnboarded(),
                "direct hire (tos_version=EMPLOYER_REGISTERED) must have "
                        + "internDirectOnboarded=true");

        DocumentDtos.DocumentPacketDetail regular = service.getPacket(regularPacket.getId(), erm);
        assertFalse(regular.internDirectOnboarded(),
                "regular hire (dated tos_version) must have "
                        + "internDirectOnboarded=false");
    }

    // ── Helpers ─────────────────────────────────────────────────

    private MockMultipartFile pdfFile(String name) {
        return new MockMultipartFile("file", name, "application/pdf",
                new byte[]{0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34});  // "%PDF-1.4"
    }

    private InternLifecycle minimalLifecycle(UUID userId, String employeeId) {
        Instant now = Instant.now();
        return InternLifecycle.builder()
                .userId(userId)
                .employeeId(employeeId)
                .activeStatus(InternLifecycleStatus.ONBOARDING_ASSIGNED.name())
                .hiredAt(now)
                .reportingStructureComplete(false)
                .build();
    }
}
