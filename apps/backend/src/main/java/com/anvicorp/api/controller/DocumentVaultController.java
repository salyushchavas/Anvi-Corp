package com.anvicorp.api.controller;

import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.intern.DocumentVaultService;
import lombok.RequiredArgsConstructor;
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
public class DocumentVaultController {

    private final DocumentVaultService vault;

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
    public List<Map<String, Object>> internVault(@PathVariable UUID userId) {
        return vault.listForOwner(userId).stream()
                .map(DocumentVaultController::toMetadata)
                .toList();
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
