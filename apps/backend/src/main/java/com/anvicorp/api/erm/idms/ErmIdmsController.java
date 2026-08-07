package com.anvicorp.api.erm.idms;

import com.anvicorp.api.admin.editabletemplates.EditableTemplate;
import com.anvicorp.api.admin.editabletemplates.EditableTemplateRepository;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.erm.offer.ErmOfferDtos;
import com.anvicorp.api.erm.offer.ErmOfferService;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IDMS Phase 2 — the ERM cockpit's HTTP surface.
 *
 * <p>Under {@code /api/v1/erm/idms} to keep it out of the legacy
 * {@code /api/v1/erm/offers} namespace but callable from the same page
 * (which was renamed "Offers / IDMS"). RBAC: ERM + SUPER_ADMIN on every
 * write path, matching {@code ErmOfferController}.</p>
 */
@RestController
@RequestMapping("/api/v1/erm/idms")
@RequiredArgsConstructor
@Slf4j
public class ErmIdmsController {

    private final DocumentInstanceService instanceService;
    private final EditableTemplateRepository templateRepo;
    private final InternLifecycleRepository lifecycleRepo;
    private final UserRepository userRepo;
    private final ErmOfferService ermOfferService;
    private final ObjectMapper objectMapper;

    // ── Queue (interns awaiting offer + in-flight instances) ──────────

    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentInstanceDtos.QueueResponse queue(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String month) {
        List<DocumentInstanceDtos.QueueRow> rows = new ArrayList<>();

        // (a) Every non-VOIDED instance the cockpit cares about.
        for (DocumentInstance i : instanceService.listForCockpit()) {
            User intern = userRepo.findById(i.getInternUserId()).orElse(null);
            var lc = lifecycleRepo.findById(i.getInternLifecycleId()).orElse(null);
            boolean canRevoke = i.getStatus() != null
                    && DocumentInstanceStatus.REVOCABLE.contains(i.getStatus())
                    && lc != null && lc.getStartedAt() == null;
            rows.add(new DocumentInstanceDtos.QueueRow(
                    i.getId(),
                    /*applicationId*/ null,
                    i.getInternLifecycleId(),
                    i.getInternUserId(),
                    intern != null ? intern.getFullName() : null,
                    intern != null ? intern.getEmail() : null,
                    i.getTemplateTitle(),
                    i.getTemplateKey(),
                    stageLabel(i.getStatus()),
                    i.getStatus() != null ? i.getStatus().name() : null,
                    i.getUpdatedAt(),
                    canRevoke,
                    /*canSupersede*/ i.getStatus() == DocumentInstanceStatus.FINALIZED));
        }

        // (b) Awaiting-offer bridge: interns who cleared the interview but
        // have no IDMS document yet. Reuses the existing offer-eligibility
        // query verbatim so the queue matches the legacy Awaiting-Offer tab.
        ErmOfferDtos.AwaitingOfferListPage awaiting =
                ermOfferService.listAwaitingOffer(search, 0, 100);
        for (ErmOfferDtos.AwaitingOfferRow a : awaiting.items()) {
            rows.add(new DocumentInstanceDtos.QueueRow(
                    /*instanceId*/ null,
                    a.applicationId(),
                    /*internLifecycleId*/ null,
                    /*internUserId*/ null,
                    a.applicantName(),
                    a.applicantEmail(),
                    /*templateTitle*/ null,
                    /*templateKey*/ null,
                    "Awaiting offer",
                    "AWAITING_OFFER",
                    a.interviewCompletedAt(),
                    /*canRevoke*/ false,
                    /*canSupersede*/ false));
        }

        // Sort by last activity DESC.
        rows.sort((x, y) -> {
            Instant xa = x.lastActivityAt() == null ? Instant.EPOCH : x.lastActivityAt();
            Instant ya = y.lastActivityAt() == null ? Instant.EPOCH : y.lastActivityAt();
            return ya.compareTo(xa);
        });

        // Optional client-side-ish search filter.
        if (search != null && !search.isBlank()) {
            String q = search.trim().toLowerCase();
            rows.removeIf(r -> {
                String hay = ((r.internName() == null ? "" : r.internName()) + " "
                        + (r.internEmail() == null ? "" : r.internEmail()) + " "
                        + (r.templateTitle() == null ? "" : r.templateTitle()))
                        .toLowerCase();
                return !hay.contains(q);
            });
        }

        return new DocumentInstanceDtos.QueueResponse(rows, rows.size());
    }

    private static String stageLabel(DocumentInstanceStatus s) {
        if (s == null) return "";
        return switch (s) {
            case DRAFT -> "Draft";
            case SENT_TO_INTERN -> "Sent";
            case INTERN_SUBMITTED -> "Signed — verifying";
            case RETURNED -> "Returned";
            case VERIFIED -> "Verified";
            case FINALIZED -> "Executed";
            case REVOKED -> "Revoked";
            case SUPERSEDED -> "Replaced";
            case VOIDED -> "Voided";
        };
    }

    // ── Template picker (LIVE templates from the studio) ─────────────

