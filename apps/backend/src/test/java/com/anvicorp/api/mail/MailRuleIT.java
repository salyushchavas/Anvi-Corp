package com.anvicorp.api.mail;

import com.anvicorp.api.mail.auth.MailPrincipal;
import com.anvicorp.api.mail.dto.MailCustomFolderRequest;
import com.anvicorp.api.mail.dto.MailMessageDetail;
import com.anvicorp.api.mail.dto.MailMessageSummary;
import com.anvicorp.api.mail.dto.MailRuleRequest;
import com.anvicorp.api.mail.dto.MailRuleResponse;
import com.anvicorp.api.mail.dto.MailSendRequest;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailAccountStatus;
import com.anvicorp.api.mail.entity.MailDomain;
import com.anvicorp.api.mail.entity.MailFolder;
import com.anvicorp.api.mail.entity.MailMailboxEntry;
import com.anvicorp.api.mail.entity.MailRole;
import com.anvicorp.api.mail.entity.MailRule;
import com.anvicorp.api.mail.entity.MailRuleMatchMode;
import com.anvicorp.api.mail.exception.MailApiException;
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
import com.anvicorp.api.mail.rules.MailRuleEngine;
import com.anvicorp.api.mail.rules.MailRuleEnvelope;
import com.anvicorp.api.mail.rules.MailRuleField;
import com.anvicorp.api.mail.rules.MailRuleOperator;
import com.anvicorp.api.mail.service.MailCustomFolderService;
import com.anvicorp.api.mail.service.MailMessageService;
import com.anvicorp.api.mail.service.MailRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A7 inbox-rules verification against H2. Rules run at inbound delivery only;
 * the engine is FAIL-OPEN. Covers filing/flagging/delete-to-Trash, ALL/ANY, order +
 * stop_processing, the A6 custom-folder target (incl. the deleted-target fail-open),
 * malformed-rule fail-open, disabled rules, walling, and the SENT-copy-untouched
 * invariant. The no-rules A2/A6 regression is covered by MailCoreIT + the A6 tests
 * staying green in the full suite. (Live Postgres differs only in the TEXT column
 * type + real concurrency; H2 exercises the same JPA/Jackson paths.)
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:anvi_a7;DB_CLOSE_DELAY=-1",
        "app.webmail.seed.admin-enabled=false",
})
class MailRuleIT {

    @Autowired MailRuleService rules;
    @Autowired MailRuleEngine engine;
    @Autowired MailCustomFolderService folders;
    @Autowired MailMessageService mail;
    @Autowired MailAccountRepository accounts;
    @Autowired MailDomainRepository domains;
    @Autowired MailMailboxEntryRepository entries;
    @Autowired MailRuleRepository ruleRepo;
    @Autowired MailCustomFolderRepository folderRepo;
    @Autowired MailMessageRecipientRepository recipients;
    @Autowired MailMessageRepository messages;

    private MailAccount alice, bob;
    private MailPrincipal pAlice, pBob;

