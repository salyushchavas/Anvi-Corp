package com.anvicorp.api.controller;

import com.anvicorp.api.dto.offer.CandidateOfferResponse;
import com.anvicorp.api.dto.offer.CreateOfferRequest;
import com.anvicorp.api.dto.offer.DeclineOfferRequest;
import com.anvicorp.api.dto.offer.OfferResponse;
import com.anvicorp.api.dto.offer.OfferSummaryResponse;
import com.anvicorp.api.dto.offer.UpdateOfferRequest;
import com.anvicorp.api.dto.common.PagedResponse;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.service.OfferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/offers")
@RequiredArgsConstructor
public class OfferController {

    /** Staff roles that get the full OfferResponse view. */
    private static final Set<UserRole> STAFF_ROLES = EnumSet.of(UserRole.ERM, UserRole.ERM);

    private final OfferService offerService;
    private final com.anvicorp.api.intern.OfferIdmsSigningService offerIdmsSigningService;

    @PostMapping
    @PreAuthorize("hasRole('ERM')")
    public ResponseEntity<OfferResponse> create(
            @Valid @RequestBody CreateOfferRequest req,
            @AuthenticationPrincipal User user) {
        OfferResponse created = offerService.create(req, user);
        return ResponseEntity.created(URI.create("/api/v1/offers/" + created.getId()))
                .body(created);
    }

    @GetMapping
    @PreAuthorize("hasRole('ERM')")
    public PagedResponse<OfferSummaryResponse> list(
            @RequestParam(required = false) OfferStatus status,
            @RequestParam(required = false) UUID applicationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PagedResponse.of(offerService.list(status, applicationId, pageable));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('INTERN')")
    public List<CandidateOfferResponse> listMine(@AuthenticationPrincipal User user) {
        return offerService.listForCandidate(user);
    }

    /** Phase 3 — doc-spec alias for {@code /me}. */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('INTERN')")
    public List<CandidateOfferResponse> listMineAlias(@AuthenticationPrincipal User user) {
        return offerService.listForCandidate(user);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('INTERN', 'ERM')")
    public Object getOne(@PathVariable UUID id,
                         @AuthenticationPrincipal User user) {
        // Candidate path returns the redacted view + enforces ownership in the
        // service; staff path returns the full offer. Controller guard keeps
        // unauthenticated requests out.
        if (isCandidateOnly(user)) {
            return offerService.getDetailCandidate(id, user);
        }
        return offerService.getDetailStaff(id, user);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ERM')")
    public OfferResponse update(@PathVariable UUID id,
                                @Valid @RequestBody UpdateOfferRequest req,
                                @AuthenticationPrincipal User user) {
        return offerService.update(id, req, user);
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasRole('ERM')")
    public OfferResponse send(@PathVariable UUID id,
                              @AuthenticationPrincipal User user) {
        return offerService.send(id, user);
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasRole('INTERN')")
    public CandidateOfferResponse accept(@PathVariable UUID id,
                                         @AuthenticationPrincipal User user) {
        Offer accepted = offerService.acceptInternal(id, user);
        return offerService.toCandidateResponse(accepted);
    }

    @PostMapping("/{id}/decline")
    @PreAuthorize("hasRole('INTERN')")
    public CandidateOfferResponse decline(@PathVariable UUID id,
                                          @Valid @RequestBody(required = false) DeclineOfferRequest req,
                                          @AuthenticationPrincipal User user) {
        Offer declined = offerService.declineInternal(id, req, user);
        return offerService.toCandidateResponse(declined);
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasRole('ERM')")
    public OfferResponse revoke(@PathVariable UUID id,
                                @AuthenticationPrincipal User user) {
        return offerService.revoke(id, user);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ERM')")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal User user) {
        offerService.delete(id, user);
        return ResponseEntity.noContent().build();
    }

    /**
     * GAP A6 — declarative coarse role gate. Row-level ownership and
     * visibility are still enforced inside OfferService.buildDownload (it
     * delegates to getDetailCandidate for candidate-only callers, which 404s
     * a non-owned or DRAFT offer; staff callers go through loadAndLazyExpire).
     * @PreAuthorize roles mirror the getOne endpoint above.
     */
    @GetMapping("/{id}/download")
    @PreAuthorize("hasAnyRole('INTERN', 'ERM')")
    public ResponseEntity<byte[]> download(@PathVariable UUID id,
                                           @AuthenticationPrincipal User user) {
        OfferService.LetterDownload payload = offerService.buildDownload(id, user);
        byte[] bytes = payload.body().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + payload.filename() + "\"")
                .contentType(MediaType.parseMediaType("text/plain; charset=utf-8"))
                .contentLength(bytes.length)
                .body(bytes);
    }

    private boolean isCandidateOnly(User user) {
        if (user == null || user.getRoles() == null) return false;
        boolean isCandidate = (user.getRoles().contains(UserRole.INTERN) || user.getRoles().contains(UserRole.INTERN));
        boolean isStaff = user.getRoles().stream().anyMatch(STAFF_ROLES::contains);
        return isCandidate && !isStaff;
    }

    // ── IDMS send / void / resend ──────────────────────────────────────────
    //
    // The legacy POST `/` (above) creates a DRAFT row for the pre-IDMS
    // manual flow and stays in place for backward compat. The canonical
    // "create + send via IDMS signing" flow is POST `/send`. The
    // applicant signs at /careers/intern/offer/sign/{id} via the
    // separate /api/v1/applicant/offers/{id}/sign endpoint.
    //
    // Removed in this rename:
    //   - GET /{id}/signing-url       (no embedded URL — applicant signs locally)
    //   - GET /{id}/signed-pdf        (no PDF archive — IDMS stores signature image)
    //   - POST /{id}/refresh-status   (no external system to reconcile with)

    @PostMapping("/send")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<CandidateOfferResponse> sendOffer(
            @Valid @RequestBody com.anvicorp.api.dto.offer.SendOfferRequest req,
            @AuthenticationPrincipal User actor) {
        Offer offer = offerIdmsSigningService.sendOffer(req, actor);
        // Return the candidate-shaped DTO so ERM tooling and intern frontend
        // can use the same shape; staff GET /{id} still returns the full
        // staff DTO via the existing endpoint.
        return ResponseEntity.created(URI.create("/api/v1/offers/" + offer.getId()))
                .body(offerService.toCandidateResponse(offer));
    }

    /** VOID an offer before it's signed. Refuses if SIGNED. */
    @PostMapping("/{id}/void")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public CandidateOfferResponse voidOffer(
            @PathVariable UUID id,
            @RequestBody java.util.Map<String, String> body,
            @AuthenticationPrincipal User actor) {
        String reason = body == null ? null : body.get("reason");
        Offer offer = offerIdmsSigningService.voidOffer(id, reason, actor);
        return offerService.toCandidateResponse(offer);
    }

    /** Re-send the OFFER_LETTER email so the applicant can reach the
     *  IDMS signing page again. Only SENT offers are eligible. */
    @PostMapping("/{id}/resend")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<Void> resend(@PathVariable UUID id,
                                       @AuthenticationPrincipal User actor) {
        offerIdmsSigningService.resendOffer(id, actor);
        return ResponseEntity.noContent().build();
    }
}
