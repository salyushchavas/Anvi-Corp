package com.anvicorp.api.controller;

import com.anvicorp.api.dto.everify.CloseCaseRequest;
import com.anvicorp.api.dto.everify.CreateEVerifyCaseRequest;
import com.anvicorp.api.dto.everify.EVerifyCaseResponse;
import com.anvicorp.api.dto.everify.EVerifyCaseSummaryResponse;
import com.anvicorp.api.dto.everify.EVerifyHistoryEntryResponse;
import com.anvicorp.api.dto.everify.UpdateCaseRequest;
import com.anvicorp.api.dto.everify.UpdateStatusRequest;
import com.anvicorp.api.dto.common.PagedResponse;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.EVerifyStatus;
import com.anvicorp.api.service.EVerifyService;
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
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/everify")
@RequiredArgsConstructor
public class EVerifyController {

    private final EVerifyService service;

    @PostMapping
    @PreAuthorize("hasRole('ERM')")
    public ResponseEntity<EVerifyCaseResponse> create(
            @Valid @RequestBody CreateEVerifyCaseRequest req,
            @AuthenticationPrincipal User user) {
        EVerifyCaseResponse created = service.createCase(req, user);
        return ResponseEntity.created(URI.create("/api/v1/everify/" + created.getId()))
                .body(created);
    }

    @GetMapping
    @PreAuthorize("hasRole('ERM')")
    public PagedResponse<EVerifyCaseSummaryResponse> list(
            @RequestParam(required = false) EVerifyStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PagedResponse.of(service.list(status, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ERM')")
    public EVerifyCaseResponse getOne(@PathVariable UUID id) {
        return service.getById(id);
    }

    @GetMapping("/i9/{i9FormId}")
    @PreAuthorize("hasRole('ERM')")
    public EVerifyCaseResponse getByI9(@PathVariable UUID i9FormId) {
        return service.getByI9FormId(i9FormId);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ERM')")
    public EVerifyCaseResponse updateFields(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCaseRequest req,
            @AuthenticationPrincipal User user) {
        return service.updateFields(id, req, user);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ERM')")
    public EVerifyCaseResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateStatusRequest req,
            @AuthenticationPrincipal User user) {
        return service.updateStatus(id, req, user);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('ERM')")
    public EVerifyCaseResponse close(
            @PathVariable UUID id,
            @Valid @RequestBody CloseCaseRequest req,
            @AuthenticationPrincipal User user) {
        return service.closeCase(id, req, user);
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasRole('ERM')")
    public List<EVerifyHistoryEntryResponse> getHistory(@PathVariable UUID id) {
        return service.getHistory(id);
    }
}
