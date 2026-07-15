package com.anvicorp.api.mail;

import com.anvicorp.api.mail.auth.MailPrincipal;
import com.anvicorp.api.mail.dto.MailCustomFolderRequest;
import com.anvicorp.api.mail.dto.MailMessageDetail;
import com.anvicorp.api.mail.dto.MailRuleRequest;
import com.anvicorp.api.mail.dto.MailSendRequest;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailAccountStatus;
import com.anvicorp.api.mail.entity.MailDomain;
import com.anvicorp.api.mail.entity.MailFolder;
import com.anvicorp.api.mail.entity.MailRole;
import com.anvicorp.api.mail.entity.MailRuleMatchMode;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailCustomFolderRepository;
import com.anvicorp.api.mail.repository.MailDomainRepository;
import com.anvicorp.api.mail.repository.MailMailboxEntryRepository;
import com.anvicorp.api.mail.repository.MailMessageRecipientRepository;
import com.anvicorp.api.mail.repository.MailMessageRepository;
import com.anvicorp.api.mail.repository.MailRuleRepository;
import com.anvicorp.api.mail.rules.MailRuleAction;
import com.anvicorp.api.mail.rules.MailRuleActionType;
import com.anvicorp.api.mail.rules.MailRuleCondition;
import com.anvicorp.api.mail.rules.MailRuleField;
import com.anvicorp.api.mail.rules.MailRuleOperator;
import com.anvicorp.api.mail.service.MailCustomFolderService;
import com.anvicorp.api.mail.service.MailMessageService;
import com.anvicorp.api.mail.service.MailRuleService;
import com.anvicorp.api.mail.service.MailSseService;
import com.anvicorp.api.mail.sse.MailDeliveredEvent;
import com.anvicorp.api.mail.sse.MailSseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A8 real-time verification against H2. The SSE fan-out runs from a
 * {@code @TransactionalEventListener(AFTER_COMMIT)}, so send() must commit — the
 * test class is deliberately NOT @Transactional (each service call commits its own
 * tx, mirroring MailRuleIT). {@link Capture} records the post-commit
 * {@link MailDeliveredEvent} to assert the walled recipient + the A7-resolved
 * placement; the registry tests exercise register/publish/prune directly. The
 * browser fetch-stream + real HTTP frames are the served/manual gate.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:anvi_a8;DB_CLOSE_DELAY=-1",
        "app.webmail.seed.admin-enabled=false",
})
@Import(MailSseIT.Capture.class)
class MailSseIT {

    @TestConfiguration
    static class Capture {
        final List<MailDeliveredEvent> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(MailDeliveredEvent e) {
            events.add(e);
        }
    }

    @Autowired Capture capture;
    @Autowired MailSseService sse;
    @Autowired MailRuleService rules;
    @Autowired MailCustomFolderService folders;
    @Autowired MailMessageService mail;
    @Autowired MailAccountRepository accounts;
    @Autowired MailDomainRepository domains;
    @Autowired MailMailboxEntryRepository entries;
    @Autowired MailRuleRepository ruleRepo;
    @Autowired MailCustomFolderRepository folderRepo;
    @Autowired MailMessageRecipientRepository recipients;
    @Autowired MailMessageRepository messages;

    private MailAccount alice, bob, carol;
    private MailPrincipal pAlice, pBob;

    @BeforeEach
    void seed() {
        capture.events.clear();
        entries.deleteAll();
        recipients.deleteAll();
        messages.deleteAll();
        ruleRepo.deleteAll();
        folderRepo.deleteAll();
        accounts.deleteAll();
        domains.deleteAll();

        MailDomain anvi = domains.save(MailDomain.builder().name("anvicorp.com").displayName("Anvi").active(true).build());
        alice = account(anvi, "alice");
        bob = account(anvi, "bob");
        carol = account(anvi, "carol");
        pAlice = principal(alice);
        pBob = principal(bob);
    }

    private MailAccount account(MailDomain d, String local) {
        return accounts.save(MailAccount.builder()
                .domain(d).localPart(local).displayName(local).passwordHash("x")
                .role(MailRole.USER).status(MailAccountStatus.ACTIVE)
                .mustChangePassword(false).requireChangeOnFirstLogin(false).build());
    }

    private MailPrincipal principal(MailAccount a) {
        return new MailPrincipal(a.getId(), a.getLocalPart() + "@" + a.getDomain().getName(),
                a.getDisplayName(), a.getDomain().getId(), a.getRole(), false);
    }

