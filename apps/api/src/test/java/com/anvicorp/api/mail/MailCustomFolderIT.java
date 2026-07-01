package com.anvicorp.api.mail;

import com.anvicorp.api.auth.MailPrincipal;
import com.anvicorp.api.mail.dto.MailCustomFolderRequest;
import com.anvicorp.api.mail.dto.MailCustomFolderResponse;
import com.anvicorp.api.mail.dto.MailFolderCount;
import com.anvicorp.api.mail.dto.MailMessageDetail;
import com.anvicorp.api.mail.dto.MailSendRequest;
import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailAccountStatus;
import com.anvicorp.api.mail.entity.MailDomain;
import com.anvicorp.api.mail.entity.MailFolder;
import com.anvicorp.api.mail.entity.MailRole;
import com.anvicorp.api.mail.exception.MailApiException;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailCustomFolderRepository;
import com.anvicorp.api.mail.repository.MailDomainRepository;
import com.anvicorp.api.mail.repository.MailMailboxEntryRepository;
import com.anvicorp.api.mail.repository.MailMessageRecipientRepository;
import com.anvicorp.api.mail.repository.MailMessageRepository;
import com.anvicorp.api.mail.service.MailCustomFolderService;
import com.anvicorp.api.mail.service.MailMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A6 custom-folder verification against H2. Exercises the precedence model
 * (a message is in exactly one of system-enum | custom folder via the nullable
 * custom_folder_id), walling, counts, and delete→Trash. The A2 system-folder
 * regression is covered by MailCoreIT still passing in the full suite.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:anvi_a6;DB_CLOSE_DELAY=-1",
        "app.webmail.seed.admin-enabled=false",
})
class MailCustomFolderIT {

    @Autowired MailCustomFolderService folders;
    @Autowired MailMessageService mail;
    @Autowired MailAccountRepository accounts;
    @Autowired MailDomainRepository domains;
    @Autowired MailMailboxEntryRepository entries;
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

    /** Deliver one message to alice's INBOX (from bob) and return her inbox entry id. */
    private UUID deliverToAlice() {
        mail.send(pBob, new MailSendRequest(java.util.List.of("alice"), null, null, "hello", "body", null, null));
        return UUID.fromString(mail.listFolder(pAlice, MailFolder.INBOX, 0, 25).items().get(0).entryId());
    }

    private long inboxTotal(MailPrincipal p) {
        return count(p, "INBOX").total();
    }

    private MailFolderCount count(MailPrincipal p, String folder) {
        return mail.folderCounts(p).stream().filter(c -> c.folder().equals(folder)).findFirst().orElseThrow();
    }

    // ── 1. Create / rename / delete — walled + dup-name ─────────────────────────
    @Test
    void folderCrud_walled_andDuplicateName() {
        MailCustomFolderResponse work = folders.create(pAlice, new MailCustomFolderRequest("Work"));
        assertEquals("Work", work.name());
        assertEquals(0, work.total());
        assertTrue(folders.list(pAlice).stream().anyMatch(f -> f.name().equals("Work")));

        // Case-insensitive duplicate → 409.
        assertEquals(409, assertThrows(MailApiException.class,
                () -> folders.create(pAlice, new MailCustomFolderRequest("work"))).getStatus().value());

        UUID workId = UUID.fromString(work.id());
        assertEquals("Projects", folders.rename(pAlice, workId, new MailCustomFolderRequest("Projects")).name());

        // Walling: bob cannot rename/delete alice's folder.
        assertEquals(404, assertThrows(MailApiException.class,
                () -> folders.rename(pBob, workId, new MailCustomFolderRequest("X"))).getStatus().value());
        assertEquals(404, assertThrows(MailApiException.class,
                () -> folders.delete(pBob, workId)).getStatus().value());

        folders.delete(pAlice, workId);
        assertTrue(folders.list(pAlice).isEmpty());
    }

    // ── 2. Move into a custom folder → LEAVES its system folder; counts update ──
    @Test
    void moveIntoCustomFolder_leavesSystemFolder() {
        UUID entryId = deliverToAlice();
        assertEquals(1, inboxTotal(pAlice));
        UUID workId = UUID.fromString(folders.create(pAlice, new MailCustomFolderRequest("Work")).id());

        MailMessageDetail moved = mail.moveToCustomFolder(pAlice, entryId, workId);
        assertEquals(workId.toString(), moved.customFolderId());

        // Left the system folder entirely.
        assertEquals(0, inboxTotal(pAlice), "message no longer counts in INBOX");
        assertEquals(0, mail.listFolder(pAlice, MailFolder.INBOX, 0, 25).total());
        // Present in the custom folder + its count.
        assertEquals(1, mail.listCustomFolder(pAlice, workId, 0, 25).total());
        assertEquals(1, folders.list(pAlice).get(0).total());
        // The entry has the FK set.
        assertEquals(workId, entries.findById(entryId).orElseThrow().getCustomFolderId());
    }