    @GetMapping("/templates")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentInstanceDtos.PickableTemplateList pickableTemplates() {
        List<EditableTemplate> rows = templateRepo.findAllByOrderBySortOrderAscTitleAsc();
        List<DocumentInstanceDtos.PickableTemplate> items = new ArrayList<>();
        for (EditableTemplate t : rows) {
            // "LIVE" per the task = active + has both source + saved schema.
            if (!t.isActive()) continue;
            if (t.getCanonicalHtml() == null || t.getFieldSchemaJson() == null) continue;
            int count = 0;
            try {
                List<?> parsed = objectMapper.readValue(t.getFieldSchemaJson(), List.class);
                count = parsed.size();
            } catch (Exception ignored) { /* count stays 0 */ }
            items.add(new DocumentInstanceDtos.PickableTemplate(
                    t.getId(), t.getKey(), t.getTitle(), t.getDescription(), count));
        }
        return new DocumentInstanceDtos.PickableTemplateList(items);
    }

    // ── Create + detail + list per lifecycle ─────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentInstanceDtos.InstanceDetail create(
            @jakarta.validation.Valid @RequestBody DocumentInstanceDtos.CreateInstanceRequest req,
            @AuthenticationPrincipal User caller) {
        return instanceService.create(req, caller);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentInstanceDtos.InstanceDetail get(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return instanceService.getDetailErmView(id, caller);
    }

    @GetMapping("/lifecycle/{lifecycleId}/history")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public Map<String, Object> historyForLifecycle(
            @PathVariable UUID lifecycleId,
            @AuthenticationPrincipal User caller) {
        List<DocumentInstance> instances = instanceService.listForLifecycle(lifecycleId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DocumentInstance i : instances) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i.getId());
            row.put("title", i.getTemplateTitle());
            row.put("key", i.getTemplateKey());
            row.put("status", i.getStatus());
            row.put("stage", stageLabel(i.getStatus()));
            row.put("createdAt", i.getCreatedAt());
            row.put("finalizedAt", i.getFinalizedAt());
            row.put("revokedAt", i.getRevokedAt());
            row.put("supersedesId", i.getSupersedesId());
            row.put("finalPdfDocumentId", i.getFinalPdfDocumentId());
            rows.add(row);
        }
        return Map.of("items", rows);
    }

    // ── State transitions ────────────────────────────────────────────

    @PostMapping("/{id}/fill")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentInstanceDtos.InstanceDetail fill(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody DocumentInstanceDtos.FillFieldsRequest req,
            @AuthenticationPrincipal User caller) {
        return instanceService.fillFields(id, req, caller);
    }

    @PostMapping("/{id}/sign")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentInstanceDtos.InstanceDetail sign(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody DocumentInstanceDtos.SignFieldRequest req,
            @AuthenticationPrincipal User caller) {
        return instanceService.signField(id, req, caller);
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentInstanceDtos.InstanceDetail send(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody(required = false) DocumentInstanceDtos.SendRequest req,
            @AuthenticationPrincipal User caller) {
        return instanceService.send(id, req, caller);
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentInstanceDtos.InstanceDetail verify(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody(required = false) DocumentInstanceDtos.VerifyRequest req,
            @AuthenticationPrincipal User caller) {
        return instanceService.verify(id, req, caller);
    }

    @PostMapping("/{id}/finalize")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentInstanceDtos.InstanceDetail finalizeInstance(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody(required = false) DocumentInstanceDtos.FinalizeRequest req,
            @AuthenticationPrincipal User caller) {
        return instanceService.finalize(id, req, caller);
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentInstanceDtos.InstanceDetail returnDoc(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody DocumentInstanceDtos.ReturnRequest req,
            @AuthenticationPrincipal User caller) {
        return instanceService.returnForCorrections(id, req, caller);
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public DocumentInstanceDtos.InstanceDetail revoke(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody DocumentInstanceDtos.RevokeRequest req,
            @AuthenticationPrincipal User caller) {
        return instanceService.revoke(id, req, caller);
    }

    /**
     * Stream the finalized PDF as {@code application/pdf} with a proper
     * {@code Content-Disposition: attachment; filename=...} — the vault
     * stores the object under a {@code .bin} storage key and the PII-
     * encrypted branch means a plain S3 presign returns the AES envelope
     * not the PDF. This endpoint reads through
     * {@link DocumentInstanceService#readFinalPdfBytes} (which uses
     * {@code DocumentVaultService.readDocument} — auto-decrypts) and
     * emits the plaintext bytes with the right headers so the browser
     * saves it as {@code "<template> - <intern>.pdf"} and opens as a PDF.
     */
    @GetMapping("/{id}/final-pdf")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> downloadFinalPdf(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        DocumentInstanceService.FinalPdfPayload payload =
                instanceService.readFinalPdfBytes(id, caller);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE,
                        "application/pdf")
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        com.anvicorp.api.common.ContentDispositionFilenames
                                .forFilename("attachment", payload.suggestedFilename()))
                .body(payload.bytes());
    }

    /** Convenience — 204 wrapper for delete-esque flows if callers want it. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> notSupported(@PathVariable UUID id) {
        // No hard-delete in IDMS Phase 2 — every state must remain visible
        // in history. SUPER_ADMIN can force VOIDED only via a future admin
        // surface; the delete verb 405s here to prevent accidents.
        return ResponseEntity.status(405).build();
    }
}
