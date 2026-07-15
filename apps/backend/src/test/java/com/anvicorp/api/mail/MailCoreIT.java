package com.anvicorp.api.mail;

import com.anvicorp.api.mail.auth.MailPrincipal;
import com.anvicorp.api.mail.dto.MailDraftRequest;
import com.anvicorp.api.mail.dto.MailFlagsRequest;
import com.anvicorp.api.mail.dto.MailFolderCount;
import com.anvicorp.api.mail.dto.MailMessageDetail;
import com.anvicorp.api.mail.dto.MailMessageSummary;
import com.anvicorp.api.mail.dto.MailPage;
import com.anvicorp.api.mail.dto.MailSendRequest;
import com.anvicorp.api.mail.dto.MailThreadResponse;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailAccountStatus;
import com.anvicorp.api.mail.entity.MailDomain;
import com.anvicorp.api.mail.entity.MailFolder;
import com.anvicorp.api.mail.entity.MailRole;
import com.anvicorp.api.mail.exception.MailApiException;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailDomainRepository;
import com.anvicorp.api.mail.repository.MailMailboxEntryRepository;
import com.anvicorp.api.mail.repository.MailMessageRecipientRepository;
import com.anvicorp.api.mail.repository.MailMessageRepository;
import com.anvicorp.api.mail.service.MailMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A2 mail-core verification against in-memory H2. Drives {@link MailMessageService}
 * directly with constructed principals (the A1 chain/JWT is verified separately).
 * Two same-domain accounts (alice, bob, dave @anvicorp.com) + one cross-domain
 * (carol @other.com) exercise walled send, encryption-at-rest, drafts, threads,
 * flags, search, and the walling 404s.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:anvi_a2;DB_CLOSE_DELAY=-1",
        "app.webmail.seed.admin-enabled=false"
})
class MailCoreIT {

    @Autowired MailMessageService mail;
    @Autowired MailDomainRepository domains;
    @Autowired MailAccountRepository accounts;
    @Autowired MailMailboxEntryRepository entries;
    @Autowired MailMessageRecipientRepository recipients;
    @Autowired MailMessageRepository messages;
    @Autowired PasswordEncoder enc;
    @Autowired JdbcTemplate jdbc;

    private MailDomain anvi;
    private MailAccount alice, bob, dave, carol;
    private MailPrincipal pAlice, pBob, pDave, pCarol;

    @BeforeEach
    void seed() {
        entries.deleteAll();
        recipients.deleteAll();
        messages.deleteAll();
        accounts.deleteAll();
        domains.deleteAll();

        anvi = domains.save(MailDomain.builder().name("anvicorp.com").displayName("Anvi").active(true).build());
        MailDomain other = domains.save(MailDomain.builder().name("other.com").displayName("Other").active(true).build());
        alice = account(anvi, "alice", "Alice");
        bob = account(anvi, "bob", "Bob");
        dave = account(anvi, "dave", "Dave");
        carol = account(other, "carol", "Carol");
        pAlice = principal(alice);
        pBob = principal(bob);
        pDave = principal(dave);
        pCarol = principal(carol);
    }

    private MailAccount account(MailDomain d, String local, String display) {
        return accounts.save(MailAccount.builder()
                .domain(d).localPart(local).displayName(display)
                .passwordHash(enc.encode("pw-" + local))
                .role(MailRole.USER).status(MailAccountStatus.ACTIVE)
                .mustChangePassword(false).requireChangeOnFirstLogin(false)
                .build());
    }

    private MailPrincipal principal(MailAccount a) {
        return new MailPrincipal(a.getId(), a.getLocalPart() + "@" + a.getDomain().getName(),
                a.getDisplayName(), a.getDomain().getId(), a.getRole(), false);
    }

