package com.anvicorp.api.intern;

import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.OfferStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Applicant-side endpoints for the IDMS offer signing flow. Separate
 * from {@code OfferController} so the applicant signing page has a
 * stable URL surface dedicated to the IDMS sign action.
 *
 * <p>All endpoints require INTERN role and ownership of the offer.
 */
@RestController
@RequestMapping("/api/v1/applicant/offers")
@RequiredArgsConstructor
@Slf4j
public class ApplicantOfferController {

    private final OfferIdmsSigningService offerService;

    /** Payload for the signing page. Mirrors what the page needs to
     *  render — no internal_notes, no signing PIN, no PDF bytes. */
    public record ApplicantOfferView(
            UUID offerId,
            OfferStatus status,
            String applicantFullName,
            String applicantId,
            String applicantEmail,
            String roleTitle,
            String jobTitle,
            String compensationSummary,
            String worksite,
            Integer expectedHoursPerWeek,
            LocalDate tentativeStartDate,
            Instant expiresAt,
            Instant sentAt,
            Instant signedAt,
            String signedByTypedName,
            String signedSignatureImage,
            String letterContent,
            String contingencies
    ) {}

    /** Phase 8.6.2.1 — applicant draws a signature on a canvas; the page
     *  serialises it to a PNG data URL ({@code data:image/png;base64,...}).
     *  {@code typedName} is optional and only used as the printed-name
     *  override; the server falls back to the applicant's full name. */
    public record SignRequest(String typedName, String signatureImage) {}

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('INTERN')")
    public ApplicantOfferView get(@PathVariable UUID id,
                                  @AuthenticationPrincipal User caller) {
        Offer offer = offerService.loadForApplicant(id, caller);
        return toView(offer);
    }

    @PostMapping("/{id}/sign")
    @PreAuthorize("hasRole('INTERN')")
    public ApplicantOfferView sign(@PathVariable UUID id,
                                   @RequestBody SignRequest req,
                                   @AuthenticationPrincipal User caller) {
        String typed = req != null ? req.typedName() : null;
        String image = req != null ? req.signatureImage() : null;
        Offer signed = offerService.recordIdmsSignature(id, typed, image, caller);
        return toView(signed);
    }

    // ── Mapper ────────────────────────────────────────────────────────────

    private ApplicantOfferView toView(Offer offer) {
        Application app = offer.getApplication();
        Candidate cand = app != null ? app.getCandidate() : null;
        User u = cand != null ? cand.getUser() : null;
        JobPosting jp = app != null ? app.getJobPosting() : null;
        return new ApplicantOfferView(
                offer.getId(),
                offer.getStatus(),
                u != null ? u.getFullName() : null,
                u != null ? u.getApplicantId() : null,
                u != null ? u.getEmail() : null,
                offer.getRoleTitle(),
                jp != null ? jp.getTitle() : null,
                offer.getCompensationSummary(),
                offer.getWorksite(),
                offer.getExpectedHoursPerWeek(),
                offer.getStartDate(),
                offer.getExpiresAt(),
                offer.getSentAt(),
                offer.getSignedAt(),
                offer.getSignedByTypedName(),
                offer.getSignedSignatureImage(),
                offer.getLetterContent(),
                null  // contingencies are not stored as a discrete column today;
                      // they're inlined into letter_content at send time.
        );
    }
}
