package com.anvicorp.api.controller;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailFolder;
import com.anvicorp.api.mail.entity.MailMailboxEntry;
import com.anvicorp.api.mail.entity.MailMessage;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailMailboxEntryRepository;
import com.anvicorp.api.mail.repository.MailMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Mail bridge Phase 5 (revised) — Careers-side READ-ONLY peek into
 * the current user's linked company mailbox. Authorized by the
 * EXISTING Careers session ({@code @AuthenticationPrincipal User}) —
 * no MAIL JWT is read or minted, no MailPrincipal is constructed.
 *
 * <p>Returns:
 * <ul>
 *   <li>{@code hasMailbox=false} when the user has no linked mail
 *       account (the frontend hides the nav peek for these).</li>
 *   <li>{@code hasMailbox=true}, {@code unreadCount}, and the most
 *       recent inbox messages (id, sender address, subject,
 *       receivedAt, unread) for ACTIVATED users.</li>
 * </ul>
 *
 * <p>STRICTLY READ-ONLY. Compose / reply / mark-read live on the
 * /mail surface; this endpoint is for the nav peek only.</p>
 */
@RestController
@RequestMapping("/api/v1/me/mailbox")
@RequiredArgsConstructor
@Slf4j
public class MeMailboxController {

    /** Max items returned in the nav-peek list. The nav popover doesn't
     *  paginate; if the user wants more they open /mail. */
    private static final int PEEK_LIMIT = 10;

    private final MailAccountRepository mailAccountRepository;
    private final MailMailboxEntryRepository mailboxEntryRepository;
    private final MailMessageRepository messageRepository;

    @GetMapping("/summary")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public Summary summary(@AuthenticationPrincipal User caller) {
        if (caller == null) {
            return Summary.empty();
        }
        // Resolve the mailbox. Preferred path: the explicit
        // User.mailAccountId link. Fallback: match by
        // User.email → localPart@domain, so staff accounts created
        // through the admin dual-write (which historically forgot to
        // stamp the link column) still get a real peek instead of a
        // false hasMailbox=false. Same address-based resolution
        // MailSsoService uses for its handoff — the two paths now
        // agree on "does this user have a mailbox".
        MailAccount account = resolveAccount(caller);
        if (account == null) return Summary.empty();
        UUID accountId = account.getId();
        try {

            long unread = mailboxEntryRepository
                    .countByAccountIdAndFolderAndCustomFolderIdIsNullAndDeletedAtIsNullAndIsReadFalse(
                            accountId, MailFolder.INBOX);

            // Most recent inbox entries — same query the inbox page uses,
            // capped at PEEK_LIMIT.
            var page = mailboxEntryRepository
                    .findByAccountIdAndFolderAndCustomFolderIdIsNullAndDeletedAtIsNull(
                            accountId, MailFolder.INBOX,
                            PageRequest.of(0, PEEK_LIMIT,
                                    Sort.by(Sort.Direction.DESC, "createdAt")));

            // Bulk-load the referenced messages + senders in one round-trip each.
            List<UUID> msgIds = page.getContent().stream()
                    .map(MailMailboxEntry::getMessageId).toList();
            Map<UUID, MailMessage> msgById = new HashMap<>();
            if (!msgIds.isEmpty()) {
                for (MailMessage m : messageRepository.findAllById(msgIds)) {
                    msgById.put(m.getId(), m);
                }
            }
            List<UUID> senderIds = msgById.values().stream()
                    .map(MailMessage::getSenderAccountId).toList();
            Map<UUID, MailAccount> senderById = new HashMap<>();
            if (!senderIds.isEmpty()) {
                for (MailAccount a : mailAccountRepository.findAllById(senderIds)) {
                    senderById.put(a.getId(), a);
                }
            }

            List<PeekItem> items = new ArrayList<>(page.getContent().size());
            for (MailMailboxEntry e : page.getContent()) {
                MailMessage m = msgById.get(e.getMessageId());
                if (m == null) continue;
                MailAccount sender = senderById.get(m.getSenderAccountId());
                String fromAddress = sender != null
                        ? sender.getLocalPart() + "@" + sender.getDomain().getName()
                        : "(unknown sender)";
                items.add(new PeekItem(
                        e.getId(),
                        fromAddress,
                        m.getSubject(),
                        m.getCreatedAt(),
                        !Boolean.TRUE.equals(e.getIsRead())));
            }

            return new Summary(true, accountId,
                    account.getLocalPart() + "@" + account.getDomain().getName(),
                    unread, items);
        } catch (Exception ex) {
            // Best-effort: a peek failure must never crash the dashboard.
            log.warn("[MeMailbox] summary failed for user {} (non-fatal): {}",
                    caller.getId(), ex.getMessage());
            return Summary.empty();
        }
    }

    /**
     * Resolve the caller's mailbox with a two-step lookup:
     * <ol>
     *   <li>{@code User.mailAccountId} → mailbox by id. Fastest path;
     *       used by intern accounts and any staff account whose link is
     *       correctly stamped.</li>
     *   <li>{@code User.email} → {@code localPart@domain} → mailbox by
     *       address. Rescue path for staff created via the admin
     *       dual-write, which historically forgot to stamp
     *       {@link User#getMailAccountId()} — the MailAccount row is
     *       there, just unlinked. Same address resolution
     *       {@code MailSsoService.mintFor} uses, so the two paths agree.</li>
     * </ol>
     * Returns {@code null} when neither path finds a mailbox — the caller
     * turns that into a {@code hasMailbox=false} summary.
     */
    private MailAccount resolveAccount(User caller) {
        UUID linkedId = caller.getMailAccountId();
        if (linkedId != null) {
            MailAccount linked = mailAccountRepository.findById(linkedId).orElse(null);
            if (linked != null) return linked;
        }
        String email = caller.getEmail();
        if (email == null || email.isBlank()) return null;
        String norm = email.trim().toLowerCase(Locale.ROOT);
        int at = norm.indexOf('@');
        if (at <= 0 || at == norm.length() - 1) return null;
        String localPart = norm.substring(0, at);
        String domainName = norm.substring(at + 1);
        return mailAccountRepository
                .findByLocalPartAndDomain_Name(localPart, domainName)
                .orElse(null);
    }

    /** Compact peek-item DTO. NO body — body is encrypted at rest and
     *  the peek is subject-only. */
    public record PeekItem(
            UUID entryId,
            String fromAddress,
            String subject,
            Instant receivedAt,
            boolean unread
    ) {}

    /** Peek summary. {@code hasMailbox=false} ⇒ the user has no linked
     *  mail account and the nav peek should be hidden entirely. */
    public record Summary(
            boolean hasMailbox,
            UUID mailAccountId,
            String mailAddress,
            long unreadCount,
            List<PeekItem> items
    ) {
        static Summary empty() {
            return new Summary(false, null, null, 0L, List.of());
        }
    }

}
