package com.anvicorp.api.erm.idms;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.intern.DocumentVaultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * IDMS signature-bytes endpoint — the single authorised gateway for
 * fetching a signature PNG for render in any of the five IDMS surfaces
 * (ERM fill preview, intern fill preview, ERM verify page, read-only
 * detail/history, and by extension anywhere else the document renderer
 * paints a signed anchor).
 *
 * <h2>Why this exists</h2>
 * Signatures are saved with sensitivity={@code PII} → the vault wraps
 * bytes in an AES-256-GCM envelope before writing to S3. A direct
 * browser fetch via an S3 presigned URL therefore returns the
 * encrypted envelope, not a valid PNG — which is the "durable broken
 * image" symptom this endpoint fixes. Only the server can decrypt, so
 * signature bytes must flow through the server.
 *
 * <h2>Authorisation</h2>
 * Caller must be the owning intern (matches
 * {@code DocumentInstance.internUserId}) OR any ERM / SUPER_ADMIN.
 * Both are legitimate viewers of any signature on their own instance
 * (intern sees the ERM's signature, ERM sees the intern's). No cross-
 * instance leakage: we look up the field-value by
 * {@code (instanceId, fieldId)} so a caller can't request another
 * instance's document id even if they know it.
 *
 * <h2>Response</h2>
 * {@code Content-Type: image/png}, {@code Cache-Control: private,
 * no-store} (PII bytes — no CDN, no browser disk cache). The frontend
 * calls this via axios with {@code responseType: 'blob'}, wraps the
 * blob in an object URL, and pins it to the img element — stable
 * across re-renders so keystroke flicker + node reuse guarantees from
 * the earlier fix remain intact.
 */
@RestController
@RequestMapping("/api/v1/idms/documents")
@RequiredArgsConstructor
@Slf4j
public class SignatureBytesController {

    private final DocumentInstanceRepository instanceRepo;
    private final DocumentInstanceFieldValueRepository valueRepo;
    private final DocumentVaultService vault;

    @GetMapping("/{instanceId}/signatures/{fieldId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> getSignature(
            @PathVariable UUID instanceId,
            @PathVariable String fieldId,
            /** Cache-buster tied to the underlying documentId — lets the
             *  browser reuse a cached blob URL for the same signature and
             *  force a fresh fetch when re-signed. Accepted but unused
             *  server-side. */
            @RequestParam(value = "v", required = false) String cacheBuster,
            @AuthenticationPrincipal User caller) {
        if (caller == null) throw new ForbiddenException("Sign in first.");
        if (fieldId == null || fieldId.isBlank()) {
            throw new BadRequestException("fieldId required");
        }
        DocumentInstance instance = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document not found: " + instanceId));

        boolean isStaff = caller.getRoles() != null && (
                caller.getRoles().contains(UserRole.ERM)
                        || caller.getRoles().contains(UserRole.SUPER_ADMIN));
        boolean isOwningIntern = caller.getId().equals(instance.getInternUserId());
        if (!isStaff && !isOwningIntern) {
            throw new ForbiddenException("You don't have access to this document.");
        }

        DocumentInstanceFieldValue val = valueRepo
                .findByInstanceIdAndFieldId(instanceId, fieldId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Signature not found for field " + fieldId));
        if (val.getSignatureDocumentId() == null) {
            throw new ResourceNotFoundException(
                    "That field has no captured signature yet.");
        }

        byte[] png;
        try {
            // vault.readDocumentBytesNoAuth decrypts the envelope; the
            // authorisation gate above is the sole access boundary for
            // this endpoint.
            png = vault.readDocumentBytesNoAuth(val.getSignatureDocumentId());
        } catch (Exception ex) {
            log.warn("[IDMS] signature bytes read failed instance={} field={} doc={}: {}",
                    instanceId, fieldId, val.getSignatureDocumentId(), ex.getMessage());
            throw new ResourceNotFoundException(
                    "Signature file couldn't be loaded.");
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(png);
    }
}
