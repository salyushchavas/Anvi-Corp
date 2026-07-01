package com.anvicorp.api.mail.service;

import com.anvicorp.api.auth.MailPrincipal;
import com.anvicorp.api.mail.dto.MailAttachmentResponse;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailAttachment;
import com.anvicorp.api.mail.entity.MailFolder;
import com.anvicorp.api.mail.entity.MailMailboxEntry;
import com.anvicorp.api.mail.entity.MailMessage;
import com.anvicorp.api.mail.exception.MailApiException;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailAttachmentRepository;
import com.anvicorp.api.mail.repository.MailMailboxEntryRepository;
import com.anvicorp.api.mail.repository.MailMessageRepository;
import com.anvicorp.api.mail.storage.S3MailBlobStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Mail attachments: draft-anchored encrypted upload, walled download proxy, and
 * draft-scoped delete. Every op re-loads the actor and is walled — upload only to
 * the caller's OWN draft (IDOR guard → 404), download only if the caller has a
 * mailbox entry for the attachment's message (else 404, anti-enumeration), delete
 * only from the caller's own draft.
 */
@Service
@RequiredArgsConstructor
public class MailAttachmentService {

    private final MailAttachmentRepository attachmentRepository;
    private final MailMailboxEntryRepository entryRepository;
    private final MailMessageRepository messageRepository;
    private final MailAccountRepository accountRepository;
    private final S3MailBlobStore blobStore;

    @Value("${app.webmail.attachment-max-bytes:26214400}")
    private long maxBytes;

    public long maxBytes() {
        return maxBytes;
    }

    /** The decrypted bytes + metadata streamed by the download proxy. */
    public record Download(String filename, String contentType, byte[] bytes) {
    }

    @Transactional
    public MailAttachmentResponse upload(MailPrincipal principal, UUID draftEntryId,
                                         String filename, String contentType, byte[] bytes) {
        MailAccount actor = loadActor(principal);
        if (!blobStore.isReady()) {
            throw new MailApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Attachment storage is not configured", "MAIL_STORAGE_NOT_CONFIGURED");
        }
        if (bytes == null || bytes.length == 0) {
            throw badRequest("Empty file", "MAIL_EMPTY_FILE");
        }
        if (bytes.length > maxBytes) {
            throw new MailApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File exceeds the " + (maxBytes / (1024 * 1024)) + " MB limit", "MAIL_FILE_TOO_LARGE");
        }
        // IDOR wall: the draft must be the caller's own DRAFTS entry.
        MailMailboxEntry draft = entryRepository.findByIdAndAccountId(draftEntryId, actor.getId())
                .orElseThrow(() -> notFound("Draft not found"));
        if (draft.getFolder() != MailFolder.DRAFTS || draft.getDeletedAt() != null) {
            throw badRequest("Not a draft", "MAIL_NOT_A_DRAFT");
        }
        MailMessage msg = messageRepository.findById(draft.getMessageId())
                .orElseThrow(() -> notFound("Draft not found"));

        String storageKey = blobStore.store(bytes);
        MailAttachment att = attachmentRepository.save(MailAttachment.builder()
                .messageId(msg.getId())
                .filename(sanitizeFilename(filename))
                .contentType(trimToNull(contentType))
                .sizeBytes(bytes.length)
                .storageKey(storageKey)
                .build());
        if (!Boolean.TRUE.equals(msg.getHasAttachments())) {
            msg.setHasAttachments(true);
            messageRepository.save(msg);
        }
        return toResponse(att);
    }

    @Transactional(readOnly = true)
    public Download download(MailPrincipal principal, UUID attachmentId) {
        MailAccount actor = loadActor(principal);
        MailAttachment att = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> notFound("Attachment not found"));
        // Wall: the caller must have a mailbox entry for the attachment's message.
        if (!entryRepository.existsByAccountIdAndMessageId(actor.getId(), att.getMessageId())) {
            throw notFound("Attachment not found");
        }
        byte[] bytes = blobStore.fetch(att.getStorageKey());
        return new Download(att.getFilename(), att.getContentType(), bytes);
    }

    @Transactional
    public void delete(MailPrincipal principal, UUID attachmentId) {
        MailAccount actor = loadActor(principal);
        MailAttachment att = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> notFound("Attachment not found"));
        // Only removable from the caller's OWN draft.
        entryRepository.findByAccountIdAndMessageIdAndFolder(
                        actor.getId(), att.getMessageId(), MailFolder.DRAFTS)
                .orElseThrow(() -> notFound("Attachment not found"));
        blobStore.delete(att.getStorageKey());
        attachmentRepository.delete(att);
        if (attachmentRepository.countByMessageId(att.getMessageId()) == 0) {
            messageRepository.findById(att.getMessageId()).ifPresent(m -> {
                m.setHasAttachments(false);
                messageRepository.save(m);
            });
        }
    }

    /** Used by the message detail view (already walled by the caller's own entry). */
    public List<MailAttachmentResponse> listForMessage(UUID messageId) {
        return attachmentRepository.findByMessageId(messageId).stream()
                .map(MailAttachmentService::toResponse)
                .toList();
    }

    private MailAccount loadActor(MailPrincipal principal) {
        if (principal == null) {
            throw new MailApiException(HttpStatus.UNAUTHORIZED, "Authentication required", "MAIL_UNAUTHENTICATED");
        }
        return accountRepository.findById(principal.accountId())
                .orElseThrow(() -> new MailApiException(
                        HttpStatus.UNAUTHORIZED, "Authentication required", "MAIL_UNAUTHENTICATED"));
    }

    private static MailAttachmentResponse toResponse(MailAttachment a) {
        return new MailAttachmentResponse(a.getId().toString(), a.getFilename(),
                a.getContentType(), a.getSizeBytes());
    }

    /** Strip path separators + control chars; keep a safe, bounded filename. */
    private static String sanitizeFilename(String name) {
        String n = (name == null || name.isBlank()) ? "attachment" : name.trim();
        n = n.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("[\\x00-\\x1f\"]", "_");
        if (n.isBlank()) n = "attachment";
        return n.length() > 200 ? n.substring(0, 200) : n;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static MailApiException notFound(String msg) {
        return new MailApiException(HttpStatus.NOT_FOUND, msg, "MAIL_NOT_FOUND");
    }

    private static MailApiException badRequest(String msg, String code) {
        return new MailApiException(HttpStatus.BAD_REQUEST, msg, code);
    }
}