    // ── 3. Move back to a system folder → FK cleared, behaves normally again ────
    @Test
    void moveBackToSystemFolder_clearsFk() {
        UUID entryId = deliverToAlice();
        UUID workId = UUID.fromString(folders.create(pAlice, new MailCustomFolderRequest("Work")).id());
        mail.moveToCustomFolder(pAlice, entryId, workId);

        MailMessageDetail back = mail.move(pAlice, entryId, MailFolder.ARCHIVE);
        assertNull(back.customFolderId(), "FK cleared on move to a system folder");
        assertNull(entries.findById(entryId).orElseThrow().getCustomFolderId());
        assertEquals(1, mail.listFolder(pAlice, MailFolder.ARCHIVE, 0, 25).total());
        assertEquals(0, mail.listCustomFolder(pAlice, workId, 0, 25).total());
    }

    // ── 4. Soft-delete to Trash from a custom folder → FK cleared, enum=TRASH ───
    @Test
    void softDeleteFromCustomFolder_clearsFk_toTrash() {
        UUID entryId = deliverToAlice();
        UUID workId = UUID.fromString(folders.create(pAlice, new MailCustomFolderRequest("Work")).id());
        mail.moveToCustomFolder(pAlice, entryId, workId);

        mail.softDelete(pAlice, entryId);
        assertNull(entries.findById(entryId).orElseThrow().getCustomFolderId());
        assertEquals(MailFolder.TRASH, entries.findById(entryId).orElseThrow().getFolder());
        assertEquals(0, mail.listCustomFolder(pAlice, workId, 0, 25).total());
        assertEquals(1, mail.listFolder(pAlice, MailFolder.TRASH, 0, 25).total());
    }

    // ── 5. Delete a folder with messages → messages to TRASH (recoverable) ──────
    @Test
    void deleteFolderWithMessages_movesToTrash_recoverable_noOrphanFk() {
        UUID entryId = deliverToAlice();
        UUID workId = UUID.fromString(folders.create(pAlice, new MailCustomFolderRequest("Work")).id());
        mail.moveToCustomFolder(pAlice, entryId, workId);

        folders.delete(pAlice, workId);

        assertTrue(folders.list(pAlice).isEmpty(), "folder row gone");
        assertNull(entries.findById(entryId).orElseThrow().getCustomFolderId(), "no orphaned FK");
        assertEquals(MailFolder.TRASH, entries.findById(entryId).orElseThrow().getFolder());
        assertEquals(1, mail.listFolder(pAlice, MailFolder.TRASH, 0, 25).total());

        // Recoverable: move it back out of Trash.
        mail.move(pAlice, entryId, MailFolder.INBOX);
        assertEquals(1, inboxTotal(pAlice));
    }

    // ── 6. Walling on the message ops: a foreign custom folder → 404 ────────────
    @Test
    void moveToForeignFolder_andListForeignFolder_are404() {
        UUID entryId = deliverToAlice();
        UUID bobFolder = UUID.fromString(folders.create(pBob, new MailCustomFolderRequest("BobWork")).id());

        assertEquals(404, assertThrows(MailApiException.class,
                () -> mail.moveToCustomFolder(pAlice, entryId, bobFolder)).getStatus().value());
        assertEquals(404, assertThrows(MailApiException.class,
                () -> mail.listCustomFolder(pAlice, bobFolder, 0, 25)).getStatus().value());
        // alice's message untouched.
        assertEquals(1, inboxTotal(pAlice));
    }

    // ── 7. Filing a message from TRASH into a custom folder keeps it searchable ──
    @Test
    void moveFromTrashIntoCustomFolder_normalizesEnum_staysSearchable() {
        UUID entryId = deliverToAlice(); // subject "hello"
        mail.softDelete(pAlice, entryId);
        assertEquals(MailFolder.TRASH, entries.findById(entryId).orElseThrow().getFolder());

        UUID workId = UUID.fromString(folders.create(pAlice, new MailCustomFolderRequest("Work")).id());
        mail.moveToCustomFolder(pAlice, entryId, workId);

        // Lives in the custom folder, out of the Trash view, enum normalized off TRASH.
        assertEquals(1, mail.listCustomFolder(pAlice, workId, 0, 25).total());
        assertEquals(0, mail.listFolder(pAlice, MailFolder.TRASH, 0, 25).total());
        assertEquals(MailFolder.INBOX, entries.findById(entryId).orElseThrow().getFolder());
        // Still searchable — search gates on folder != TRASH, so normalization matters.
        assertEquals(1, mail.search(pAlice, "hello", 0, 25).total());
    }
}
