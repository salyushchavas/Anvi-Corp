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
 * Weekly narrative reports — the second piece of the Phase-2 weekly cycle.
 *
 * <h2>Roles per endpoint</h2>
 * <ul>
 *   <li>Intern commands (create / update / submit-via-update / list own):
 *       {@code INTERN} only. APPLICANT can't satisfy the service-level
 *       active-engagement gate either way.</li>
 *   <li>Supervisor commands (read intern roster / return / approve):
 *       {@code TECHNICAL_EVALUATOR} or {@code SUPER_ADMIN}. The service
 *       layer additionally checks that a TECHNICAL_EVALUATOR owns the
 *       intern's engagement; SUPER_ADMIN bypasses ownership.</li>
 * </ul>
 *
 * <h2>APPROVED lock</h2>
 * Once a report is APPROVED, PUT returns 409 and the return/approve
 * endpoints become idempotent no-ops.
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

    // ── Supervisor commands ─────────────────────────────────────────────────

    /** All reports for a candidate the caller supervises. */
    @GetMapping("/intern/{candidateId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public List<WeeklyReportResponse> listForIntern(
            @PathVariable UUID candidateId,
            @AuthenticationPrincipal User user) {
        return service.listForCandidate(candidateId, user);
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public WeeklyReportResponse returnForCorrection(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewWeeklyReportRequest req,
            @AuthenticationPrincipal User user) {
        return service.returnForCorrection(id, req, user);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('TRAINER', 'SUPER_ADMIN')")
    public WeeklyReportResponse approve(
            @PathVariable UUID id,
            @RequestBody(required = false) ReviewWeeklyReportRequest req,
            @AuthenticationPrincipal User user) {
        return service.approve(id, req, user);
    }

    // ── Attachment (optional supporting file) ───────────────────────────────

    /**
     * Intern uploads (or replaces) the optional supporting file on their
     * own report. Multipart POST — reuses the standard document upload
     * pattern (10 MB cap via {@code spring.servlet.multipart.max-file-size}).
     * Allow-list: {@code application/pdf}, {@code application/msword},
     * {@code application/vnd.openxmlformats-officedocument.wordprocessingml.document}.
     */
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
     * Stream the attachment for viewing / download. Intern-owner OR the
     * assigned supervisor (TRAINER) OR SUPER_ADMIN — enforced in the
     * service so the intern's own view uses the same URL the reviewer
     * hits. Content-Disposition is {@code inline} so a PDF renders in
     * the browser tab; {@code filename} carries the original name for
     * "Save as…".
     */
    @GetMapping("/{id}/attachment")
    @PreAuthorize("hasAnyRole('INTERN', 'TRAINER', 'SUPER_ADMIN')")
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

    /**
     * Intern clears the attachment on their own report. Idempotent —
     * no-op when nothing is attached. APPROVED-lock refuses (409).
     */
    @DeleteMapping("/{id}/attachment")
    @PreAuthorize("hasRole('INTERN')")
    public WeeklyReportResponse deleteAttachment(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        return service.deleteAttachment(id, user);
    }
}