    private MailMessageDetail aliceToBob(String subject) {
        return mail.send(pAlice, new MailSendRequest(List.of("bob"), null, null, subject, "body", null, null));
    }

    private void bobRule(String name, MailRuleAction action) {
        rules.create(pBob, new MailRuleRequest(name, MailRuleMatchMode.ALL, true, false,
                List.of(new MailRuleCondition(MailRuleField.FROM, MailRuleOperator.CONTAINS, "alice")),
                List.of(action)));
    }

    // ── 1. Walled publish: A→B emits ONE event to B (the recipient), never to C ──
    @Test
    void deliver_emitsInboxEventToRecipientOnly_walledFromThirdParty() {
        aliceToBob("hello");
        assertEquals(1, capture.events.size(), "one event — the recipient's; the SENT copy does not emit");
        MailDeliveredEvent e = capture.events.get(0);
        assertEquals(bob.getId(), e.recipientId());
        assertEquals("INBOX", e.folder());
        assertNull(e.customFolderId());
        assertEquals("NEW_MAIL", MailSseEvent.newMail(e.folder(), e.customFolderId(), e.messageId()).type());
        // Walled: nothing addressed to carol (a third account).
        assertTrue(capture.events.stream().noneMatch(x -> x.recipientId().equals(carol.getId())));
    }

    // ── 2. A7 rule → ARCHIVE: the event carries ARCHIVE, not a blind INBOX ───────
    @Test
    void ruleFilingToArchive_eventCarriesArchive() {
        bobRule("alice→Archive", new MailRuleAction(MailRuleActionType.MOVE_TO_SYSTEM_FOLDER, MailFolder.ARCHIVE, null));
        aliceToBob("hello");
        MailDeliveredEvent e = capture.events.get(0);
        assertEquals("ARCHIVE", e.folder());
        assertNull(e.customFolderId());
    }

    // ── 3. A7 rule → custom folder: the event carries the custom folder id ───────
    @Test
    void ruleFilingToCustomFolder_eventCarriesCustomFolderId() {
        UUID work = UUID.fromString(folders.create(pBob, new MailCustomFolderRequest("Work")).id());
        bobRule("alice→Work", new MailRuleAction(MailRuleActionType.MOVE_TO_CUSTOM_FOLDER, null, work.toString()));
        aliceToBob("hello");
        MailDeliveredEvent e = capture.events.get(0);
        assertEquals(work.toString(), e.customFolderId(), "custom placement → client bumps that folder, no INBOX popup");
    }

    // ── 4. Delivery succeeds with no subscribers AND when a publish would fail ───
    @Test
    void deliverySucceeds_whenNoSubscribers_andWhenPublishFails() {
        // No streams connected → send still delivers.
        MailMessageDetail a = aliceToBob("one");
        assertNotNull(a.messageId());
        assertEquals(1, mail.listFolder(pBob, MailFolder.INBOX, 0, 25).total());

        // A dead (completed) emitter: the post-commit publish throws internally, is
        // swallowed (best-effort), the emitter is pruned, and the send still delivers.
        SseEmitter dead = sse.register(bob.getId());
        dead.complete();
        MailMessageDetail b = aliceToBob("two");
        assertNotNull(b.messageId());
        assertEquals(2, mail.listFolder(pBob, MailFolder.INBOX, 0, 25).total());
        assertEquals(0, sse.connectionCount(bob.getId()), "dead emitter pruned on failed publish");
    }

    // ── 5. Registry: register/publish count + walling + prune-on-failed-send ─────
    @Test
    void registry_publishCountsWalledAndPrunesDeadEmitters() {
        SseEmitter b1 = sse.register(bob.getId());
        sse.register(bob.getId());
        sse.register(carol.getId());
        assertEquals(2, sse.connectionCount(bob.getId()));
        assertEquals(1, sse.connectionCount(carol.getId()));

        MailSseEvent event = MailSseEvent.newMail("INBOX", null, UUID.randomUUID().toString());
        assertEquals(2, sse.publishNewMail(bob.getId(), event), "sent to both of bob's streams");
        assertEquals(1, sse.connectionCount(carol.getId()), "carol's stream untouched (walled)");
        assertEquals(0, sse.publishNewMail(UUID.randomUUID(), event), "unknown account → no streams");

        // A completed emitter fails its next send and is pruned.
        b1.complete();
        sse.publishNewMail(bob.getId(), event);
        assertEquals(1, sse.connectionCount(bob.getId()), "the completed emitter was pruned");
    }
}
