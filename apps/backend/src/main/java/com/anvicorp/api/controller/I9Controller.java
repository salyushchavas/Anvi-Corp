package com.anvicorp.api.controller;

import com.anvicorp.api.dto.i9.I9FormResponse;
import com.anvicorp.api.dto.i9.I9HistoryEntryResponse;
import com.anvicorp.api.dto.i9.I9SummaryResponse;
import com.anvicorp.api.dto.i9.ReopenRequest;
import com.anvicorp.api.dto.i9.Section1Request;
import com.anvicorp.api.dto.i9.Section2Request;
import com.anvicorp.api.dto.common.PagedResponse;
import com.anvicorp.api.entity.I9Form;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.I9Status;
import com.anvicorp.api.service.I9FormService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/i9")
@RequiredArgsConstructor
public class I9Controller {

    private final I9FormService service;

    @GetMapping("/me")
    @PreAuthorize("hasRole('INTERN')")
    public I9FormResponse getMyForm(@AuthenticationPrincipal User user) {
        return service.toResponse(service.getMyForm(user));
    }

    @GetMapping("/candidate/{candidateId}")
    @PreAuthorize("hasRole('ERM')")
    public I9FormResponse getForCandidate(@PathVariable UUID candidateId,
                                          @AuthenticationPrincipal User user) {
        I9Form form = service.getOrCreateForCandidate(candidateId, user);
        return service.toResponse(form);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('INTERN', 'ERM', 'TRAINER')")
    public I9FormResponse getOne(@PathVariable UUID id,
                                 @AuthenticationPrincipal User user) {
        // Service-side requireReadAccess enforces candidate ownership; the
        // controller guard rejects unauthenticated requests up front.
        return service.toResponse(service.getById(id, user));
    }

    @GetMapping
    @PreAuthorize("hasRole('ERM')")
    public PagedResponse<I9SummaryResponse> list(
            @RequestParam(required = false) I9Status status,
            @RequestParam(defaultValue = "false") boolean overdueOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        return PagedResponse.of(service.list(status, overdueOnly, pageable));
    }

    // Candidate self-edit (APPLICANT / INTERN) + SUPER_ADMIN corrective bypass.
    // The OPERATIONS slot here was the post-§7-fold remnant of the original
    // hasAnyRole('CANDIDATE', 'ADMIN') gate — ADMIN→SUPER_ADMIN keeps the
    // bypass on the owner role only, off OPERATIONS.
    @PostMapping("/{id}/section1")
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public I9FormResponse saveSection1(@PathVariable UUID id,
                                       @Valid @RequestBody Section1Request req,
                                       @AuthenticationPrincipal User user) {
        return service.toResponse(service.saveSection1(id, req, user));
    }

    @PostMapping("/{id}/section2")
    @PreAuthorize("hasRole('ERM')")
    public I9FormResponse saveSection2(@PathVariable UUID id,
                                       @Valid @RequestBody Section2Request req,
                                       @AuthenticationPrincipal User user) {
        return service.toResponse(service.saveSection2(id, req, user));
    }

    // Reopening a COMPLETED I-9 breaks lifecycle immutability — owner-only,
    // not operational. Was hasRole('ADMIN') before the §7 fold.
    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public I9FormResponse reopen(@PathVariable UUID id,
                                 @Valid @RequestBody ReopenRequest req,
                                 @AuthenticationPrincipal User user) {
        return service.toResponse(service.reopenForm(id, req.getReason(), user));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('INTERN', 'ERM', 'TRAINER')")
    public List<I9HistoryEntryResponse> getHistory(@PathVariable UUID id,
                                                   @AuthenticationPrincipal User user) {
        return service.getHistory(id, user);
    }
}
