package com.anvicorp.api.mail.controller;

import com.anvicorp.api.auth.MailPrincipal;
import com.anvicorp.api.mail.dto.MailAttachmentResponse;
import com.anvicorp.api.mail.exception.MailApiException;
import com.anvicorp.api.mail.service.MailAttachmentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Attachment endpoints under the @Order(1) mail chain (gated to MAIL_USER+, past
 * the must-change gate). Upload takes a RAW octet-stream body (NOT Spring
 * multipart) so it needs no global multipart limit; the 25 MB mail cap is
 * enforced here by a bounded read. Download is a walled proxy that streams
 * DECRYPTED bytes — no presigned URL or S3 key is ever exposed.
 */
@RestController
@RequestMapping("/api/mail/attachments")
@RequiredArgsConstructor
public class MailAttachmentController {

    private final MailAttachmentService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MailAttachmentResponse upload(@AuthenticationPrincipal MailPrincipal principal,
                                         @RequestParam String draftId,
                                         @RequestParam String filename,
                                         @RequestParam(required = false) String contentType,
                                         HttpServletRequest request) throws IOException {
        byte[] bytes = readBounded(request, service.maxBytes());
        return service.upload(principal, uuid(draftId), filename, contentType, bytes);
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal MailPrincipal principal,
                                           @PathVariable String id) {
        MailAttachmentService.Download d = service.download(principal, uuid(id));
        MediaType type = parseType(d.contentType());
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(d.filename() == null ? "attachment" : d.filename())
                .build();
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(d.bytes());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal MailPrincipal principal, @PathVariable String id) {
        service.delete(principal, uuid(id));
    }

    /** Read the request body with a hard cap so a huge body can't OOM the server. */
    private static byte[] readBounded(HttpServletRequest request, long cap) throws IOException {
        try (InputStream in = request.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                // Check BEFORE writing so the cap is hard (no overshoot by a buffer).
                if (total + n > cap) {
                    throw new MailApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "File exceeds the " + (cap / (1024 * 1024)) + " MB limit",
                            "MAIL_FILE_TOO_LARGE");
                }
                total += n;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static MediaType parseType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            throw new MailApiException(HttpStatus.BAD_REQUEST, "Invalid id", "MAIL_INVALID_ID");
        }
    }
}
