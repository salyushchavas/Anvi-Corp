package com.anvicorp.api.controller;

import com.anvicorp.api.dto.report.CreateWeeklyReportRequest;
import com.anvicorp.api.dto.report.ReviewWeeklyReportRequest;
import com.anvicorp.api.dto.report.UpdateWeeklyReportRequest;
import com.anvicorp.api.dto.report.WeeklyReportResponse;
import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.service.WeeklyReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Weekly narrative reports — second piece of the Phase-2 weekly cycle.
 *
 * <h2>Two-stage review (mirrors the timesheet flow)</h2>
 * {@code SUBMITTED → VERIFIED} lives on
 * {@link com.anvicorp.api.erm.report.ErmWeeklyReportController} ({@code ERM}
 * + {@code SUPER_ADMIN}). {@code VERIFIED → APPROVED} lives here on the
 * {@link #approve} endpoint ({@code MANAGER} + {@code SUPER_ADMIN}).
 * Return-for-correction is available at both stages.
 *
 * <h2>Roles per endpoint</h2>
 * <ul>
 *   <li>Intern commands (create / update / submit-via-update / list own):
 *       {@code INTERN} only.</li>
 *   <li>Reviewer read (list an intern's reports): {@code ERM},
 *       {@code MANAGER}, {@code EVALUATOR}, {@code SUPER_ADMIN}, or the
 *       assigned Trainer supervisor — enforced by the service layer.
 *       EVALUATOR retains read access so the Evaluator still sees an
 *       intern's history when preparing a monthly / final evaluation.</li>
 *   <li>Approve (VERIFIED → APPROVED): {@code MANAGER} +
 *       {@code SUPER_ADMIN}. Requires the ERM to have verified first.</li>
 *   <li>Return-for-correction (VERIFIED → RETURNED, approver-stage):
 *       {@code MANAGER} + {@code SUPER_ADMIN}. The ERM-stage return
 *       (SUBMITTED → RETURNED) lives on
 *       {@link com.anvicorp.api.erm.report.ErmWeeklyReportController}.</li>
 * </ul>
 *
 * <h2>APPROVED lock</h2>
 * Once a report is APPROVED, PUT returns 409 and every reviewer action
 * becomes an idempotent no-op.
 */
@RestController
@RequestMapping("/api/v1/weekly-reports")
@RequiredArgsConstructor
public class WeeklyReportController {

    private final WeeklyReportService service;

    // ── Intern commands ─────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasRole('INTERN')")
    public ResponseEntity<WeeklyReportResponse> create(
            @Valid @RequestBody CreateWeeklyReportRequest req,
            @AuthenticationPrincipal User user) {
        WeeklyReportResponse created = service.create(req, user);
        return ResponseEntity.created(URI.create("/api/v1/weekly-reports/" + created.getId()))
                .body(created);
    }

    /**
     * Edit a report. Pass {@code submit: true} in the body to also transition
     * DRAFT or RETURNED → SUBMITTED in the same call. APPROVED reports are
     * locked — returns 409 with a "start a new week's report" pointer.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('INTERN')")
    public WeeklyReportResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateWeeklyReportRequest req,
            @AuthenticationPrincipal User user) {
        return service.update(id, req, user);
    }

    /** This intern's own reports, newest week first. Status agnostic. */
    @GetMapping("/me")
    @PreAuthorize("hasRole('INTERN')")
    public List<WeeklyReportResponse> listForMe(@AuthenticationPrincipal User user) {
        return service.listForMe(user);
    }

    // ── Reviewer commands ───────────────────────────────────────────────────

    /**
     * All reports for a candidate the caller has read-access to. Role /
     * ownership enforcement is inside the service — any ERM / EVALUATOR /
     * SUPER_ADMIN sees any intern, plus the assigned Trainer supervisor
     * for their own roster.
     */
    @GetMapping("/intern/{candidateId}")
    @PreAuthorize("hasAnyRole('ERM', 'MANAGER', 'EVALUATOR', 'TRAINER', 'SUPER_ADMIN')")
    public List<WeeklyReportResponse> listForIntern(
            @PathVariable UUID candidateId,
            @AuthenticationPrincipal User user) {
        return service.listForCandidate(candidateId, user);
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public WeeklyReportResponse returnForCorrection(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewWeeklyReportRequest req,
            @AuthenticationPrincipal User user) {
        return service.returnFromManager(id, req, user);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('MANAGER', 'SUPER_ADMIN')")
    public WeeklyReportResponse approve(
            @PathVariable UUID id,
            @RequestBody(required = false) ReviewWeeklyReportRequest req,
            @AuthenticationPrincipal User user) {
        return service.approve(id, req, user);
    }

    // ── Attachment (optional supporting file) ───────────────────────────────

    @PostMapping(value = "/{id}/attachment",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('INTERN')")
    public WeeklyReportResponse uploadAttachment(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User user) {
        return service.uploadAttachment(id, file, user);
    }

    /**
     * Stream the attachment for viewing / download. Intern-owner OR any
     * ERM / EVALUATOR / SUPER_ADMIN OR the assigned Trainer supervisor —
     * enforced by the service.
     */
    @GetMapping("/{id}/attachment")
    @PreAuthorize("hasAnyRole('INTERN', 'ERM', 'EVALUATOR', 'TRAINER', 'SUPER_ADMIN')")
    public ResponseEntity<ByteArrayResource> downloadAttachment(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        WeeklyReportService.AttachmentPayload payload = service.readAttachment(id, user);
        Document doc = payload.document();
        String mime = doc.getMimeType() != null
                ? doc.getMimeType() : "application/octet-stream";
        String name = doc.getFileName() != null
                ? doc.getFileName().replace("\"", "") : "weekly-report";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + name + "\"")
                .contentLength(payload.bytes().length)
                .body(new ByteArrayResource(payload.bytes()));
    }

    @DeleteMapping("/{id}/attachment")
    @PreAuthorize("hasRole('INTERN')")
    public WeeklyReportResponse deleteAttachment(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        return service.deleteAttachment(id, user);
    }
}
