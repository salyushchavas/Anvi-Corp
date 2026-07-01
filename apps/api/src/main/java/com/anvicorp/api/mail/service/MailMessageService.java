package com.anvicorp.api.mail.service;

import com.anvicorp.api.auth.MailPrincipal;
import com.anvicorp.api.mail.dto.MailAttachmentResponse;
import com.anvicorp.api.mail.dto.MailDraftRequest;
import com.anvicorp.api.mail.dto.MailFlagsRequest;
import com.anvicorp.api.mail.dto.MailFolderCount;
import com.anvicorp.api.mail.dto.MailMessageDetail;
import com.anvicorp.api.mail.dto.MailMessageHeader;
import com.anvicorp.api.mail.dto.MailMessageSummary;
import com.anvicorp.api.mail.dto.MailPage;
import com.anvicorp.api.mail.dto.MailParticipant;
import com.anvicorp.api.mail.dto.MailSendRequest;
import com.anvicorp.api.mail.dto.MailThreadResponse;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailFolder;
import com.anvicorp.api.mail.entity.MailMailboxEntry;
import com.anvicorp.api.mail.entity.MailMessage;
import com.anvicorp.api.mail.entity.MailMessageRecipient;
import com.anvicorp.api.mail.entity.MailRecipientType;
import com.anvicorp.api.mail.exception.MailApiException;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailAttachmentRepository;
import com.anvicorp.api.mail.repository.MailCustomFolderRepository;
import com.anvicorp.api.mail.repository.MailMailboxEntryRepository;
import com.anvicorp.api.mail.repository.MailMessageRecipientRepository;
import com.anvicorp.api.mail.repository.MailMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Internal mail core. Every operation re-loads the actor from the principal and
 * is strictly walled: a caller only ever sees/acts on mailbox entries where
 * account_id == caller; a no-entry read is 404 (anti-enumeration). Send is
 * same-domain ONLY — a cross-domain or unknown recipient rejects the whole send
 * with 422 (no partial delivery; resolution precedes any write). Bodies are
 * encrypted at rest by the AES converter on {@link MailMessage}; listings/search
 * use a header projection so they never decrypt bodies. Body search is DEFERRED
 * (encrypted columns aren't DB-searchable); search covers subject + visible
 * participants only.
 *
 * <p>Rules, attachments, custom folders and SSE are later phases — a recipient's
 * delivered entry is a plain unread INBOX entry for now.</p>
 */
@Service
@RequiredArgsConstructor
public class MailMessageService {

    private final MailMessageRepository messageRepository;
    private final MailMessageRecipientRepository recipientRepository;
    private final MailMailboxEntryRepository entryRepository;
    private final MailAccountRepository accountRepository;
    private final MailAttachmentRepository attachmentRepository;
    private final MailCustomFolderRepository customFolderRepository;

    @Value("${app.webmail.messages.max-subject-length:500}")
    private int maxSubject;
    @Value("${app.webmail.messages.max-body-length:100000}")
    private int maxBody;
    @Value("${app.webmail.messages.max-recipients:100}")
    private int maxRecipients;
    @Value("${app.webmail.messages.default-page-size:25}")
    private int defaultPageSize;
    @Value("${app.webmail.messages.max-page-size:100}")
    private int maxPageSize;
    @Value("${app.webmail.messages.search-scan-cap:500}")
    private int searchScanCap;

    // ── Send / drafts ────────────────────────────────────────────────────

    @Transactional
    public MailMessageDetail send(MailPrincipal principal, MailSendRequest req) {
        MailAccount sender = loadActor(principal);
        validateContent(req.subject(), req.bodyText(), req.bodyHtml());
        Resolved r = resolveRecipients(sender, req.to(), req.cc(), req.bcc());
        if (r.count() == 0) {
            throw badRequest("At least one recipient is required", "MAIL_NO_RECIPIENTS");
        }
        MailMessage msg = newMessage(sender, req.subject(), req.bodyText(), req.bodyHtml(), req.inReplyTo());
        deliver(msg, r);
        MailMailboxEntry sent = entryRepository.save(MailMailboxEntry.builder()
                .accountId(sender.getId()).messageId(msg.getId()).folder(MailFolder.SENT)
                .isRead(true).build());
        return toDetail(sent, msg, sender.getId());
    }

    @Transactional
    public MailMessageDetail saveDraft(MailPrincipal principal, MailDraftRequest req) {
        MailAccount sender = loadActor(principal);
        validateContent(req.subject(), req.bodyText(), req.bodyHtml());
        ReplyContext reply = resolveReply(sender.getId(), req.inReplyTo());
        MailMessage msg = MailMessage.builder()
                .senderAccountId(sender.getId())
                .subject(req.subject())
                .bodyText(req.bodyText())
                .bodyHtml(req.bodyHtml())
                .inReplyTo(reply.inReplyTo())
                .threadId(reply.threadId())
                .draftTo(joinAddrs(req.to()))
                .draftCc(joinAddrs(req.cc()))
                .draftBcc(joinAddrs(req.bcc()))
                .hasAttachments(false)
                .build();
        msg = messageRepository.save(msg);
        if (msg.getThreadId() == null) {
            msg.setThreadId(msg.getId());
            msg = messageRepository.save(msg);
        }
        MailMailboxEntry entry = entryRepository.save(MailMailboxEntry.builder()
                .accountId(sender.getId()).messageId(msg.getId()).folder(MailFolder.DRAFTS)
                .isRead(true).build());
        return toDetail(entry, msg, sender.getId());
    }

    @Transactional
    public MailMessageDetail updateDraft(MailPrincipal principal, UUID entryId, MailDraftRequest req) {
        MailAccount sender = loadActor(principal);
        MailMailboxEntry entry = loadDraftEntry(sender.getId(), entryId);
        MailMessage msg = messageRepository.findById(entry.getMessageId())
                .orElseThrow(() -> notFound("Draft not found"));
        validateContent(req.subject(), req.bodyText(), req.bodyHtml());
        ReplyContext reply = resolveReply(sender.getId(), req.inReplyTo());
        msg.setSubject(req.subject());
        msg.setBodyText(req.bodyText());
        msg.setBodyHtml(req.bodyHtml());
        msg.setInReplyTo(reply.inReplyTo());
        if (reply.threadId() != null) {
            msg.setThreadId(reply.threadId());
        }
        msg.setDraftTo(joinAddrs(req.to()));
        msg.setDraftCc(joinAddrs(req.cc()));
        msg.setDraftBcc(joinAddrs(req.bcc()));
        messageRepository.save(msg);
        return toDetail(entry, msg, sender.getId());
    }

    /** Sends an existing draft, transitioning its DRAFTS entry to SENT (no dup). */
    @Transactional
    public MailMessageDetail sendDraft(MailPrincipal principal, UUID entryId, MailSendRequest req) {
        MailAccount sender = loadActor(principal);
        MailMailboxEntry draftEntry = loadDraftEntry(sender.getId(), entryId);
        MailMessage msg = messageRepository.findById(draftEntry.getMessageId())
                .orElseThrow(() -> notFound("Draft not found"));

        List<String> to = req != null && req.to() != null ? req.to() : splitAddrs(msg.getDraftTo());
        List<String> cc = req != null && req.cc() != null ? req.cc() : splitAddrs(msg.getDraftCc());
        List<String> bcc = req != null && req.bcc() != null ? req.bcc() : splitAddrs(msg.getDraftBcc());

        if (req != null) {
            validateContent(req.subject(), req.bodyText(), req.bodyHtml());
            if (req.subject() != null) msg.setSubject(req.subject());
            if (req.bodyText() != null) msg.setBodyText(req.bodyText());
            if (req.bodyHtml() != null) msg.setBodyHtml(req.bodyHtml());
        }

        Resolved r = resolveRecipients(sender, to, cc, bcc);
        if (r.count() == 0) {
            throw badRequest("At least one recipient is required", "MAIL_NO_RECIPIENTS");
        }

        // A reply draft joins its parent's thread on send.
        if (msg.getInReplyTo() != null) {
            messageRepository.findById(msg.getInReplyTo()).ifPresent(parent -> {
                if (entryRepository.existsByAccountIdAndMessageId(sender.getId(), parent.getId())) {
                    msg.setThreadId(parent.getThreadId() != null ? parent.getThreadId() : parent.getId());
                }
            });
        }
        msg.setDraftTo(null);
        msg.setDraftCc(null);
        msg.setDraftBcc(null);
        messageRepository.save(msg);

        deliver(msg, r);
        draftEntry.setFolder(MailFolder.SENT);
        draftEntry.setIsRead(true);
        entryRepository.save(draftEntry);
        return toDetail(draftEntry, msg, sender.getId());
    }

    // ── Reads ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public MailPage<MailMessageSummary> listFolder(MailPrincipal principal, MailFolder folder, int page, int size) {
        UUID callerId = loadActor(principal).getId();
        int sz = clampSize(size);
        int pg = Math.max(page, 0);
        Pageable pageable = PageRequest.of(pg, sz, Sort.by(Sort.Direction.DESC, "createdAt"));
        // Precedence: a system folder shows only entries NOT in a custom folder.
        Page<MailMailboxEntry> p = entryRepository
                .findByAccountIdAndFolderAndCustomFolderIdIsNullAndDeletedAtIsNull(callerId, folder, pageable);
        return new MailPage<>(buildSummaries(p.getContent()), pg, sz, p.getTotalElements());
    }

    /** List a custom folder's messages (walled — a foreign/unknown folder → 404). */
    @Transactional(readOnly = true)
    public MailPage<MailMessageSummary> listCustomFolder(MailPrincipal principal, UUID folderId, int page, int size) {
        UUID callerId = loadActor(principal).getId();
        if (!customFolderRepository.existsByIdAndAccountId(folderId, callerId)) {
            throw notFound("Folder not found"); // anti-enumeration
        }
        int sz = clampSize(size);
        int pg = Math.max(page, 0);
        Pageable pageable = PageRequest.of(pg, sz, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<MailMailboxEntry> p = entryRepository
                .findByAccountIdAndCustomFolderIdAndDeletedAtIsNull(callerId, folderId, pageable);
        return new MailPage<>(buildSummaries(p.getContent()), pg, sz, p.getTotalElements());
    }

    @Transactional(readOnly = true)
    public MailPage<MailMessageSummary> starred(MailPrincipal principal, int page, int size) {
        UUID callerId = loadActor(principal).getId();
        int sz = clampSize(size);
        int pg = Math.max(page, 0);
        Pageable pageable = PageRequest.of(pg, sz, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<MailMailboxEntry> p = entryRepository
                .findByAccountIdAndIsStarredTrueAndDeletedAtIsNull(callerId, pageable);
        return new MailPage<>(buildSummaries(p.getContent()), pg, sz, p.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<MailFolderCount> folderCounts(MailPrincipal principal) {
        UUID callerId = loadActor(principal).getId();
        List<MailFolderCount> out = new ArrayList<>();
        for (MailFolder f : MailFolder.values()) {
            // Precedence: system-folder counts exclude entries that live in a custom folder.
            long total = entryRepository.countByAccountIdAndFolderAndCustomFolderIdIsNullAndDeletedAtIsNull(callerId, f);
            long unread = entryRepository
                    .countByAccountIdAndFolderAndCustomFolderIdIsNullAndDeletedAtIsNullAndIsReadFalse(callerId, f);
            out.add(new MailFolderCount(f.name(), total, unread));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public MailMessageDetail getEntry(MailPrincipal principal, UUID entryId) {
        UUID callerId = loadActor(principal).getId();
        MailMailboxEntry entry = liveEntry(callerId, entryId);
        MailMessage msg = messageRepository.findById(entry.getMessageId())
                .orElseThrow(() -> notFound("Message not found"));
        return toDetail(entry, msg, callerId);
    }

    @Transactional(readOnly = true)
    public MailThreadResponse getThread(MailPrincipal principal, UUID threadId) {
        UUID callerId = loadActor(principal).getId();
        List<MailMessage> msgs = messageRepository.findByThreadId(threadId);
        if (msgs.isEmpty()) {
            throw notFound("Thread not found");
        }
        Set<UUID> msgIds = msgs.stream().map(MailMessage::getId).collect(Collectors.toSet());
        List<MailMailboxEntry> entries =
                entryRepository.findByAccountIdAndMessageIdInAndDeletedAtIsNull(callerId, msgIds);
        if (entries.isEmpty()) {
            throw notFound("Thread not found"); // caller participates in nothing here
        }
        Map<UUID, MailMailboxEntry> entryByMsg = new HashMap<>();
        for (MailMailboxEntry e : entries) {
            entryByMsg.putIfAbsent(e.getMessageId(), e);
        }
        Map<UUID, MailMessage> msgById = msgs.stream().collect(Collectors.toMap(MailMessage::getId, m -> m));
        List<MailMessageDetail> details = entryByMsg.entrySet().stream()
                .map(en -> toDetail(en.getValue(), msgById.get(en.getKey()), callerId))
                .sorted(Comparator.comparing(MailMessageDetail::createdAt))
                .toList();
        return new MailThreadResponse(threadId.toString(), details);
    }

    @Transactional(readOnly = true)
    public MailPage<MailMessageSummary> search(MailPrincipal principal, String query, int page, int size) {
        UUID callerId = loadActor(principal).getId();
        int sz = clampSize(size);
        int pg = Math.max(page, 0);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return new MailPage<>(List.of(), pg, sz, 0);
        }
        // Bounded scan of the caller's live, non-trash entries (newest first).
        List<MailMailboxEntry> candidates = entryRepository.findByAccountIdAndDeletedAtIsNullAndFolderNot(
                callerId, MailFolder.TRASH,
                PageRequest.of(0, searchScanCap, Sort.by(Sort.Direction.DESC, "createdAt")));
        if (candidates.isEmpty()) {
            return new MailPage<>(List.of(), pg, sz, 0);
        }
        Set<UUID> msgIds = candidates.stream().map(MailMailboxEntry::getMessageId).collect(Collectors.toSet());
        Map<UUID, MailMessageHeader> headers = messageRepository.findHeadersByIdIn(msgIds).stream()
                .collect(Collectors.toMap(MailMessageHeader::id, h -> h));
        Map<UUID, List<MailMessageRecipient>> recipsByMsg = recipientRepository.findByMessageIdIn(msgIds).stream()
                .collect(Collectors.groupingBy(MailMessageRecipient::getMessageId));
        Map<UUID, MailAccount> accts = resolveAccountsFor(headers.values().stream()
                .map(MailMessageHeader::senderAccountId).collect(Collectors.toSet()), recipsByMsg);

        List<MailMailboxEntry> matched = candidates.stream().filter(e -> {
            MailMessageHeader h = headers.get(e.getMessageId());
            if (h == null) return false;
            if (h.subject() != null && h.subject().toLowerCase(Locale.ROOT).contains(q)) return true;
            if (matchesAccount(accts.get(h.senderAccountId()), q)) return true;
            for (MailMessageRecipient r : recipsByMsg.getOrDefault(e.getMessageId(), List.of())) {
                if (r.getRecipientType() == MailRecipientType.BCC) continue;
                if (matchesAccount(accts.get(r.getRecipientAccountId()), q)) return true;
            }
            return false;
        }).toList();

        long total = matched.size();
        int from = Math.min(pg * sz, matched.size());
        int to = Math.min(from + sz, matched.size());
        List<MailMessageSummary> items = buildSummaries(matched.subList(from, to));
        return new MailPage<>(items, pg, sz, total);
    }

    // ── Mutations on the caller's own entry ──────────────────────────────

    @Transactional
    public MailMessageDetail move(MailPrincipal principal, UUID entryId, MailFolder folder) {
        UUID callerId = loadActor(principal).getId();
        MailMailboxEntry entry = liveEntry(callerId, entryId);
        entry.setFolder(folder);
        entry.setCustomFolderId(null); // moving to a system folder clears custom placement
        entryRepository.save(entry);
        MailMessage msg = messageRepository.findById(entry.getMessageId())
                .orElseThrow(() -> notFound("Message not found"));
        return toDetail(entry, msg, callerId);
    }

    /**
     * Move the caller's entry into a custom folder (walled — a foreign/unknown
     * folder → 404). Sets the FK; the custom folder wins for placement (precedence:
     * custom over system). The system enum is otherwise left as-is, EXCEPT that a
     * TRASH enum is normalized away: search gates on {@code folder != TRASH}, so an
     * entry filed from Trash into a custom folder would otherwise be silently
     * unsearchable while visibly living in that folder. Exactly one placement is
     * authoritative, and a custom-foldered entry is always searchable.
     */
    @Transactional
    public MailMessageDetail moveToCustomFolder(MailPrincipal principal, UUID entryId, UUID folderId) {
        UUID callerId = loadActor(principal).getId();
        if (!customFolderRepository.existsByIdAndAccountId(folderId, callerId)) {
            throw notFound("Folder not found");
        }
        MailMailboxEntry entry = liveEntry(callerId, entryId);
        entry.setCustomFolderId(folderId);
        if (entry.getFolder() == MailFolder.TRASH) {
            entry.setFolder(MailFolder.INBOX); // keep it out of TRASH so search still finds it
        }
        entryRepository.save(entry);
        MailMessage msg = messageRepository.findById(entry.getMessageId())
                .orElseThrow(() -> notFound("Message not found"));
        return toDetail(entry, msg, callerId);
    }

    /** Soft-delete: move the caller's own entry to TRASH. */
    @Transactional
    public MailMessageDetail softDelete(MailPrincipal principal, UUID entryId) {
        return move(principal, entryId, MailFolder.TRASH);
    }

    @Transactional
    public MailMessageDetail setFlags(MailPrincipal principal, UUID entryId, MailFlagsRequest req) {
        UUID callerId = loadActor(principal).getId();
        MailMailboxEntry entry = liveEntry(callerId, entryId);
        if (req.isRead() != null) entry.setIsRead(req.isRead());
        if (req.isStarred() != null) entry.setIsStarred(req.isStarred());
        if (req.isImportant() != null) entry.setIsImportant(req.isImportant());
        entryRepository.save(entry);
        MailMessage msg = messageRepository.findById(entry.getMessageId())
                .orElseThrow(() -> notFound("Message not found"));
        return toDetail(entry, msg, callerId);
    }

    /** Tombstones the caller's own entry (full message purge is a later phase). */
    @Transactional
    public void permanentDelete(MailPrincipal principal, UUID entryId) {
        UUID callerId = loadActor(principal).getId();
        MailMailboxEntry entry = entryRepository.findByIdAndAccountId(entryId, callerId)
                .orElseThrow(() -> notFound("Message not found"));
        if (entry.getDeletedAt() == null) {
            entry.setDeletedAt(Instant.now());
            entryRepository.save(entry);
        }
    }

    // ── Walling / actor ──────────────────────────────────────────────────

    private MailAccount loadActor(MailPrincipal principal) {
        if (principal == null) {
            throw new MailApiException(HttpStatus.UNAUTHORIZED, "Authentication required", "MAIL_UNAUTHENTICATED");
        }
        return accountRepository.findById(principal.accountId())
                .orElseThrow(() -> new MailApiException(
                        HttpStatus.UNAUTHORIZED, "Authentication required", "MAIL_UNAUTHENTICATED"));
    }

    private MailMailboxEntry liveEntry(UUID callerId, UUID entryId) {
        MailMailboxEntry entry = entryRepository.findByIdAndAccountId(entryId, callerId)
                .orElseThrow(() -> notFound("Message not found"));
        if (entry.getDeletedAt() != null) {
            throw notFound("Message not found");
        }
        return entry;
    }

    private MailMailboxEntry loadDraftEntry(UUID callerId, UUID entryId) {
        MailMailboxEntry entry = liveEntry(callerId, entryId);
        if (entry.getFolder() != MailFolder.DRAFTS) {
            throw badRequest("Not a draft", "MAIL_NOT_A_DRAFT");
        }
        return entry;
    }

    // ── Send internals ───────────────────────────────────────────────────

    private MailMessage newMessage(MailAccount sender, String subject, String bodyText,
                                   String bodyHtml, String inReplyToStr) {
        ReplyContext reply = resolveReply(sender.getId(), inReplyToStr);
        MailMessage msg = MailMessage.builder()
                .senderAccountId(sender.getId())
                .subject(subject)
                .bodyText(bodyText)
                .bodyHtml(bodyHtml)
                .inReplyTo(reply.inReplyTo())
                .threadId(reply.threadId())
                .hasAttachments(false)
                .build();
        msg = messageRepository.save(msg);
        if (msg.getThreadId() == null) {
            msg.setThreadId(msg.getId());
            msg = messageRepository.save(msg);
        }
        return msg;
    }

    /**
     * Resolves an optional inReplyTo: validates the parent exists AND the caller
     * can see it (walling, else 404), and returns the thread to join. Used for
     * sends and reply drafts alike so a draft reply isn't an orphan thread and a
     * reply to an unseen message is rejected at creation time.
     */
    private ReplyContext resolveReply(UUID senderId, String inReplyToStr) {
        if (inReplyToStr == null || inReplyToStr.isBlank()) {
            return new ReplyContext(null, null);
        }
        UUID parentId = parseUuid(inReplyToStr, "inReplyTo");
        MailMessage parent = messageRepository.findById(parentId)
                .orElseThrow(() -> notFound("Message not found"));
        if (!entryRepository.existsByAccountIdAndMessageId(senderId, parentId)) {
            throw notFound("Message not found"); // can't reply to something you can't see
        }
        return new ReplyContext(parentId,
                parent.getThreadId() != null ? parent.getThreadId() : parent.getId());
    }

    /** Resolves recipients SAME-DOMAIN only; rejects the whole send if any is bad. */
    private Resolved resolveRecipients(MailAccount sender, List<String> to, List<String> cc, List<String> bcc) {
        UUID domainId = sender.getDomain().getId();
        String domainName = sender.getDomain().getName();
        List<String> bad = new ArrayList<>();
        List<MailAccount> toAll = resolveList(to, domainId, domainName, bad);
        List<MailAccount> ccAll = resolveList(cc, domainId, domainName, bad);
        List<MailAccount> bccAll = resolveList(bcc, domainId, domainName, bad);
        if (!bad.isEmpty()) {
            throw new MailApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Unknown or cross-domain recipient(s): " + String.join(", ", bad),
                    "MAIL_RECIPIENT_INVALID");
        }
        // De-duplicate so each account appears exactly once across to/cc/bcc
        // (first occurrence wins: TO > CC > BCC) — no duplicate recipient rows.
        Set<UUID> taken = new HashSet<>();
        List<MailAccount> rto = new ArrayList<>();
        List<MailAccount> rcc = new ArrayList<>();
        List<MailAccount> rbcc = new ArrayList<>();
        for (MailAccount a : toAll) if (taken.add(a.getId())) rto.add(a);
        for (MailAccount a : ccAll) if (taken.add(a.getId())) rcc.add(a);
        for (MailAccount a : bccAll) if (taken.add(a.getId())) rbcc.add(a);
        Resolved r = new Resolved(rto, rcc, rbcc);
        if (r.count() > maxRecipients) {
            throw badRequest("Too many recipients (max " + maxRecipients + ")", "MAIL_TOO_MANY_RECIPIENTS");
        }
        return r;
    }

    private List<MailAccount> resolveList(List<String> addrs, UUID domainId, String domainName, List<String> bad) {
        List<MailAccount> out = new ArrayList<>();
        if (addrs == null) return out;
        for (String raw : addrs) {
            if (raw == null || raw.isBlank()) continue;
            String addr = raw.trim().toLowerCase(Locale.ROOT);
            String localPart;
            int at = addr.indexOf('@');
            if (at >= 0) {
                String dom = addr.substring(at + 1);
                localPart = addr.substring(0, at);
                if (at == 0 || !dom.equals(domainName)) { // empty local OR cross-domain
                    bad.add(raw.trim());
                    continue;
                }
            } else {
                localPart = addr;
            }
            Optional<MailAccount> acct = accountRepository.findByLocalPartAndDomain_Id(localPart, domainId);
            if (acct.isEmpty()) {
                bad.add(raw.trim());
                continue;
            }
            out.add(acct.get());
        }
        return out;
    }

    /**
     * Creates recipient rows + one unread INBOX entry per distinct recipient.
     * (Rules / SSE / non-INBOX delivery placement are later phases.)
     */
    private void deliver(MailMessage msg, Resolved r) {
        saveRecipientRows(msg.getId(), r.to(), MailRecipientType.TO);
        saveRecipientRows(msg.getId(), r.cc(), MailRecipientType.CC);
        saveRecipientRows(msg.getId(), r.bcc(), MailRecipientType.BCC);
        for (MailAccount a : r.all()) {
            entryRepository.save(MailMailboxEntry.builder()
                    .accountId(a.getId()).messageId(msg.getId())
                    .folder(MailFolder.INBOX)
                    .isRead(false).isStarred(false).isImportant(false)
                    .build());
        }
    }

    private void saveRecipientRows(UUID messageId, List<MailAccount> accts, MailRecipientType type) {
        for (MailAccount a : accts) {
            recipientRepository.save(MailMessageRecipient.builder()
                    .messageId(messageId).recipientAccountId(a.getId()).recipientType(type).build());
        }
    }

    // ── DTO building ─────────────────────────────────────────────────────

    private List<MailMessageSummary> buildSummaries(List<MailMailboxEntry> entries) {
        if (entries.isEmpty()) return List.of();
        Set<UUID> msgIds = entries.stream().map(MailMailboxEntry::getMessageId).collect(Collectors.toSet());
        Map<UUID, MailMessageHeader> headers = messageRepository.findHeadersByIdIn(msgIds).stream()
                .collect(Collectors.toMap(MailMessageHeader::id, h -> h));
        Map<UUID, List<MailMessageRecipient>> recipsByMsg = recipientRepository.findByMessageIdIn(msgIds).stream()
                .collect(Collectors.groupingBy(MailMessageRecipient::getMessageId));
        Map<UUID, MailAccount> accts = resolveAccountsFor(headers.values().stream()
                .map(MailMessageHeader::senderAccountId).collect(Collectors.toSet()), recipsByMsg);

        List<MailMessageSummary> out = new ArrayList<>();
        for (MailMailboxEntry e : entries) {
            MailMessageHeader h = headers.get(e.getMessageId());
            if (h == null) continue;
            List<MailParticipant> toList = recipsByMsg.getOrDefault(e.getMessageId(), List.of()).stream()
                    .filter(r -> r.getRecipientType() != MailRecipientType.BCC)
                    .map(r -> participant(accts.get(r.getRecipientAccountId())))
                    .filter(Objects::nonNull).toList();
            out.add(new MailMessageSummary(
                    e.getId().toString(), h.id().toString(),
                    h.threadId() != null ? h.threadId().toString() : null,
                    e.getFolder().name(), h.subject(),
                    senderParticipant(accts, h.senderAccountId()), toList,
                    Boolean.TRUE.equals(e.getIsRead()), Boolean.TRUE.equals(e.getIsStarred()),
                    Boolean.TRUE.equals(e.getIsImportant()), Boolean.TRUE.equals(h.hasAttachments()),
                    e.getCreatedAt()));
        }
        return out;
    }

    private MailMessageDetail toDetail(MailMailboxEntry entry, MailMessage msg, UUID callerId) {
        List<MailMessageRecipient> recips = recipientRepository.findByMessageId(msg.getId());
        Set<UUID> acctIds = new HashSet<>();
        acctIds.add(msg.getSenderAccountId());
        recips.forEach(r -> acctIds.add(r.getRecipientAccountId()));
        Map<UUID, MailAccount> accts = accountRepository.findAllById(acctIds).stream()
                .collect(Collectors.toMap(MailAccount::getId, a -> a));

        boolean callerIsSender = msg.getSenderAccountId().equals(callerId);
        List<MailParticipant> to = participantsOfType(recips, MailRecipientType.TO, accts);
        List<MailParticipant> cc = participantsOfType(recips, MailRecipientType.CC, accts);
        List<MailParticipant> bcc;
        if (callerIsSender) {
            bcc = participantsOfType(recips, MailRecipientType.BCC, accts);
        } else {
            // a BCC'd caller sees only their own BCC line; everyone else sees none
            bcc = recips.stream()
                    .filter(r -> r.getRecipientType() == MailRecipientType.BCC
                            && r.getRecipientAccountId().equals(callerId))
                    .map(r -> participant(accts.get(r.getRecipientAccountId())))
                    .filter(Objects::nonNull).toList();
        }
        boolean isDraft = entry.getFolder() == MailFolder.DRAFTS;
        List<MailAttachmentResponse> attachments = Boolean.TRUE.equals(msg.getHasAttachments())
                ? attachmentRepository.findByMessageId(msg.getId()).stream()
                        .map(a -> new MailAttachmentResponse(a.getId().toString(), a.getFilename(),
                                a.getContentType(), a.getSizeBytes()))
                        .toList()
                : List.of();
        return new MailMessageDetail(
                entry.getId().toString(), msg.getId().toString(),
                msg.getThreadId() != null ? msg.getThreadId().toString() : null,
                msg.getInReplyTo() != null ? msg.getInReplyTo().toString() : null,
                entry.getFolder().name(), msg.getSubject(), msg.getBodyText(), msg.getBodyHtml(),
                senderParticipant(accts, msg.getSenderAccountId()), to, cc, bcc,
                Boolean.TRUE.equals(entry.getIsRead()), Boolean.TRUE.equals(entry.getIsStarred()),
                Boolean.TRUE.equals(entry.getIsImportant()), Boolean.TRUE.equals(msg.getHasAttachments()),
                isDraft ? msg.getDraftTo() : null, isDraft ? msg.getDraftCc() : null,
                isDraft ? msg.getDraftBcc() : null, msg.getCreatedAt(), attachments,
                entry.getCustomFolderId() != null ? entry.getCustomFolderId().toString() : null);
    }

    private Map<UUID, MailAccount> resolveAccountsFor(Set<UUID> senderIds,
                                                      Map<UUID, List<MailMessageRecipient>> recipsByMsg) {
        Set<UUID> ids = new HashSet<>(senderIds);
        recipsByMsg.values().forEach(list -> list.forEach(r -> {
            if (r.getRecipientType() != MailRecipientType.BCC) {
                ids.add(r.getRecipientAccountId());
            }
        }));
        if (ids.isEmpty()) return Map.of();
        return accountRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(MailAccount::getId, a -> a));
    }

    private List<MailParticipant> participantsOfType(List<MailMessageRecipient> recips,
                                                     MailRecipientType type, Map<UUID, MailAccount> accts) {
        return recips.stream().filter(r -> r.getRecipientType() == type)
                .map(r -> participant(accts.get(r.getRecipientAccountId())))
                .filter(Objects::nonNull).toList();
    }

    private MailParticipant participant(MailAccount a) {
        if (a == null) return null;
        return new MailParticipant(a.getId().toString(),
                a.getLocalPart() + "@" + a.getDomain().getName(), a.getDisplayName());
    }

    /** Sender is always non-null in the DTO; falls back to a placeholder if the
     *  account row is somehow missing (not reachable today — accounts aren't
     *  deletable — but keeps the contract robust). */
    private MailParticipant senderParticipant(Map<UUID, MailAccount> accts, UUID senderId) {
        MailAccount a = accts.get(senderId);
        return a != null ? participant(a) : new MailParticipant(senderId.toString(), "unknown", null);
    }

    private boolean matchesAccount(MailAccount a, String q) {
        if (a == null) return false;
        String email = (a.getLocalPart() + "@" + a.getDomain().getName()).toLowerCase(Locale.ROOT);
        if (email.contains(q)) return true;
        return a.getDisplayName() != null && a.getDisplayName().toLowerCase(Locale.ROOT).contains(q);
    }

    // ── Small helpers ────────────────────────────────────────────────────

    private void validateContent(String subject, String bodyText, String bodyHtml) {
        if (subject != null && subject.length() > maxSubject) {
            throw badRequest("Subject exceeds " + maxSubject + " characters", "MAIL_SUBJECT_TOO_LONG");
        }
        if (bodyText != null && bodyText.length() > maxBody) {
            throw badRequest("Body exceeds " + maxBody + " characters", "MAIL_BODY_TOO_LONG");
        }
        if (bodyHtml != null && bodyHtml.length() > maxBody) {
            throw badRequest("Body exceeds " + maxBody + " characters", "MAIL_BODY_TOO_LONG");
        }
    }

    private int clampSize(int size) {
        if (size <= 0) return defaultPageSize;
        return Math.min(size, maxPageSize);
    }

    private static String joinAddrs(List<String> addrs) {
        if (addrs == null || addrs.isEmpty()) return null;
        String joined = addrs.stream().filter(s -> s != null && !s.isBlank())
                .map(String::trim).collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    private static List<String> splitAddrs(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static UUID parseUuid(String s, String what) {
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            throw badRequest("Invalid " + what, "MAIL_INVALID_ID");
        }
    }

    private static MailApiException notFound(String msg) {
        return new MailApiException(HttpStatus.NOT_FOUND, msg, "MAIL_NOT_FOUND");
    }

    private static MailApiException badRequest(String msg, String code) {
        return new MailApiException(HttpStatus.BAD_REQUEST, msg, code);
    }

    /** Outcome of resolving an optional inReplyTo (both null when not a reply). */
    private record ReplyContext(UUID inReplyTo, UUID threadId) {
    }

    /** Resolved recipient accounts, grouped by type. */
    private record Resolved(List<MailAccount> to, List<MailAccount> cc, List<MailAccount> bcc) {
        List<MailAccount> all() {
            Map<UUID, MailAccount> distinct = new LinkedHashMap<>();
            for (MailAccount a : to) distinct.putIfAbsent(a.getId(), a);
            for (MailAccount a : cc) distinct.putIfAbsent(a.getId(), a);
            for (MailAccount a : bcc) distinct.putIfAbsent(a.getId(), a);
            return new ArrayList<>(distinct.values());
        }

        int count() {
            return all().size();
        }
    }
}
