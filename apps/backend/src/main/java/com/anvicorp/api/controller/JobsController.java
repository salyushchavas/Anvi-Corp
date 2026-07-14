package com.anvicorp.api.controller;

import com.anvicorp.api.dto.JobPostingCreateRequest;
import com.anvicorp.api.dto.JobPostingResponse;
import com.anvicorp.api.dto.JobPostingStatusUpdateRequest;
import com.anvicorp.api.dto.JobPostingUpdateRequest;
import com.anvicorp.api.dto.common.PagedResponse;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.JobPostingStatus;
import com.anvicorp.api.service.JobPostingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Phase 2 doc-spec Jobs API. Sits in parallel with the legacy
 * {@code /api/v1/job-postings} controller and delegates to the same
 * {@link JobPostingService}; the intern surface and public career page
 * call this path. The ERM dashboard (built in its own phase) keeps
 * calling the legacy path until it migrates.
 */
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobsController {

    private final JobPostingService jobPostingService;

    @GetMapping
    public PagedResponse<JobPostingResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal User user) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "publishedAt"));
        return PagedResponse.of(jobPostingService.listOpenForCandidate(user, pageable));
    }

    /**
     * Phase 2 — featured jobs for the public landing and the intern Home
     * "browse jobs" CTA. Latest six OPEN postings ordered by publish time.
     */
    @GetMapping("/featured")
    public List<JobPostingResponse> featured(@AuthenticationPrincipal User user) {
        Pageable pageable = PageRequest.of(0, 6,
                Sort.by(Sort.Direction.DESC, "publishedAt"));
        Page<JobPostingResponse> page = jobPostingService.listOpenForCandidate(user, pageable);
        return page.getContent();
    }

    @GetMapping("/{idOrSlug}")
    public JobPostingResponse getOne(@PathVariable String idOrSlug,
                                     @AuthenticationPrincipal User user) {
        return jobPostingService.findPublicByIdOrSlug(idOrSlug, user);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public ResponseEntity<JobPostingResponse> create(
            @Valid @RequestBody JobPostingCreateRequest req,
            @AuthenticationPrincipal User user) {
        JobPostingResponse created = jobPostingService.create(req, user);
        return ResponseEntity.created(URI.create("/api/v1/jobs/" + created.getId()))
                .body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public JobPostingResponse update(@PathVariable UUID id,
                                     @Valid @RequestBody JobPostingUpdateRequest req) {
        return jobPostingService.update(id, req);
    }

    /** DRAFT → OPEN. ERM-only. */
    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public JobPostingResponse publish(@PathVariable UUID id) {
        return jobPostingService.updateStatus(id, JobPostingStatus.OPEN);
    }

    /** OPEN/PAUSED → CLOSED. ERM-only. */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public JobPostingResponse close(@PathVariable UUID id) {
        return jobPostingService.updateStatus(id, JobPostingStatus.CLOSED);
    }

    /** Legacy-style PATCH for explicit status transitions. */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public JobPostingResponse updateStatus(@PathVariable UUID id,
                                           @Valid @RequestBody JobPostingStatusUpdateRequest req) {
        return jobPostingService.updateStatus(id, req.getStatus());
    }
}
