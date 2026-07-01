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

    // ── System-folder listing + counts (live entries only) ────────────────────
    Page<MailMailboxEntry> findByAccountIdAndFolderAndDeletedAtIsNull(
            UUID accountId, MailFolder folder, Pageable pageable);

    long countByAccountIdAndFolderAndDeletedAtIsNull(UUID accountId, MailFolder folder);

    long countByAccountIdAndFolderAndDeletedAtIsNullAndIsReadFalse(UUID accountId, MailFolder folder);

    // ── Starred view ─────────────────────────────────────────────────────────
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

    /** Bounded scan for search (Pageable caps the candidate set; TRASH excluded). */
    List<MailMailboxEntry> findByAccountIdAndDeletedAtIsNullAndFolderNot(
            UUID accountId, MailFolder folder, Pageable pageable);
}
