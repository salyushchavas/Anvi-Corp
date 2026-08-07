package com.anvicorp.api.controller;

import com.anvicorp.api.dto.ResumeResponse;
import com.anvicorp.api.entity.Resume;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.service.ResumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
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

@RestController
@RequestMapping("/api/v1/resumes")
@RequiredArgsConstructor
@Slf4j
public class ResumeController {

    private final ResumeService resumeService;
    private final ApplicationRepository applicationRepository;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('INTERN')")
    public ResponseEntity<ResumeResponse> upload(@RequestParam("file") MultipartFile file,
                                                 @AuthenticationPrincipal User user) {
        ResumeResponse created = resumeService.upload(user, file);
        return ResponseEntity.created(URI.create("/api/v1/resumes/" + created.getId()))
                .body(created);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('INTERN')")
    public List<ResumeResponse> listMine(@AuthenticationPrincipal User user) {
        return resumeService.listForUser(user.getId());
    }

    /**
     * GAP A6 — declarative coarse role gate. ensureCanDownload below is the
     * row-level gate (owner check for candidates, "linked to an application
     * I can see" check for staff). The two layers together: @PreAuthorize
     * rejects unauth + wrong-role principals up front; the in-handler check
     * enforces ownership / scope.
     *
     * <p>MANAGER + SUPER_ADMIN are included because the Manager Hire
     * Approvals screen renders the candidate's resume inline alongside
     * the ERM-submitted scorecard. The row-level check still gates the
     * actual file by "linked to an application visible to me".</p>
     */
    @GetMapping("/{id}/download")
    @PreAuthorize("hasAnyRole('INTERN', 'ERM', 'MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<Resource> download(@PathVariable UUID id,
                                              @AuthenticationPrincipal User user) {
        Resume resume = resumeService.loadEntity(id);
        ensureCanDownload(resume, user);
        // GAP E6 — sensitive PII event. Audit BEFORE serving the file so a
        // serve-time IO failure can't dissolve the audit record (audit-first).
        resumeService.recordDownloadAudit(resume, user);
        // Phase B — loadFile now returns Resource (interface) so the
        // dual-resolver can return either a FileSystemResource (volume)
        // or a ByteArrayResource (S3) transparently.
        Resource resource = resumeService.loadFile(resume);

        String contentType = resume.getContentType() != null
                ? resume.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        String fileName = resume.getFileName() != null ? resume.getFileName() : "resume";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sanitizeFileName(fileName) + "\"")
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .body(resource);
    }

    @PatchMapping("/{id}/default")
    @PreAuthorize("hasRole('INTERN')")
    public ResumeResponse setDefault(@PathVariable UUID id,
                                     @AuthenticationPrincipal User user) {
        return resumeService.setDefault(user.getId(), id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('INTERN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal User user) {
        resumeService.delete(user.getId(), id, true);
        return ResponseEntity.noContent().build();
    }

    private void ensureCanDownload(Resume resume, User caller) {
        // SUPER_ADMIN always bypasses — operational override.
        boolean superAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (superAdmin) return;

        boolean isOwner = resume.getCandidate() != null
                && resume.getCandidate().getUser() != null
                && resume.getCandidate().getUser().getId().equals(caller.getId());
        if (isOwner) return;

        // Staff roles that may view a candidate's resume when the row is
        // linked to an application they can see. ERM has org-wide review
        // scope by design; MANAGER's read is gated by the same
        // "linked-to-any-application" check (there's no manager_id FK on
        // JobPosting to further narrow to a specific manager's roster —
        // MANAGER is an organization-level oversight role per the
        // canonical design mirrored in ManagerTimesheetApprovalService).
        boolean isErm = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.ERM);
        boolean isManager = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.MANAGER);
        if ((isErm || isManager)
                && applicationRepository.existsByResumeId(resume.getId())) {
            return;
        }

        // Anti-enumeration: same 404 a bogus id would produce. Log WARN
        // so operators can spot probing.
        log.warn("[IDOR-guard] resume.download caller={} resource={} reason={}",
                caller.getId(), resume.getId(),
                (isErm || isManager)
                        ? "resume-not-linked-to-any-application"
                        : "caller-not-owner-and-not-privileged");
        throw new ResourceNotFoundException("Resume not found: " + resume.getId());
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\r\\n\"]", "_");
    }
}
