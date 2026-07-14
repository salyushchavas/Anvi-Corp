package com.anvicorp.api.controller;

import com.anvicorp.api.dto.supervised.AssignmentResponse;
import com.anvicorp.api.dto.supervised.CreateAssignmentRequest;
import com.anvicorp.api.dto.supervised.ReviewAssignmentRequest;
import com.anvicorp.api.dto.supervised.SubmitAssignmentRequest;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.WorkAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/supervised")
@RequiredArgsConstructor
public class WorkAssignmentController {

    private final WorkAssignmentService workAssignmentService;

    @PostMapping("/interns/{candidateId}/assignments")
    @PreAuthorize("hasAnyRole('ERM', 'TRAINER')")
    public ResponseEntity<AssignmentResponse> create(
            @PathVariable UUID candidateId,
            @Valid @RequestBody CreateAssignmentRequest req,
            @AuthenticationPrincipal User caller) {
        AssignmentResponse created = workAssignmentService.create(candidateId, req, caller);
        return ResponseEntity.created(URI.create("/api/v1/supervised/assignments/" + created.getId()))
                .body(created);
    }

    @GetMapping("/interns/{candidateId}/assignments")
    @PreAuthorize("hasAnyRole('ERM', 'TRAINER')")
    public List<AssignmentResponse> listForIntern(@PathVariable UUID candidateId) {
        return workAssignmentService.listForIntern(candidateId);
    }

    // Legacy intern-callable assignment endpoints — superseded by the
    // ProjectAssignment flow per the Applicant-to-Intern Lifecycle doc.
    // Kept on disk through Phase 0 so any in-flight clients keep working;
    // marked @Deprecated and removed in a subsequent phase.

    @Deprecated
    @GetMapping("/my/assignments")
    @PreAuthorize("hasRole('INTERN')")
    public List<AssignmentResponse> listMine(@AuthenticationPrincipal User caller) {
        return workAssignmentService.listForCandidateUser(caller);
    }

    @Deprecated
    @PostMapping("/assignments/{id}/start")
    @PreAuthorize("hasRole('INTERN')")
    public AssignmentResponse start(@PathVariable UUID id,
                                    @AuthenticationPrincipal User caller) {
        return workAssignmentService.start(id, caller);
    }

    @Deprecated
    @PostMapping("/assignments/{id}/submit")
    @PreAuthorize("hasRole('INTERN')")
    public AssignmentResponse submit(@PathVariable UUID id,
                                     @Valid @RequestBody SubmitAssignmentRequest req,
                                     @AuthenticationPrincipal User caller) {
        return workAssignmentService.submit(id, req, caller);
    }

    @PostMapping("/assignments/{id}/review")
    @PreAuthorize("hasAnyRole('ERM', 'TRAINER')")
    public AssignmentResponse review(@PathVariable UUID id,
                                     @Valid @RequestBody ReviewAssignmentRequest req,
                                     @AuthenticationPrincipal User caller) {
        return workAssignmentService.review(id, req, caller);
    }
}
