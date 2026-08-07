package com.anvicorp.api.erm.documents;

import com.anvicorp.api.auth.RegistrationRateLimiter;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.erm.documents.DocumentDtos.InternPacketView;
import com.anvicorp.api.erm.documents.DocumentDtos.InternTaskView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * ERM Phase 8.2 — intern-facing document HTTP surface. Templates are
 * served as Next.js static assets at {@code /document-templates/{slug}.pdf}
 * — the backend no longer streams them, so the old download endpoint
 * is gone. Upload is restricted to {@code application/pdf}.
 */
@RestController
@RequestMapping("/api/v1/intern/documents")
@RequiredArgsConstructor
public class InternDocumentController {

    private final InternDocumentService service;
    private final RegistrationRateLimiter rateLimiter;

    @GetMapping("/packet")
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public ResponseEntity<InternPacketView> getMyPacket(
            @AuthenticationPrincipal User caller) {
        return service.getMyPacket(caller)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping(value = "/tasks/{id}/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public InternTaskView upload(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User caller) {
        // Security Wave 3 — per-user upload throttle (10/user/min).
        rateLimiter.enforceAuthenticatedUpload(caller == null ? null : caller.getId());
        return service.uploadFilled(id, file, caller);
    }

    /**
     * Phase 1.6 — explicit intern handoff. Locks the packet against
     * further intern uploads and signals "ready for verification" to
     * ERM. Service-layer ownership check ensures the caller can only
     * submit their own packet.
     */
    @PostMapping("/packets/{packetId}/submit")
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public InternPacketView submitPacket(
            @PathVariable UUID packetId,
            @AuthenticationPrincipal User caller) {
        return service.submitToErm(packetId, caller);
    }
}
