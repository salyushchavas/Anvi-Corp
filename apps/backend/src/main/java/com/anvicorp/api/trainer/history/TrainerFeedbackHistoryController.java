package com.anvicorp.api.trainer.history;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.trainer.history.TrainerFeedbackHistoryDtos.HistoryDetail;
import com.anvicorp.api.trainer.history.TrainerFeedbackHistoryDtos.HistoryPage;
import com.anvicorp.api.trainer.history.TrainerFeedbackHistoryDtos.InternTimeline;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/** Trainer Phase 4 — Feedback History HTTP surface (read-only). */
@RestController
@RequestMapping("/api/v1/trainer/feedback-history")
@RequiredArgsConstructor
public class TrainerFeedbackHistoryController {

    private final TrainerFeedbackHistoryService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public HistoryPage list(
            @RequestParam(required = false) UUID internLifecycleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            @AuthenticationPrincipal User caller) {
        return service.list(internLifecycleId, from, to, decision, search,
                page, pageSize, caller);
    }

    @GetMapping("/{submissionId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public HistoryDetail get(@PathVariable UUID submissionId,
                              @AuthenticationPrincipal User caller) {
        return service.getDetail(submissionId, caller);
    }

    @GetMapping("/intern/{internLifecycleId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public InternTimeline internTimeline(
            @PathVariable UUID internLifecycleId,
            @AuthenticationPrincipal User caller) {
        return service.getInternTimeline(internLifecycleId, caller);
    }
}
