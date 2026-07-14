package com.anvicorp.api.erm.offer;

import com.anvicorp.api.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ERM Phase 4 — offer control HTTP surface. */
@RestController
@RequestMapping("/api/v1/erm/offers")
@RequiredArgsConstructor
public class ErmOfferController {

    private final ErmOfferService ermOfferService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.OfferListPage list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID applicationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        return ermOfferService.list(status, search, applicationId, page, pageSize);
    }

    /** Phase 8.6 — queue of applications in INTERVIEWED+SELECTED with no
     *  active outstanding offer. Powers the Decision Center page and the
     *  Awaiting Offer tab on /careers/erm/offers. */
    @GetMapping("/awaiting")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.AwaitingOfferListPage awaiting(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        return ermOfferService.listAwaitingOffer(search, page, pageSize);
    }

    @GetMapping("/reason-codes")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public List<ErmOfferDtos.ReasonCodeGroup> reasonCodes(
            @RequestParam(required = false) String family) {
        return ermOfferService.listReasonCodes(family);
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.PreviewResponse preview(
            @RequestBody ErmOfferDtos.CreateOfferRequest req,
            @AuthenticationPrincipal User caller) {
        return ermOfferService.preview(req, caller);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.OfferDetail detail(@PathVariable UUID id) {
        return ermOfferService.getDetail(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.OfferDetail create(
            @RequestBody ErmOfferDtos.CreateOfferRequest req,
            @AuthenticationPrincipal User caller) {
        return ermOfferService.createAndSend(req, caller);
    }

    @PostMapping("/{id}/resend")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.OfferDetail resend(
            @PathVariable UUID id,
            @RequestBody ErmOfferDtos.ResendRequest req,
            @AuthenticationPrincipal User caller) {
        return ermOfferService.resend(id, req, caller);
    }

    @PostMapping("/{id}/reminder")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.OfferDetail reminder(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return ermOfferService.sendReminder(id, caller);
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.OfferDetail voidOffer(
            @PathVariable UUID id,
            @RequestBody ErmOfferDtos.VoidRequest req,
            @AuthenticationPrincipal User caller) {
        return ermOfferService.voidOffer(id, req, caller);
    }

    @PostMapping("/{id}/clear-for-reoffer")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.OfferDetail clearForReoffer(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return ermOfferService.clearForReoffer(id, caller);
    }

    @PostMapping("/{id}/internal-note")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> internalNote(
            @PathVariable UUID id,
            @RequestBody ErmOfferDtos.InternalNoteRequest req,
            @AuthenticationPrincipal User caller) {
        ermOfferService.appendInternalNote(id, req != null ? req.note() : null, caller);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/update-start-date")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ErmOfferDtos.OfferDetail updateStartDate(
            @PathVariable UUID id,
            @RequestBody ErmOfferDtos.UpdateStartDateRequest req,
            @AuthenticationPrincipal User caller) {
        return ermOfferService.updateStartDate(id,
                req != null ? req.newDate() : null, caller);
    }
}