    // ── 1. Walled send + body encrypted at rest + delivery ───────────────────
    @Test
    void send_deliversWalled_andEncryptsBodyAtRest() {
        MailMessageDetail sent = mail.send(pAlice, new MailSendRequest(
                List.of("bob"), null, null, "Hello Bob", "TOP SECRET BODY ALPHA", "<p>secret html</p>", null));

        assertEquals("SENT", sent.folder());
        assertEquals("alice@anvicorp.com", sent.from().email());
        assertEquals(1, sent.to().size());
        assertEquals("bob@anvicorp.com", sent.to().get(0).email());

        // Sender has a SENT entry; recipient has an UNREAD INBOX entry.
        assertEquals(1, inbox(pBob).total());
        MailMessageSummary bobRow = inbox(pBob).items().get(0);
        assertFalse(bobRow.isRead());
        assertEquals(1, unread(pBob, "INBOX"));
        assertEquals(1, total(pAlice, "SENT"));

        // CIPHERTEXT-AT-REST: the raw body columns are AES envelopes, not plaintext.
        String rawText = jdbc.queryForObject(
                "SELECT body_text FROM mail_messages WHERE CAST(id AS VARCHAR) = ?", String.class, sent.messageId());
        String rawHtml = jdbc.queryForObject(
                "SELECT body_html FROM mail_messages WHERE CAST(id AS VARCHAR) = ?", String.class, sent.messageId());
        assertNotNull(rawText);
        assertNotEquals("TOP SECRET BODY ALPHA", rawText);
        assertFalse(rawText.contains("TOP SECRET"), "stored body must not contain plaintext");
        assertFalse(rawHtml.contains("secret html"), "stored html must not contain plaintext");
        assertTrue(Base64.getDecoder().decode(rawText).length > 12, "stored body is an AES (IV+ct) envelope");

        // ROUND-TRIP: bob decrypts the original plaintext on read.
        MailMessageDetail got = mail.getEntry(pBob, UUID.fromString(bobRow.entryId()));
        assertEquals("TOP SECRET BODY ALPHA", got.bodyText());
        assertEquals("<p>secret html</p>", got.bodyHtml());

        // Reading flips the read flag (explicit) → unread count drops.
        mail.setFlags(pBob, UUID.fromString(bobRow.entryId()), new MailFlagsRequest(true, null, null));
        assertEquals(0, unread(pBob, "INBOX"));
    }

    // ── 2. Cross-domain / unknown recipients rejected (422), nothing delivered ─
    @Test
    void crossDomainAndUnknownRecipients_rejected422() {
        MailApiException crossDomain = assertThrows(MailApiException.class, () -> mail.send(pAlice,
                new MailSendRequest(List.of("carol@other.com"), null, null, "x", "y", null, null)));
        assertEquals(422, crossDomain.getStatus().value());
        assertEquals("MAIL_RECIPIENT_INVALID", crossDomain.getCode());

        MailApiException unknown = assertThrows(MailApiException.class, () -> mail.send(pAlice,
                new MailSendRequest(List.of("nobody"), null, null, "x", "y", null, null)));
        assertEquals(422, unknown.getStatus().value());

        // Nothing persisted / delivered.
        assertEquals(0, total(pAlice, "SENT"));
        assertEquals(0, inbox(pCarol).total());
        assertEquals(0, messages.count());
    }

    // ── 3. Single-row draft → sendDraft (no duplicate SENT) ──────────────────
    @Test
    void draft_isSingleRow_andSendDraftDoesNotDuplicate() {
        MailMessageDetail draft = mail.saveDraft(pAlice, new MailDraftRequest(
                List.of("bob"), null, null, "Draft 1", "draft body", null, null));
        assertEquals("DRAFTS", draft.folder());
        assertEquals(1, total(pAlice, "DRAFTS"));
        UUID draftEntryId = UUID.fromString(draft.entryId());

        mail.updateDraft(pAlice, draftEntryId, new MailDraftRequest(
                List.of("bob"), null, null, "Draft 1 updated", "draft body 2", null, null));
        assertEquals(1, total(pAlice, "DRAFTS")); // still one row

        MailMessageDetail send = mail.sendDraft(pAlice, draftEntryId, null);
        assertEquals("SENT", send.folder());
        // SAME row transitioned DRAFTS → SENT (not a new entry).
        assertEquals(draftEntryId.toString(), send.entryId());
        assertEquals(0, total(pAlice, "DRAFTS"));
        assertEquals(1, total(pAlice, "SENT"));
        // Exactly one SENT entry for alice on that message — no duplicate.
        assertEquals(1, sentEntriesForMessage(alice.getId(), send.messageId()));
        assertEquals(1, inbox(pBob).total()); // bob got the delivery
    }

    // ── 4. Threads: reply shares thread_id + sets in_reply_to; walled view ───
    @Test
    void reply_sharesThread_andThreadViewIsWalled() {
        MailMessageDetail root = mail.send(pAlice, new MailSendRequest(
                List.of("bob"), null, null, "Hello Bob", "body", null, null));
        String threadId = root.threadId();
        assertEquals(root.messageId(), threadId); // root points at itself

        MailMessageDetail reply = mail.send(pBob, new MailSendRequest(
                List.of("alice"), null, null, "Re: Hello Bob", "reply body", null, root.messageId()));
        assertEquals(threadId, reply.threadId());
        assertEquals(root.messageId(), reply.inReplyTo());

        // Alice sees both messages in the thread (her SENT root + her INBOX reply), oldest first.
        MailThreadResponse thread = mail.getThread(pAlice, UUID.fromString(threadId));
        assertEquals(2, thread.messages().size());
        assertTrue(thread.messages().get(0).createdAt().compareTo(thread.messages().get(1).createdAt()) <= 0);

        // Carol participates in nothing here → 404 (anti-enumeration).
        MailApiException ex = assertThrows(MailApiException.class,
                () -> mail.getThread(pCarol, UUID.fromString(threadId)));
        assertEquals(404, ex.getStatus().value());
    }

