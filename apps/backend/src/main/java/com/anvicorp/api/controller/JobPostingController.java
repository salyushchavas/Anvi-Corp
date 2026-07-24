package com.anvicorp.api.controller;

import com.anvicorp.api.dto.JobPostingCreateRequest;
import com.anvicorp.api.dto.JobPostingResponse;
import com.anvicorp.api.dto.JobPostingStatusUpdateRequest;
import com.anvicorp.api.dto.JobPostingUpdateRequest;
import com.anvicorp.api.dto.common.PagedResponse;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.JobPostingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/job-postings")
@RequiredArgsConstructor
public class JobPostingController {

    private final JobPostingService jobPostingService;

    @GetMapping
    public PagedResponse<JobPostingResponse> listOpen(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal User user) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "publishedAt"));
        // When the caller is an authenticated CANDIDATE the service decorates
        // each posting with their applied state in a single extra query.
        // Public/non-candidate callers go through the plain listOpen path.
        return PagedResponse.of(jobPostingService.listOpenForCandidate(user, pageable));
    }

    /**
     * Admin / ERM postings list. Optional filters: {@code search} (title or
     * description, case-insensitive), {@code status}, {@code entityId}.
     * Response items carry {@code applicantCount} so the admin table can
     * surface counts without an extra round-trip.
     */
    @GetMapping("/admin/all")
    @PreAuthorize("hasAnyRole('ERM', 'JOBS_ADMIN')")
    public PagedResponse<JobPostingResponse> listAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) com.anvicorp.api.enums.JobPostingStatus status,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PagedResponse.of(
                jobPostingService.listAllForAdmin(search, status, entityId, pageable));
    }

    @GetMapping("/admin/{idOrSlug}")
    @PreAuthorize("hasAnyRole('ERM', 'JOBS_ADMIN')")
    public JobPostingResponse getOneForAdmin(@PathVariable String idOrSlug) {
        return jobPostingService.findAnyByIdOrSlug(idOrSlug);
    }

    @GetMapping("/{idOrSlug}")
    public JobPostingResponse getOne(@PathVariable String idOrSlug,
                                     @AuthenticationPrincipal User user) {
        return jobPostingService.findPublicByIdOrSlug(idOrSlug, user);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ERM', 'JOBS_ADMIN')")
    public ResponseEntity<JobPostingResponse> create(
            @Valid @RequestBody JobPostingCreateRequest req,
            @AuthenticationPrincipal User user) {
        JobPostingResponse created = jobPostingService.create(req, user);
        return ResponseEntity.created(URI.create("/api/v1/job-postings/" + created.getId()))
                .body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ERM', 'JOBS_ADMIN')")
    public JobPostingResponse update(@PathVariable UUID id,
                                     @Valid @RequestBody JobPostingUpdateRequest req) {
        return jobPostingService.update(id, req);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ERM', 'JOBS_ADMIN')")
    public JobPostingResponse updateStatus(@PathVariable UUID id,
                                           @Valid @RequestBody JobPostingStatusUpdateRequest req) {
        return jobPostingService.updateStatus(id, req.getStatus());
    }
}
