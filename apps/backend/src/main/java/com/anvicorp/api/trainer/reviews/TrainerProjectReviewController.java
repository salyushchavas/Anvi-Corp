package com.anvicorp.api.trainer.reviews;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.trainer.reviews.TrainerProjectReviewDtos.PendingPage;
import com.anvicorp.api.trainer.reviews.TrainerProjectReviewDtos.SubmissionDetail;
import com.anvicorp.api.trainer.reviews.TrainerProjectReviewDtos.SubmitFeedbackRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Trainer Phase 3 — Pending Reviews HTTP surface. */
@RestController
@RequestMapping("/api/v1/trainer/pending-reviews")
@RequiredArgsConstructor
public class TrainerProjectReviewController {

    private final TrainerProjectReviewService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public PendingPage listPending(
            @RequestParam(required = false) UUID internLifecycleId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize,
            @AuthenticationPrincipal User caller) {
        return service.listPending(internLifecycleId, search, page, pageSize, caller);
    }

    @GetMapping("/{submissionId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public SubmissionDetail get(
            @PathVariable UUID submissionId,
            @AuthenticationPrincipal User caller) {
        return service.getDetail(submissionId, caller);
    }

    @PostMapping("/{submissionId}/feedback")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public SubmissionDetail submitFeedback(
            @PathVariable UUID submissionId,
            @RequestBody SubmitFeedbackRequest req,
            @AuthenticationPrincipal User caller) {
        return service.submitFeedback(submissionId, req, caller);
    }
}
