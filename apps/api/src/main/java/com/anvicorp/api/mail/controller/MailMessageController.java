package com.anvicorp.api.mail.controller;

import com.anvicorp.api.auth.MailPrincipal;
import com.anvicorp.api.mail.dto.MailDraftRequest;
import com.anvicorp.api.mail.dto.MailFlagsRequest;
import com.anvicorp.api.mail.dto.MailFolderCount;
import com.anvicorp.api.mail.dto.MailMessageDetail;
import com.anvicorp.api.mail.dto.MailMessageSummary;
import com.anvicorp.api.mail.dto.MailMoveRequest;
import com.anvicorp.api.mail.dto.MailPage;
import com.anvicorp.api.mail.dto.MailSendRequest;
import com.anvicorp.api.mail.dto.MailThreadResponse;
import com.anvicorp.api.mail.entity.MailFolder;
import com.anvicorp.api.mail.exception.MailApiException;
import com.anvicorp.api.mail.service.MailMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Internal mail core endpoints under /api/mail/** (gated by the A1 @Order(1) mail
 * chain to MAIL_USER/ADMIN/SUPER_ADMIN — a pre-change principal cannot reach
 * them). Every call is walled in {@link MailMessageService} via the re-loaded
 * actor; path ids are parsed here to return a clean 400 (not a 500) on garbage.
 */
@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
public class MailMessageController {

    private final MailMessageService service;

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MailMessageDetail send(@AuthenticationPrincipal MailPrincipal principal,
                                  @RequestBody MailSendRequest req) {
        return service.send(principal, req);
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public MailMessageDetail saveDraft(@AuthenticationPrincipal MailPrincipal principal,
                                       @RequestBody MailDraftRequest req) {
        return service.saveDraft(principal, req);
    }

    @PutMapping("/drafts/{entryId}")
    public MailMessageDetail updateDraft(@AuthenticationPrincipal MailPrincipal principal,
                                         @PathVariable String entryId,
                                         @RequestBody MailDraftRequest req) {
        return service.updateDraft(principal, uuid(entryId), req);
    }

    @PostMapping("/drafts/{entryId}/send")
    public MailMessageDetail sendDraft(@AuthenticationPrincipal MailPrincipal principal,
                                       @PathVariable String entryId,
                                       @RequestBody(required = false) MailSendRequest req) {
        return service.sendDraft(principal, uuid(entryId), req);
    }

    @GetMapping("/folders/{folder}")
    public MailPage<MailMessageSummary> listFolder(@AuthenticationPrincipal MailPrincipal principal,
                                                   @PathVariable String folder,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "0") int size) {
        return service.listFolder(principal, folder(folder), page, size);
    }

    @GetMapping("/folder-counts")
    public List<MailFolderCount> folderCounts(@AuthenticationPrincipal MailPrincipal principal) {
        return service.folderCounts(principal);
    }

    @GetMapping("/starred")
    public MailPage<MailMessageSummary> starred(@AuthenticationPrincipal MailPrincipal principal,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "0") int size) {
        return service.starred(principal, page, size);
    }

    @GetMapping("/messages/{entryId}")
    public MailMessageDetail getEntry(@AuthenticationPrincipal MailPrincipal principal,
                                      @PathVariable String entryId) {
        return service.getEntry(principal, uuid(entryId));
    }

    @GetMapping("/threads/{threadId}")
    public MailThreadResponse getThread(@AuthenticationPrincipal MailPrincipal principal,
                                        @PathVariable String threadId) {
        return service.getThread(principal, uuid(threadId));
    }

    @PatchMapping("/messages/{entryId}/folder")
    public MailMessageDetail move(@AuthenticationPrincipal MailPrincipal principal,
                                  @PathVariable String entryId,
                                  @RequestBody MailMoveRequest req) {
        return service.move(principal, uuid(entryId), folder(req.folder()));
    }

    /** Soft-delete = move the caller's entry to TRASH. */
    @PostMapping("/messages/{entryId}/trash")
    public MailMessageDetail trash(@AuthenticationPrincipal MailPrincipal principal,
                                   @PathVariable String entryId) {
        return service.softDelete(principal, uuid(entryId));
    }

    @PatchMapping("/messages/{entryId}/flags")
    public MailMessageDetail setFlags(@AuthenticationPrincipal MailPrincipal principal,
                                      @PathVariable String entryId,
                                      @RequestBody MailFlagsRequest req) {
        return service.setFlags(principal, uuid(entryId), req);
    }

    /** Permanent delete of the caller's OWN entry (tombstone; full purge later). */
    @DeleteMapping("/messages/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal MailPrincipal principal,
                       @PathVariable String entryId) {
        service.permanentDelete(principal, uuid(entryId));
    }

    @GetMapping("/search")
    public MailPage<MailMessageSummary> search(@AuthenticationPrincipal MailPrincipal principal,
                                               @RequestParam(name = "q", defaultValue = "") String q,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "0") int size) {
        return service.search(principal, q, page, size);
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            throw new MailApiException(HttpStatus.BAD_REQUEST, "Invalid id", "MAIL_INVALID_ID");
        }
    }

    private static MailFolder folder(String s) {
        if (s == null || s.isBlank()) {
            throw new MailApiException(HttpStatus.BAD_REQUEST, "Folder is required", "MAIL_INVALID_FOLDER");
        }
        try {
            return MailFolder.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MailApiException(HttpStatus.BAD_REQUEST, "Invalid folder", "MAIL_INVALID_FOLDER");
        }
    }
}
