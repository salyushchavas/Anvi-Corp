package com.anvicorp.api.mail.service;

import com.anvicorp.api.mail.auth.MailPrincipal;
import com.anvicorp.api.mail.dto.MailCustomFolderRequest;
import com.anvicorp.api.mail.dto.MailCustomFolderResponse;
import com.anvicorp.api.mail.entity.MailCustomFolder;
import com.anvicorp.api.mail.entity.MailFolder;
import com.anvicorp.api.mail.entity.MailMailboxEntry;
import com.anvicorp.api.mail.exception.MailApiException;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailCustomFolderRepository;
import com.anvicorp.api.mail.repository.MailMailboxEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Walled CRUD for per-account custom folders. Every op re-loads the actor and
 * scopes by accountId; a foreign folder id returns 404 (anti-enumeration).
 * Deleting a folder moves its messages to Trash (clears the FK, sets the system
 * enum to TRASH — recoverable) then removes the row, transactionally.
 */
@Service
@RequiredArgsConstructor
public class MailCustomFolderService {

    private static final int MAX_FOLDERS = 50;
    private static final int MAX_NAME = 100;

    private final MailCustomFolderRepository folderRepository;
    private final MailAccountRepository accountRepository;
    private final MailMailboxEntryRepository entryRepository;

    @Transactional(readOnly = true)
    public List<MailCustomFolderResponse> list(MailPrincipal principal) {
        UUID actorId = loadActorId(principal);
        return folderRepository.findByAccountIdOrderByNameAsc(actorId).stream()
                .map(f -> toResponse(actorId, f))
                .toList();
    }

    @Transactional
    public MailCustomFolderResponse create(MailPrincipal principal, MailCustomFolderRequest req) {
        UUID actorId = loadActorId(principal);
        String name = validateName(req);
        if (folderRepository.countByAccountId(actorId) >= MAX_FOLDERS) {
            throw badRequest("Folder limit reached (" + MAX_FOLDERS + ")", "MAIL_FOLDER_LIMIT");
        }
        if (folderRepository.existsByAccountIdAndNameIgnoreCase(actorId, name)) {
            throw conflict("A folder with that name already exists");
        }
        MailCustomFolder folder = folderRepository.save(
                MailCustomFolder.builder().accountId(actorId).name(name).build());
        return toResponse(actorId, folder);
    }

    @Transactional
    public MailCustomFolderResponse rename(MailPrincipal principal, UUID id, MailCustomFolderRequest req) {
        UUID actorId = loadActorId(principal);
        String name = validateName(req);
        MailCustomFolder folder = load(actorId, id);
        if (!folder.getName().equalsIgnoreCase(name)
                && folderRepository.existsByAccountIdAndNameIgnoreCase(actorId, name)) {
            throw conflict("A folder with that name already exists");
        }
        folder.setName(name);
        return toResponse(actorId, folderRepository.save(folder));
    }

    @Transactional
    public void delete(MailPrincipal principal, UUID id) {
        UUID actorId = loadActorId(principal);
        MailCustomFolder folder = load(actorId, id);
        // Recoverable: messages go to Trash (clear the FK, set system enum=TRASH).
        List<MailMailboxEntry> entries = entryRepository.findByAccountIdAndCustomFolderId(actorId, id);
        for (MailMailboxEntry e : entries) {
            e.setCustomFolderId(null);
            e.setFolder(MailFolder.TRASH);
        }
        if (!entries.isEmpty()) {
            entryRepository.saveAll(entries);
        }
        folderRepository.delete(folder);
    }

    private MailCustomFolderResponse toResponse(UUID actorId, MailCustomFolder f) {
        long total = entryRepository.countByAccountIdAndCustomFolderIdAndDeletedAtIsNull(actorId, f.getId());
        long unread = entryRepository.countByAccountIdAndCustomFolderIdAndDeletedAtIsNullAndIsReadFalse(
                actorId, f.getId());
        return new MailCustomFolderResponse(f.getId().toString(), f.getName(), total, unread);
    }

    private MailCustomFolder load(UUID actorId, UUID id) {
        return folderRepository.findByIdAndAccountId(id, actorId)
                .orElseThrow(() -> new MailApiException(HttpStatus.NOT_FOUND, "Folder not found", "MAIL_NOT_FOUND"));
    }

    private String validateName(MailCustomFolderRequest req) {
        String n = req == null || req.name() == null ? "" : req.name().trim();
        if (n.isEmpty()) {
            throw badRequest("Folder name is required", "MAIL_FOLDER_NAME_REQUIRED");
        }
        if (n.length() > MAX_NAME) {
            throw badRequest("Folder name is too long", "MAIL_FOLDER_NAME_TOO_LONG");
        }
        return n;
    }

    private UUID loadActorId(MailPrincipal principal) {
        if (principal == null) {
            throw new MailApiException(HttpStatus.UNAUTHORIZED, "Authentication required", "MAIL_UNAUTHENTICATED");
        }
        return accountRepository.findById(principal.accountId())
                .orElseThrow(() -> new MailApiException(
                        HttpStatus.UNAUTHORIZED, "Authentication required", "MAIL_UNAUTHENTICATED"))
                .getId();
    }

    private static MailApiException badRequest(String msg, String code) {
        return new MailApiException(HttpStatus.BAD_REQUEST, msg, code);
    }

    private static MailApiException conflict(String msg) {
        return new MailApiException(HttpStatus.CONFLICT, msg, "MAIL_FOLDER_DUP");
    }
}
