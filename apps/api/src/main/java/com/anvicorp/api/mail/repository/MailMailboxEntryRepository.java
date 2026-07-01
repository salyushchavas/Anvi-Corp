package com.anvicorp.api.mail.repository;

import com.anvicorp.api.mail.entity.MailFolder;
import com.anvicorp.api.mail.entity.MailMailboxEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MailMailboxEntryRepository extends JpaRepository<MailMailboxEntry, UUID> {

    /** Walled fetch — only the caller's own entry. */
    Optional<MailMailboxEntry> findByIdAndAccountId(UUID id, UUID accountId);

    // ── System-folder listing + counts (A6 precedence: only FK-NULL entries;
    //    a message moved into a custom folder no longer shows in its system
    //    folder. For every existing/never-custom entry this is identical to
    //    before — they all have custom_folder_id NULL). ──────────────────────────
    Page<MailMailboxEntry> findByAccountIdAndFolderAndCustomFolderIdIsNullAndDeletedAtIsNull(
            UUID accountId, MailFolder folder, Pageable pageable);

    long countByAccountIdAndFolderAndCustomFolderIdIsNullAndDeletedAtIsNull(UUID accountId, MailFolder folder);

    long countByAccountIdAndFolderAndCustomFolderIdIsNullAndDeletedAtIsNullAndIsReadFalse(
            UUID accountId, MailFolder folder);

    // ── Custom-folder listing + counts ────────────────────────────────────────
    Page<MailMailboxEntry> findByAccountIdAndCustomFolderIdAndDeletedAtIsNull(
            UUID accountId, UUID customFolderId, Pageable pageable);

    long countByAccountIdAndCustomFolderIdAndDeletedAtIsNull(UUID accountId, UUID customFolderId);

    long countByAccountIdAndCustomFolderIdAndDeletedAtIsNullAndIsReadFalse(UUID accountId, UUID customFolderId);

    /** All the caller's entries in a custom folder (any state) — used by
     *  delete-folder → Trash so no entry retains a dangling FK. */
    List<MailMailboxEntry> findByAccountIdAndCustomFolderId(UUID accountId, UUID customFolderId);

    // ── Starred view (cross-folder; placement-agnostic) ───────────────────────
    Page<MailMailboxEntry> findByAccountIdAndIsStarredTrueAndDeletedAtIsNull(
            UUID accountId, Pageable pageable);

    /** True if the caller participates in (has any entry for) a message — walling
     *  check for replies, draft sends, and attachment downloads. */
    boolean existsByAccountIdAndMessageId(UUID accountId, UUID messageId);

    /** The caller's entry for a message in a specific folder (e.g. their DRAFTS
     *  entry — used by the attachment delete-from-draft guard). */
    Optional<MailMailboxEntry> findByAccountIdAndMessageIdAndFolder(
            UUID accountId, UUID messageId, MailFolder folder);

    /** The caller's live entries within a thread (for the thread view). */
    List<MailMailboxEntry> findByAccountIdAndMessageIdInAndDeletedAtIsNull(
            UUID accountId, Collection<UUID> messageIds);

    /** Bounded scan for search (Pageable caps the candidate set; TRASH excluded).
     *  Custom-foldered entries are still searchable (their folder enum isn't TRASH). */
    List<MailMailboxEntry> findByAccountIdAndDeletedAtIsNullAndFolderNot(
            UUID accountId, MailFolder folder, Pageable pageable);
}
