package com.anvicorp.api.controller;

import com.anvicorp.api.dto.qa.QaSessionResponse;
import com.anvicorp.api.dto.qa.ReturnQaSessionRequest;
import com.anvicorp.api.dto.qa.ScheduleQaSessionRequest;
import com.anvicorp.api.dto.qa.SignOffRequest;
import com.anvicorp.api.dto.qa.UpdateConductedRequest;
import com.anvicorp.api.entity.QaSession;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.QaSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Q&amp;A session endpoints. Writes restricted to EVALUATOR,
 * REPORTING_MANAGER, or SUPER_ADMIN; reads also accept the trainer
 * and the intern themselves (service-level scoping).
 */
@RestController
@RequestMapping("/api/v1/qa-sessions")
@RequiredArgsConstructor
public class QaSessionController {

    private final QaSessionService qaSessionService;

    @PostMapping
    @PreAuthorize("hasAnyRole('EVALUATOR', 'REPORTING_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<QaSessionResponse> schedule(
            @Valid @RequestBody ScheduleQaSessionRequest req,
            @AuthenticationPrincipal User caller) {
        QaSession s = qaSessionService.schedule(
                req.projectId(), req.scheduledAt(), req.meetingLink(),
                req.durationMinutes(), req.timezone(), req.topic(), req.agenda(),
                caller);
        return ResponseEntity
                .created(URI.create("/api/v1/qa-sessions/" + s.getId()))
                .body(qaSessionService.toResponse(s));
    }

    @PatchMapping("/{id}/conducted")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'REPORTING_MANAGER', 'SUPER_ADMIN')")
    public QaSessionResponse updateConducted(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateConductedRequest req,
            @AuthenticationPrincipal User caller) {
        QaSession s = qaSessionService.updateConducted(
                id, req.questionsAsked(), req.internResponses(), caller);
        return qaSessionService.toResponse(s);
    }

    @PostMapping("/{id}/sign-off")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'REPORTING_MANAGER', 'SUPER_ADMIN')")
    public QaSessionResponse signOff(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) SignOffRequest req,
            @AuthenticationPrincipal User caller) {
        Integer marks = req != null ? req.marks() : null;
        String remarks = req != null ? req.remarks() : null;
        QaSession s = qaSessionService.signOff(id, marks, remarks, caller);
        return qaSessionService.toResponse(s);
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAnyRole('EVALUATOR', 'REPORTING_MANAGER', 'SUPER_ADMIN')")
    public QaSessionResponse returnForRevisions(
            @PathVariable UUID id,
            @Valid @RequestBody ReturnQaSessionRequest req,
            @AuthenticationPrincipal User caller) {
        QaSession s = qaSessionService.returnForRevisions(id, req.reason(), caller);
        return qaSessionService.toResponse(s);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public QaSessionResponse get(
            @PathVariable UUID id,
            @AuthenticationPrincipal User caller) {
        return qaSessionService.toResponse(qaSessionService.get(id, caller));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<QaSessionResponse> listByProject(
            @RequestParam("projectId") UUID projectId,
            @AuthenticationPrincipal User caller) {
        return qaSessionService.listByProject(projectId, caller).stream()
                .map(qaSessionService::toResponse)
                .toList();
    }
}
