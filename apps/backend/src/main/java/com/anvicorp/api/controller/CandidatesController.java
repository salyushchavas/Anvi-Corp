package com.anvicorp.api.controller;

import com.anvicorp.api.dto.candidates.CandidateDetailResponse;
import com.anvicorp.api.dto.candidates.CandidateListItemResponse;
import com.anvicorp.api.dto.common.PagedResponse;
import com.anvicorp.api.service.CandidatesService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Staff-side read endpoints for browsing candidate profiles. Candidates use the
 * /me endpoints elsewhere; this is purely for recruiter/ERM/HR/admin access.
 */
@RestController
@RequestMapping("/api/v1/candidates")
@RequiredArgsConstructor
public class CandidatesController {

    private final CandidatesService candidatesService;

    @GetMapping
    @PreAuthorize("hasRole('ERM')")
    public PagedResponse<CandidateListItemResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return candidatesService.list(search, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ERM')")
    public CandidateDetailResponse detail(@PathVariable UUID id) {
        return candidatesService.detail(id);
    }
}
