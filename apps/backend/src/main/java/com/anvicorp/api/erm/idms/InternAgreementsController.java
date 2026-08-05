package com.anvicorp.api.erm.idms;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * IDMS Phase 2 — the intern-side HTTP surface for the Agreements
 * dashboard. The service enforces per-field ownership + state-machine
 * guards; this controller just wires the routes.
 */
@RestController
@RequestMapping("/api/v1/intern/agreements")
@RequiredArgsConstructor
public class InternAgreementsController {

    private final DocumentInstanceService instanceService;

    @GetMapping
    @PreAuthorize("hasRole('INTERN')")
    public Map<String, Object> list(@AuthenticationPrincipal User caller) {
        if (caller == null) throw new ForbiddenException("Sign in first.");
        List<DocumentInstance> mine = instanceService.listForIntern(caller.getId());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DocumentInstance i : mine) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i.getId());
            row.put("title", i.getTemplateTitle());
            row.put("status", i.getStatus().name());
            row.put("stage", stageLabel(i.getStatus()));
            row.put("sentAt", i.getSentAt());
            row.put("returnedAt", i.getReturnedAt());
            row.put("finalizedAt", i.getFinalizedAt());
            row.put("revokedAt", i.getRevokedAt());
            row.put("updatedAt", i.getUpdatedAt());
            row.put("hasReturnComments",
                    i.getReturnReasonCode() != null || i.getReturnComments() != null);
            rows.add(row);
        }
        return Map.of("items", rows);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('INTERN')")
    public DocumentInstanceDtos.InstanceDetail get(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return instanceService.getDetail(id, caller);
    }

    @PostMapping("/{id}/fill")
    @PreAuthorize("hasRole('INTERN')")
    public DocumentInstanceDtos.InstanceDetail fill(
            @PathVariable UUID id,
            @RequestBody DocumentInstanceDtos.FillFieldsRequest req,
            @AuthenticationPrincipal User caller) {
        return instanceService.fillFields(id, req, caller);
    }

    @PostMapping("/{id}/sign")
    @PreAuthorize("hasRole('INTERN')")
    public DocumentInstanceDtos.InstanceDetail sign(
            @PathVariable UUID id,
            @RequestBody DocumentInstanceDtos.SignFieldRequest req,
            @AuthenticationPrincipal User caller) {
        return instanceService.signField(id, req, caller);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('INTERN')")
    public DocumentInstanceDtos.InstanceDetail submit(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return instanceService.internSubmit(id, caller);
    }

    private static String stageLabel(DocumentInstanceStatus s) {
        if (s == null) return "";
        return switch (s) {
            case DRAFT -> "Being prepared";
            case SENT_TO_INTERN -> "Awaiting your signature";
            case INTERN_SUBMITTED -> "With your ERM";
            case RETURNED -> "Changes requested";
            case VERIFIED -> "With your ERM";
            case FINALIZED -> "Executed";
            case REVOKED -> "Revoked";
            case SUPERSEDED -> "Replaced";
            case VOIDED -> "Voided";
        };
    }
}
