package com.anvicorp.api.controller;

import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.intern.DocumentVaultService;
import com.anvicorp.api.repository.InternLifecycleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 4 document-vault REST surface. Distinct from the older
 * {@code DocumentController} aggregator endpoints. Binds at
 * {@code /api/v1/documents} and powers the intern Documents page plus the
 * ERM "intern vault" view.
 */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Slf4j
public class DocumentVaultController {

    private final DocumentVaultService vault;
    private final InternLifecycleRepository internLifecycleRepository;

    @GetMapping("/mine")
    @PreAuthorize("hasRole('INTERN')")
    public List<Map<String, Object>> mine(@AuthenticationPrincipal User caller) {
        return vault.listForOwner(caller.getId()).stream()
                .map(DocumentVaultController::toMetadata)
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('INTERN', 'ERM', 'MANAGER', 'SUPER_ADMIN')")
    public Map<String, Object> metadata(@PathVariable UUID id,
                                         @AuthenticationPrincipal User caller) {
        return toMetadata(vault.loadMetadataForOwner(id, caller));
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("hasAnyRole('INTERN', 'ERM', 'MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<byte[]> content(@PathVariable UUID id,
                                           @AuthenticationPrincipal User caller) {
        Document doc = vault.loadMetadataForOwner(id, caller);
        byte[] bytes = vault.readDocument(id, caller);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sanitize(doc.getFileName()) + "\"")
                .contentType(MediaType.parseMediaType(
                        doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream"))
                .contentLength(bytes.length)
                .body(bytes);
    }

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasRole('INTERN')")
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", defaultValue = "OTHER") String category,
            @AuthenticationPrincipal User caller) throws java.io.IOException {
        Document doc = vault.saveDocument(
                caller.getId(),
                file.getOriginalFilename(),
                file.getContentType(),
                file.getBytes(),
                category,
                DocumentVaultService.defaultSensitivityForCategory(category),
                caller.getId());
        return toMetadata(doc);
    }

    @GetMapping("/vault/intern/{userId}")
    @PreAuthorize("hasAnyRole('ERM', 'MANAGER', 'SUPER_ADMIN')")
    public List<Map<String, Object>> internVault(@PathVariable UUID userId,
                                                  @AuthenticationPrincipal User caller) {
        ensureCanViewVaultOr404(userId, caller);
        return vault.listForOwner(userId).stream()
                .map(DocumentVaultController::toMetadata)
                .toList();
    }

    /**
     * IDOR guard for the intern-vault listing. Scope rules:
     *
     * <ul>
     *   <li>SUPER_ADMIN — always allowed.</li>
     *   <li>ERM — allowed org-wide (matches audit's broad-read pattern
     *       for staff observability).</li>
     *   <li>MANAGER — allowed only when the target intern's lifecycle
     *       {@code manager_id} equals the caller's id. Falls back to the
     *       null-slot convention (matches TrainerScopeGuard /
     *       EvaluatorScopeGuard) so single-org setups aren't locked out.</li>
     *   <li>Anything else — 404.</li>
     * </ul>
     *
     * <p>Unowned MANAGER access throws {@link ResourceNotFoundException}
     * — no 403 — so cross-manager probing can't confirm the vault
     * exists.</p>
     */
    private void ensureCanViewVaultOr404(UUID targetUserId, User caller) {
        if (caller == null) {
            throw new ResourceNotFoundException("Not found");
        }
        boolean isSuperAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (isSuperAdmin) return;
        boolean isErm = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.ERM);
        if (isErm) return;
        boolean isManager = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.MANAGER);
        if (!isManager) {
            // The @PreAuthorize already limits to these three roles, so
            // this branch is defense-in-depth.
            throw new ResourceNotFoundException("Not found");
        }
        InternLifecycle lc = targetUserId != null
                ? internLifecycleRepository.findByUserId(targetUserId).orElse(null)
                : null;
        // Null lifecycle / null manager_id → single-org fallback: any
        // MANAGER is de-facto owner. Matches the null-FK semantics used
        // by TrainerScopeGuard and EvaluatorScopeGuard.
        if (lc == null || lc.getManagerId() == null) return;
        if (lc.getManagerId().equals(caller.getId())) return;
        log.warn("[IDOR-guard] internVault caller={} resource={} lifecycle={} reason=not-assigned-manager",
                caller.getId(), targetUserId, lc.getId());
        throw new ResourceNotFoundException(
                "Intern vault not found for user " + targetUserId);
    }

    private static Map<String, Object> toMetadata(Document doc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", doc.getId());
        m.put("ownerUserId", doc.getOwnerUserId());
        m.put("fileName", doc.getFileName());
        m.put("fileSize", doc.getFileSize());
        m.put("mimeType", doc.getMimeType());
        m.put("category", doc.getCategory());
        m.put("sensitivity", doc.getSensitivity());
        m.put("encrypted", doc.getEncryptionMetadataJson() != null);
        m.put("createdAt", doc.getCreatedAt());
        return m;
    }

    private static String sanitize(String s) {
        if (s == null) return "document.bin";
        return s.replaceAll("[\\r\\n\"\\\\]", "_");
    }
}