    @BeforeEach
    void seed() {
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

    // ── helpers ────────────────────────────────────────────────────────────────

    private MailMessageDetail sendFromBob(String subject) {
        return mail.send(pBob, new MailSendRequest(List.of("alice"), null, null, subject, "body", null, null));
    }

    private MailMailboxEntry entryFor(MailAccount who, MailMessageDetail sent) {
        UUID msgId = UUID.fromString(sent.messageId());
        return entries.findAll().stream()
                .filter(e -> e.getAccountId().equals(who.getId()) && e.getMessageId().equals(msgId))
                .findFirst().orElseThrow();
    }

    private long aliceInboxTotal() {
        return mail.listFolder(pAlice, MailFolder.INBOX, 0, 25).total();
    }

    private MailMessageSummary aliceInboxTop() {
        return mail.listFolder(pAlice, MailFolder.INBOX, 0, 25).items().get(0);
    }

    private MailRuleCondition cond(MailRuleField f, MailRuleOperator op, String v) {
        return new MailRuleCondition(f, op, v);
    }

    private MailRuleAction sysAct(MailFolder f) {
        return new MailRuleAction(MailRuleActionType.MOVE_TO_SYSTEM_FOLDER, f, null);
    }

    private MailRuleAction customAct(UUID folderId) {
        return new MailRuleAction(MailRuleActionType.MOVE_TO_CUSTOM_FOLDER, null, folderId.toString());
    }

    private MailRuleAction flag(MailRuleActionType t) {
        return new MailRuleAction(t, null, null);
    }

    private MailRuleResponse rule(MailPrincipal p, String name, MailRuleMatchMode mode, boolean stop,
                                  List<MailRuleCondition> conds, List<MailRuleAction> acts) {
        return rules.create(p, new MailRuleRequest(name, mode, true, stop, conds, acts));
    }

    private UUID folder(MailPrincipal p, String name) {
        return UUID.fromString(folders.create(p, new MailCustomFolderRequest(name)).id());
    }

    // ── 1. FROM contains → MOVE_TO_CUSTOM_FOLDER: filed into F, absent from INBOX ─
    @Test
    void fromContains_movesToCustomFolder_notInbox() {
        UUID work = folder(pAlice, "Work");
        rule(pAlice, "boss→Work", MailRuleMatchMode.ALL, false,
                List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob")), List.of(customAct(work)));

        MailMessageDetail sent = sendFromBob("hello");
        MailMailboxEntry e = entryFor(alice, sent);
        assertEquals(work, e.getCustomFolderId());
        assertEquals(0, aliceInboxTotal(), "custom-foldered entry must not count in INBOX");
        assertEquals(1, mail.listCustomFolder(pAlice, work, 0, 25).total());
    }

    // ── 2. SUBJECT contains invoice → STAR + MARK_READ (stays in INBOX) ──────────
    @Test
    void subjectContains_starsAndMarksRead() {
        rule(pAlice, "invoices", MailRuleMatchMode.ALL, false,
                List.of(cond(MailRuleField.SUBJECT, MailRuleOperator.CONTAINS, "invoice")),
                List.of(flag(MailRuleActionType.STAR), flag(MailRuleActionType.MARK_READ)));

        sendFromBob("Your invoice #12");
        MailMessageSummary top = aliceInboxTop();
        assertEquals("INBOX", top.folder());
        assertTrue(top.isStarred());
        assertTrue(top.isRead());
        assertEquals(1, aliceInboxTotal());
    }

    // ── 3. HAS_ATTACHMENT IS_TRUE → MOVE_TO_SYSTEM_FOLDER(ARCHIVE) (engine level) ─
    @Test
    void hasAttachment_movesToArchive_onlyWhenTrue() {
        rule(pAlice, "attachments→Archive", MailRuleMatchMode.ALL, false,
                List.of(cond(MailRuleField.HAS_ATTACHMENT, MailRuleOperator.IS_TRUE, null)),
                List.of(sysAct(MailFolder.ARCHIVE)));

        MailRuleEnvelope withAtt = new MailRuleEnvelope("bob@anvicorp.com",
                List.of("alice@anvicorp.com"), List.of(), "x", true);
        MailRuleEnvelope without = new MailRuleEnvelope("bob@anvicorp.com",
                List.of("alice@anvicorp.com"), List.of(), "x", false);

        assertEquals(MailFolder.ARCHIVE, engine.resolveDelivery(alice.getId(), withAtt).getFolder());
        assertEquals(MailFolder.INBOX, engine.resolveDelivery(alice.getId(), without).getFolder());
    }

    // ── 4. DELETE → TRASH ────────────────────────────────────────────────────────
    @Test
    void deleteAction_routesToTrash() {
        rule(pAlice, "bob→trash", MailRuleMatchMode.ALL, false,
                List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob")),
                List.of(flag(MailRuleActionType.DELETE)));

        MailMessageDetail sent = sendFromBob("hello");
        assertEquals(MailFolder.TRASH, entryFor(alice, sent).getFolder());
        assertEquals(0, aliceInboxTotal());
        assertEquals(1, mail.listFolder(pAlice, MailFolder.TRASH, 0, 25).total());
    }

    // ── 5. stop_processing halts later rules ─────────────────────────────────────
    @Test
    void stopProcessing_haltsLaterRules() {
        rule(pAlice, "r1-archive-stop", MailRuleMatchMode.ALL, true,
                List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob")), List.of(sysAct(MailFolder.ARCHIVE)));
        rule(pAlice, "r2-trash", MailRuleMatchMode.ALL, false,
                List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob")), List.of(sysAct(MailFolder.TRASH)));

        MailMessageDetail sent = sendFromBob("hello");
        assertEquals(MailFolder.ARCHIVE, entryFor(alice, sent).getFolder(), "stop halted r2");
    }

    // ── 6. Without stop, the last matching rule wins (order matters) ─────────────
    @Test
    void withoutStop_lastMatchingRuleWins() {
        rule(pAlice, "r1-archive", MailRuleMatchMode.ALL, false,
                List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob")), List.of(sysAct(MailFolder.ARCHIVE)));
        rule(pAlice, "r2-trash", MailRuleMatchMode.ALL, false,
                List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob")), List.of(sysAct(MailFolder.TRASH)));

        MailMessageDetail sent = sendFromBob("hello");
        assertEquals(MailFolder.TRASH, entryFor(alice, sent).getFolder());
    }

    // ── 7. ALL requires every condition; ANY needs just one ─────────────────────
    @Test
    void matchModeAll_vs_any() {
        // ALL: FROM bob AND SUBJECT invoice.
        rule(pAlice, "all", MailRuleMatchMode.ALL, false,
                List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob"),
                        cond(MailRuleField.SUBJECT, MailRuleOperator.CONTAINS, "invoice")),
                List.of(sysAct(MailFolder.ARCHIVE)));

        sendFromBob("just hello");           // subject fails → no match → INBOX
        assertEquals(1, aliceInboxTotal());
        assertEquals(0, mail.listFolder(pAlice, MailFolder.ARCHIVE, 0, 25).total());

        sendFromBob("an invoice inside");     // both match → ARCHIVE
        assertEquals(1, aliceInboxTotal(), "the non-matching one stays in INBOX");
        assertEquals(1, mail.listFolder(pAlice, MailFolder.ARCHIVE, 0, 25).total());

        ruleRepo.deleteAll();
        // ANY: FROM bob OR SUBJECT invoice — a plain hello from bob still matches.
        rule(pAlice, "any", MailRuleMatchMode.ANY, false,
                List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob"),
                        cond(MailRuleField.SUBJECT, MailRuleOperator.CONTAINS, "invoice")),
                List.of(sysAct(MailFolder.ARCHIVE)));
        MailMessageDetail any = sendFromBob("plain hello");
        assertEquals(MailFolder.ARCHIVE, entryFor(alice, any).getFolder());
    }

    // ── 8. FAIL-OPEN: a malformed rule delivers to INBOX/unread, never drops ─────
    @Test
    void failOpen_malformedRule_deliversToInbox() {
        ruleRepo.save(MailRule.builder()
                .accountId(alice.getId()).name("corrupt").priority(0).enabled(true)
                .matchMode(MailRuleMatchMode.ALL).stopProcessing(false)
                .conditionsJson("{ this is not valid json")
                .actionsJson("[]")
                .build());

        MailMessageDetail sent = sendFromBob("hello");
        MailMailboxEntry e = entryFor(alice, sent);
        assertEquals(MailFolder.INBOX, e.getFolder());
        assertNull(e.getCustomFolderId());
        assertFalse(e.getIsRead(), "fail-open delivers UNREAD");
        assertEquals(1, aliceInboxTotal(), "message delivered, never dropped");
    }

    // ── 9. FAIL-OPEN: a MOVE target folder deleted after save → INBOX, not dropped ─
    @Test
    void failOpen_deletedCustomFolderTarget_deliversToInbox() {
        UUID work = folder(pAlice, "Work");
        rule(pAlice, "bob→Work", MailRuleMatchMode.ALL, false,
                List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob")), List.of(customAct(work)));

        // First delivery files into Work (target exists).
        assertEquals(work, entryFor(alice, sendFromBob("one")).getCustomFolderId());

        // A6 lets the user delete a folder a rule still points at.
        folders.delete(pAlice, work);

        // Next delivery: dangling target → fail-open to INBOX/unread, NOT dropped.
        MailMessageDetail sent = sendFromBob("two");
        MailMailboxEntry e = entryFor(alice, sent);
        assertEquals(MailFolder.INBOX, e.getFolder());
        assertNull(e.getCustomFolderId());
        assertFalse(e.getIsRead());
        assertTrue(aliceInboxTotal() >= 1, "second message delivered to INBOX");
    }

    // ── 10. A disabled rule is ignored ──────────────────────────────────────────
    @Test
    void disabledRule_isIgnored() {
        MailRuleResponse r = rule(pAlice, "off", MailRuleMatchMode.ALL, false,
                List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob")), List.of(sysAct(MailFolder.ARCHIVE)));
        rules.setEnabled(pAlice, UUID.fromString(r.id()), false);

        assertEquals(MailFolder.INBOX, entryFor(alice, sendFromBob("hello")).getFolder());
    }

    // ── 11. Walling: foreign rule → 404; foreign folder target at save → 400 ─────
    @Test
    void walling_foreignRule404_andForeignFolderTarget400() {
        MailRuleResponse aliceRule = rule(pAlice, "mine", MailRuleMatchMode.ALL, false,
                List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob")), List.of(sysAct(MailFolder.ARCHIVE)));
        UUID rid = UUID.fromString(aliceRule.id());

        assertEquals(404, assertThrows(MailApiException.class,
                () -> rules.delete(pBob, rid)).getStatus().value());
        assertEquals(404, assertThrows(MailApiException.class,
                () -> rules.update(pBob, rid, new MailRuleRequest("x", MailRuleMatchMode.ALL, true, false,
                        List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob")),
                        List.of(sysAct(MailFolder.ARCHIVE))))).getStatus().value());

        // alice cannot target bob's custom folder at save time.
        UUID bobFolder = folder(pBob, "BobWork");
        assertEquals(400, assertThrows(MailApiException.class,
                () -> rule(pAlice, "cross", MailRuleMatchMode.ALL, false,
                        List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob")),
                        List.of(customAct(bobFolder)))).getStatus().value());
    }

    // ── 12. The SENDER's SENT copy is never touched by the recipient's rules ─────
    @Test
    void senderSentCopy_unaffectedByRecipientRules() {
        rule(pAlice, "bob→trash", MailRuleMatchMode.ALL, false,
                List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob")),
                List.of(flag(MailRuleActionType.DELETE)));

        MailMessageDetail sent = sendFromBob("hello");
        MailMailboxEntry bobEntry = entryFor(bob, sent);
        assertEquals(MailFolder.SENT, bobEntry.getFolder(), "sender's copy stays SENT");
        assertNull(bobEntry.getCustomFolderId());
        assertTrue(bobEntry.getIsRead());
        // The recipient's copy WAS ruled to TRASH.
        assertEquals(MailFolder.TRASH, entryFor(alice, sent).getFolder());
    }

    // ── 13. No rules → byte-for-byte A2 delivery (unread INBOX) ──────────────────
    @Test
    void noRules_plainInboxDelivery() {
        MailMessageDetail sent = sendFromBob("hello");
        MailMailboxEntry e = entryFor(alice, sent);
        assertEquals(MailFolder.INBOX, e.getFolder());
        assertNull(e.getCustomFolderId());
        assertFalse(e.getIsRead());
        assertFalse(e.getIsStarred());
        assertFalse(e.getIsImportant());
    }

    // ── 14. SENT/DRAFTS are outbound-only — rejected as MOVE targets at save ─────
    @Test
    void moveToSystemFolder_sentOrDrafts_rejectedAtSave() {
        assertEquals(400, assertThrows(MailApiException.class,
                () -> rule(pAlice, "toSent", MailRuleMatchMode.ALL, false,
                        List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob")),
                        List.of(sysAct(MailFolder.SENT)))).getStatus().value());
        assertEquals(400, assertThrows(MailApiException.class,
                () -> rule(pAlice, "toDrafts", MailRuleMatchMode.ALL, false,
                        List.of(cond(MailRuleField.FROM, MailRuleOperator.CONTAINS, "bob")),
                        List.of(sysAct(MailFolder.DRAFTS)))).getStatus().value());
    }

    // ── 15. Self-addressed send + a legacy SENT-target rule must NOT collide ─────
    // Guards the delivery-review finding: a rule placing the sender's own inbound entry
    // into SENT would collide with their SENT copy on the unique key and roll back the
    // send (message lost). The engine clamps SENT/DRAFTS so both copies coexist.
    @Test
    void selfAddressedSend_legacySentRule_noCollision_deliversBothCopies() {
        ruleRepo.save(MailRule.builder()
                .accountId(alice.getId()).name("legacy-sent").priority(0).enabled(true)
                .matchMode(MailRuleMatchMode.ALL).stopProcessing(false)
                .conditionsJson("[{\"field\":\"FROM\",\"operator\":\"CONTAINS\",\"value\":\"alice\"}]")
                .actionsJson("[{\"type\":\"MOVE_TO_SYSTEM_FOLDER\",\"targetSystemFolder\":\"SENT\"}]")
                .build());

        // Alice addresses herself — she gets both a SENT copy and a self-inbound entry.
        MailMessageDetail sent = mail.send(pAlice,
                new MailSendRequest(List.of("alice"), null, null, "self", "body", null, null));

        UUID msgId = UUID.fromString(sent.messageId());
        long sentCopies = entries.findAll().stream()
                .filter(e -> e.getAccountId().equals(alice.getId()) && e.getMessageId().equals(msgId)
                        && e.getFolder() == MailFolder.SENT).count();
        long inboundCopies = entries.findAll().stream()
                .filter(e -> e.getAccountId().equals(alice.getId()) && e.getMessageId().equals(msgId)
                        && e.getFolder() == MailFolder.INBOX).count();
        assertEquals(1, sentCopies, "SENT copy delivered");
        assertEquals(1, inboundCopies, "self-inbound entry clamped to INBOX — no unique-key collision");
    }
}