    // ── 5. Walling: a caller can only read/flag/move their OWN entry ──────────
    @Test
    void walling_foreignEntryIsAlways404() {
        MailMessageDetail sent = mail.send(pAlice, new MailSendRequest(
                List.of("bob"), null, null, "subj", "body", null, null));
        UUID aliceSentEntry = UUID.fromString(sent.entryId());

        // Bob has no entry with alice's SENT entry id; carol has nothing at all.
        assertEquals(404, assertThrows(MailApiException.class,
                () -> mail.getEntry(pBob, aliceSentEntry)).getStatus().value());
        assertEquals(404, assertThrows(MailApiException.class,
                () -> mail.getEntry(pCarol, aliceSentEntry)).getStatus().value());
        assertEquals(404, assertThrows(MailApiException.class,
                () -> mail.setFlags(pBob, aliceSentEntry, new MailFlagsRequest(true, null, null))).getStatus().value());
        assertEquals(404, assertThrows(MailApiException.class,
                () -> mail.move(pBob, aliceSentEntry, MailFolder.TRASH)).getStatus().value());

        // Move + soft-delete + permanent tombstone on the OWNER's entry.
        UUID bobInbox = UUID.fromString(inbox(pBob).items().get(0).entryId());
        assertEquals("ARCHIVE", mail.move(pBob, bobInbox, MailFolder.ARCHIVE).folder());
        assertEquals("TRASH", mail.softDelete(pBob, bobInbox).folder());
        mail.permanentDelete(pBob, bobInbox);
        // Tombstoned: excluded from listings + reads.
        assertEquals(404, assertThrows(MailApiException.class,
                () -> mail.getEntry(pBob, bobInbox)).getStatus().value());
        assertEquals(0, total(pBob, "TRASH"));
    }

    // ── 6. BCC is hidden from other recipients ───────────────────────────────
    @Test
    void bcc_isHiddenFromOtherRecipients() {
        MailMessageDetail sent = mail.send(pAlice, new MailSendRequest(
                List.of("bob"), null, List.of("dave"), "bcc test", "body", null, null));

        // Sender sees the BCC line.
        assertEquals(1, sent.bcc().size());
        assertEquals("dave@anvicorp.com", sent.bcc().get(0).email());

        // Bob (a TO recipient) sees NO bcc.
        MailMessageDetail bobView = mail.getEntry(pBob, UUID.fromString(inbox(pBob).items().get(0).entryId()));
        assertTrue(bobView.bcc() == null || bobView.bcc().isEmpty());

        // Dave (the BCC'd party) sees only his own bcc line.
        MailMessageDetail daveView = mail.getEntry(pDave, UUID.fromString(inbox(pDave).items().get(0).entryId()));
        assertEquals(1, daveView.bcc().size());
        assertEquals("dave@anvicorp.com", daveView.bcc().get(0).email());

        // Bob cannot find dave via participant search (BCC excluded from matching).
        assertEquals(0, mail.search(pBob, "dave", 0, 25).total());
    }

    // ── 7. Search: subject + participants, caller-scoped, body deferred ──────
    @Test
    void search_subjectAndParticipants_callerScoped_bodyNotSearched() {
        mail.send(pAlice, new MailSendRequest(
                List.of("bob"), null, null, "Quarterly Report", "CONFIDENTIAL-NUMBERS-123", null, null));

        // Subject match (alice's own SENT entry).
        assertEquals(1, mail.search(pAlice, "quarterly", 0, 25).total());
        // Participant match (bob is a visible recipient).
        assertEquals(1, mail.search(pAlice, "bob", 0, 25).total());
        // Body is NOT searched (deferred) — a body substring yields nothing.
        assertEquals(0, mail.search(pAlice, "CONFIDENTIAL-NUMBERS", 0, 25).total());
        // Caller-scoped: carol has no entries, so no results.
        assertEquals(0, mail.search(pCarol, "quarterly", 0, 25).total());
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private MailPage<MailMessageSummary> inbox(MailPrincipal p) {
        return mail.listFolder(p, MailFolder.INBOX, 0, 25);
    }

    private long total(MailPrincipal p, String folder) {
        return count(p, folder).total();
    }

    private long unread(MailPrincipal p, String folder) {
        return count(p, folder).unread();
    }

    private MailFolderCount count(MailPrincipal p, String folder) {
        return mail.folderCounts(p).stream().filter(c -> c.folder().equals(folder)).findFirst().orElseThrow();
    }

    private long sentEntriesForMessage(UUID accountId, String messageId) {
        return entries.findAll().stream()
                .filter(e -> e.getAccountId().equals(accountId)
                        && e.getMessageId().equals(UUID.fromString(messageId))
                        && e.getFolder() == MailFolder.SENT)
                .count();
    }
}
