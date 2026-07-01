"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Bell, LogOut, Mail, Menu, Search, Settings, Shield, X } from "lucide-react";
import { useMailAuth } from "./mail-auth-provider";
import { FolderRail, type FolderView } from "./folder-rail";
import { MessageList } from "./message-list";
import { ReadingPane } from "./reading-pane";
import { ComposeDialog, type ComposeInitial } from "./compose-dialog";
import { useMailEvents } from "./use-mail-events";
import { Avatar, ConfirmDialog, FormField, Input, Modal, Spinner } from "./ui";
import { useToast } from "./toast";
import { foldersApi, messagesApi } from "@/lib/mail-client";
import { MailApiError } from "@/lib/mail-api";
import { participantName } from "./format";
import type {
  MailCustomFolder,
  MailFolder,
  MailFolderCount,
  MailMessageDetail,
  MailMessageSummary,
  MailPage,
} from "@/lib/mail-types";

const APP_NAME = process.env.NEXT_PUBLIC_APP_NAME || "Mail";

const FOLDER_TITLES: Record<FolderView, string> = {
  INBOX: "Inbox",
  SENT: "Sent",
  DRAFTS: "Drafts",
  ARCHIVE: "Archive",
  TRASH: "Trash",
  STARRED: "Starred",
};

export function MailShell() {
  const { account, logout } = useMailAuth();
  const toast = useToast();

  const [view, setView] = useState<FolderView>("INBOX");
  const [customFolder, setCustomFolder] = useState<MailCustomFolder | null>(null);
  const [customFolders, setCustomFolders] = useState<MailCustomFolder[]>([]);
  const [search, setSearch] = useState<string | null>(null);
  const [searchInput, setSearchInput] = useState("");
  const [counts, setCounts] = useState<MailFolderCount[]>([]);
  const [listing, setListing] = useState<MailPage<MailMessageSummary> | null>(null);
  const [listLoading, setListLoading] = useState(true);
  const [pageIndex, setPageIndex] = useState(0);

  const [selected, setSelected] = useState<MailMessageSummary | null>(null);
  const [detail, setDetail] = useState<MailMessageDetail[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [actionBusy, setActionBusy] = useState(false);

  const [compose, setCompose] = useState<ComposeInitial | null>(null);
  const [mobileReading, setMobileReading] = useState(false);
  const [navOpen, setNavOpen] = useState(false);

  // Custom-folder edit modal (create/rename) + delete confirm.
  const [folderEdit, setFolderEdit] = useState<{ mode: "create" | "rename"; folder?: MailCustomFolder } | null>(null);
  const [folderName, setFolderName] = useState("");
  const [folderBusy, setFolderBusy] = useState(false);
  const [folderToDelete, setFolderToDelete] = useState<MailCustomFolder | null>(null);

  const loadCounts = useCallback(async () => {
    try {
      setCounts(await messagesApi.folderCounts());
    } catch {
      /* non-fatal */
    }
  }, []);

  const loadFolders = useCallback(async () => {
    try {
      setCustomFolders(await foldersApi.list());
    } catch {
      /* non-fatal */
    }
  }, []);

  const refreshCounts = useCallback(() => {
    loadCounts();
    loadFolders();
  }, [loadCounts, loadFolders]);

  const loadListing = useCallback(async () => {
    setListLoading(true);
    try {
      let page: MailPage<MailMessageSummary>;
      if (search) page = await messagesApi.search(search, pageIndex);
      else if (customFolder) page = await foldersApi.messages(customFolder.id, pageIndex);
      else if (view === "STARRED") page = await messagesApi.starred(pageIndex);
      else page = await messagesApi.listFolder(view, pageIndex);
      setListing(page);
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not load messages.");
      setListing({ items: [], page: 0, size: 25, total: 0 });
    } finally {
      setListLoading(false);
    }
  }, [view, customFolder, pageIndex, search, toast]);

  useEffect(() => {
    refreshCounts();
  }, [refreshCounts]);
  useEffect(() => {
    loadListing();
  }, [loadListing]);

  // A8 real-time: a delivered NEW_MAIL bumps the affected folder's badge in the
  // EXISTING state (system OR A6 custom, per the A7-resolved placement) and refreshes
  // the active list head; the hook resyncs counts on (re)connect and pops a desktop
  // notification only for a true INBOX arrival while the tab is hidden.
  const { notifyPermission, requestNotifyPermission } = useMailEvents({
    enabled: !!account,
    onReconnect: refreshCounts,
    onNotificationClick: () => selectFolder("INBOX"),
    onEvent: (e) => {
      if (e.type !== "NEW_MAIL") return;
      if (e.customFolderId) {
        const id = e.customFolderId;
        setCustomFolders((prev) => prev.map((f) => (f.id === id ? { ...f, total: f.total + 1, unread: f.unread + 1 } : f)));
        if (customFolder?.id === id) loadListing();
      } else {
        const folder = e.folder as MailFolder;
        setCounts((prev) => {
          const has = prev.some((c) => c.folder === folder);
          return has
            ? prev.map((c) => (c.folder === folder ? { ...c, total: c.total + 1, unread: c.unread + 1 } : c))
            : [...prev, { folder, total: 1, unread: 1 }];
        });
        if (!customFolder && !search && view === folder) loadListing();
      }
    },
  });

  function selectFolder(v: FolderView) {
    setView(v);
    setCustomFolder(null);
    setSearch(null);
    setSearchInput("");
    setPageIndex(0);
    setSelected(null);
    setDetail([]);
    setNavOpen(false);
  }

  function selectCustom(f: MailCustomFolder) {
    setCustomFolder(f);
    setSearch(null);
    setSearchInput("");
    setPageIndex(0);
    setSelected(null);
    setDetail([]);
    setNavOpen(false);
  }

  function runSearch(e: React.FormEvent) {
    e.preventDefault();
    const q = searchInput.trim();
    setSearch(q || null);
    setCustomFolder(null);
    setPageIndex(0);
    setSelected(null);
    setDetail([]);
  }

  const patchRow = useCallback((entryId: string, patch: Partial<MailMessageSummary>) => {
    setListing((prev) =>
      prev ? { ...prev, items: prev.items.map((m) => (m.entryId === entryId ? { ...m, ...patch } : m)) } : prev,
    );
    setSelected((prev) => (prev && prev.entryId === entryId ? { ...prev, ...patch } : prev));
  }, []);

  async function openMessage(m: MailMessageSummary) {
    // A draft opens straight into the composer (single-row edit).
    if (m.folder === "DRAFTS") {
      try {
        const d = await messagesApi.get(m.entryId);
        setCompose({
          to: d.draftTo ?? "",
          cc: d.draftCc ?? "",
          bcc: d.draftBcc ?? "",
          subject: d.subject ?? "",
          body: d.bodyText ?? "",
          inReplyTo: d.inReplyTo ?? undefined,
          draftEntryId: m.entryId,
          attachments: d.attachments,
        });
      } catch (err) {
        toast.error(err instanceof MailApiError ? err.message : "Could not open the draft.");
      }
      return;
    }

    setSelected(m);
    setMobileReading(true);
    setDetailLoading(true);
    setDetail([]);
    try {
      const d = await messagesApi.get(m.entryId);
      if (d.threadId) {
        const t = await messagesApi.thread(d.threadId);
        setDetail(t.messages.length ? t.messages : [d]);
      } else {
        setDetail([d]);
      }
      // Mark incoming unread messages as read on open (DRAFTS already handled above).
      const incoming = m.folder !== "SENT";
      if (incoming && !m.isRead) {
        await messagesApi.setFlags(m.entryId, { isRead: true });
        patchRow(m.entryId, { isRead: true });
        refreshCounts();
      }
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not load the message.");
    } finally {
      setDetailLoading(false);
    }
  }

  async function toggleFlag(flag: "isStarred" | "isImportant" | "isRead") {
    if (!selected) return;
    const next = !selected[flag];
    const patch: { isRead?: boolean; isStarred?: boolean; isImportant?: boolean } = {};
    patch[flag] = next;
    try {
      await messagesApi.setFlags(selected.entryId, patch);
      patchRow(selected.entryId, patch);
      if (flag === "isRead") refreshCounts();
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Action failed.");
    }
  }

  async function moveSelected(folder: MailFolder) {
    if (!selected) return;
    setActionBusy(true);
    try {
      await messagesApi.move(selected.entryId, folder);
      toast.success(`Moved to ${FOLDER_TITLES[folder]}.`);
      clearSelectionAndReload();
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not move the message.");
    } finally {
      setActionBusy(false);
    }
  }

  async function moveSelectedToCustom(folderId: string) {
    if (!selected) return;
    setActionBusy(true);
    try {
      await messagesApi.moveToCustomFolder(selected.entryId, folderId);
      const f = customFolders.find((x) => x.id === folderId);
      toast.success(`Moved to ${f?.name ?? "folder"}.`);
      clearSelectionAndReload();
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not move the message.");
    } finally {
      setActionBusy(false);
    }
  }

  async function trashSelected() {
    if (!selected) return;
    setActionBusy(true);
    try {
      await messagesApi.trash(selected.entryId);
      toast.success("Moved to Trash.");
      clearSelectionAndReload();
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not trash the message.");
    } finally {
      setActionBusy(false);
    }
  }

  async function deleteSelected() {
    if (!selected) return;
    setActionBusy(true);
    try {
      await messagesApi.remove(selected.entryId);
      toast.success("Deleted.");
      clearSelectionAndReload();
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not delete the message.");
    } finally {
      setActionBusy(false);
    }
  }

  function clearSelectionAndReload() {
    setSelected(null);
    setDetail([]);
    setMobileReading(false);
    loadListing();
    refreshCounts();
  }

  // ── Custom-folder CRUD ────────────────────────────────────────────────────
  function openNewFolder() {
    setFolderName("");
    setFolderEdit({ mode: "create" });
  }
  function openRenameFolder(f: MailCustomFolder) {
    setFolderName(f.name);
    setFolderEdit({ mode: "rename", folder: f });
  }
  async function submitFolder(e: React.FormEvent) {
    e.preventDefault();
    const name = folderName.trim();
    if (!name) {
      toast.error("Folder name is required.");
      return;
    }
    setFolderBusy(true);
    try {
      if (folderEdit?.mode === "rename" && folderEdit.folder) {
        const updated = await foldersApi.rename(folderEdit.folder.id, name);
        setCustomFolder((prev) => (prev && prev.id === updated.id ? updated : prev));
        toast.success("Folder renamed.");
      } else {
        await foldersApi.create(name);
        toast.success("Folder created.");
      }
      setFolderEdit(null);
      loadFolders();
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not save the folder.");
    } finally {
      setFolderBusy(false);
    }
  }
  async function confirmDeleteFolder() {
    if (!folderToDelete) return;
    setFolderBusy(true);
    try {
      await foldersApi.remove(folderToDelete.id);
      toast.success(`Deleted "${folderToDelete.name}" — its messages moved to Trash.`);
      const wasActive = customFolder?.id === folderToDelete.id;
      if (wasActive) {
        // Clearing customFolder/view changes loadListing's deps, so its effect
        // re-fires with fresh state and loads the Inbox. A manual loadListing()
        // here would run the STALE closure (still pointing at the just-deleted
        // folder) → a spurious 404 that races the correct load. Let the effect do it.
        setCustomFolder(null);
        setView("INBOX");
      }
      setFolderToDelete(null);
      refreshCounts();
      // Non-active delete: deps are unchanged so the effect won't refire; refresh
      // manually in case the current view is Trash (the folder's mail landed there).
      if (!wasActive) loadListing();
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not delete the folder.");
      setFolderToDelete(null);
    } finally {
      setFolderBusy(false);
    }
  }

  function composeFromReply(mode: "reply" | "replyAll" | "forward") {
    const d = detail[detail.length - 1];
    if (!d) return;
    const me = account?.email?.toLowerCase();
    const subj = d.subject ?? "";
    if (mode === "forward") {
      setCompose({ subject: prefix("Fwd:", subj), body: quote(d) });
      return;
    }
    let recipients = [d.from.email];
    if (mode === "replyAll") {
      recipients = [d.from.email, ...d.to.map((p) => p.email), ...d.cc.map((p) => p.email)];
    }
    const to = Array.from(new Set(recipients.map((e) => e.toLowerCase())))
      .filter((e) => e && e !== me)
      .join(", ");
    setCompose({ to, subject: prefix("Re:", subj), inReplyTo: d.messageId, body: quote(d) });
  }

  const listTitle = search ? `Search: ${search}` : customFolder ? customFolder.name : FOLDER_TITLES[view];
  const listView: FolderView = customFolder ? "INBOX" : view;
  const isAdmin = account?.role === "ADMIN" || account?.role === "SUPER_ADMIN";

  const rail = (
    <FolderRail
      counts={counts}
      active={view}
      activeCustomId={customFolder?.id ?? null}
      customFolders={customFolders}
      onSelect={selectFolder}
      onSelectCustom={selectCustom}
      onCompose={() => {
        setCompose({});
        setNavOpen(false);
      }}
      onNewFolder={openNewFolder}
      onRenameFolder={openRenameFolder}
      onDeleteFolder={(f) => setFolderToDelete(f)}
    />
  );

  return (
    <div className="flex h-screen flex-col bg-white">
      {/* Header */}
      <header className="flex h-14 flex-shrink-0 items-center gap-3 border-b border-ink-100 px-3 sm:px-4">
        <button type="button" onClick={() => setNavOpen(true)} className="rounded-lg p-2 text-ink-500 hover:bg-ink-50 lg:hidden" aria-label="Folders">
          <Menu className="h-5 w-5" />
        </button>
        <div className="flex items-center gap-2">
          <span className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-brand-gradient text-white">
            <Mail className="h-4 w-4" />
          </span>
          <span className="hidden font-bold text-ink-800 sm:inline">Anvi {APP_NAME}</span>
        </div>

        <form onSubmit={runSearch} className="relative ml-2 max-w-md flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-300" />
          <input
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="Search subject or people…"
            className="w-full rounded-full border border-ink-100 bg-ink-50 py-2 pl-9 pr-3 text-sm text-ink-800 placeholder:text-ink-300 focus:border-brand focus:bg-white focus:outline-none focus:ring-2 focus:ring-brand/20"
          />
        </form>

        <div className="ml-auto flex items-center gap-2">
          {isAdmin && (
            <Link href="/mail/admin" className="hidden items-center gap-1.5 rounded-full px-3 py-1.5 text-sm font-medium text-ink-700 hover:bg-ink-50 sm:inline-flex">
              <Shield className="h-4 w-4" /> Admin
            </Link>
          )}
          {notifyPermission === "default" && (
            <button
              type="button"
              onClick={requestNotifyPermission}
              title="Enable notifications"
              aria-label="Enable notifications"
              className="rounded-lg p-2 text-ink-500 hover:bg-ink-50 hover:text-brand"
            >
              <Bell className="h-4 w-4" />
            </button>
          )}
          <Link href="/mail/settings" className="hidden items-center gap-1.5 rounded-full px-3 py-1.5 text-sm font-medium text-ink-700 hover:bg-ink-50 sm:inline-flex">
            <Settings className="h-4 w-4" /> Settings
          </Link>
          <div className="flex items-center gap-2">
            <Avatar name={account?.displayName} email={account?.email} size="h-8 w-8" />
            <button type="button" onClick={logout} className="rounded-lg p-2 text-ink-500 hover:bg-ink-50" title="Sign out" aria-label="Sign out">
              <LogOut className="h-4 w-4" />
            </button>
          </div>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        <aside className="hidden w-60 flex-shrink-0 border-r border-ink-100 lg:block">{rail}</aside>

        <section
          className={`w-full flex-shrink-0 flex-col border-r border-ink-100 lg:flex lg:w-96 ${
            selected && mobileReading ? "hidden" : "flex"
          }`}
        >
          <MessageList
            title={listTitle}
            view={listView}
            page={listing}
            loading={listLoading}
            selectedEntryId={selected?.entryId ?? null}
            onSelect={openMessage}
            onToggleStar={(m) => {
              messagesApi
                .setFlags(m.entryId, { isStarred: !m.isStarred })
                .then(() => patchRow(m.entryId, { isStarred: !m.isStarred }))
                .catch((err) => toast.error(err instanceof MailApiError ? err.message : "Action failed."));
            }}
            onPageChange={setPageIndex}
          />
        </section>

        <section className={`flex-1 flex-col ${selected && mobileReading ? "flex" : "hidden"} lg:flex`}>
          <ReadingPane
            entry={selected}
            messages={detail}
            loading={detailLoading}
            busy={actionBusy}
            customFolders={customFolders}
            onReply={() => composeFromReply("reply")}
            onReplyAll={() => composeFromReply("replyAll")}
            onForward={() => composeFromReply("forward")}
            onStar={() => toggleFlag("isStarred")}
            onImportant={() => toggleFlag("isImportant")}
            onToggleRead={() => toggleFlag("isRead")}
            onMove={moveSelected}
            onMoveToCustom={moveSelectedToCustom}
            onTrash={trashSelected}
            onDelete={deleteSelected}
            onBack={() => setMobileReading(false)}
          />
        </section>
      </div>

      {/* Mobile folder drawer */}
      {navOpen && (
        <div className="fixed inset-0 z-[80] lg:hidden">
          <div className="absolute inset-0 bg-ink-800/40" onClick={() => setNavOpen(false)} />
          <div className="absolute left-0 top-0 flex h-full w-64 flex-col bg-white shadow-cardHover">
            <div className="flex items-center justify-between border-b border-ink-100 px-3 py-3">
              <span className="font-bold text-ink-800">Anvi {APP_NAME}</span>
              <button type="button" onClick={() => setNavOpen(false)} className="rounded p-1 text-ink-400 hover:bg-ink-50">
                <X className="h-5 w-5" />
              </button>
            </div>
            <div className="min-h-0 flex-1">{rail}</div>
            <Link
              href="/mail/settings"
              onClick={() => setNavOpen(false)}
              className="mx-3 mt-1 flex items-center gap-2 rounded-xl px-3 py-2 text-sm text-ink-700 hover:bg-ink-50"
            >
              <Settings className="h-4 w-4 text-ink-400" /> Settings
            </Link>
            {isAdmin && (
              <Link
                href="/mail/admin"
                onClick={() => setNavOpen(false)}
                className="mx-3 mb-3 mt-1 flex items-center gap-2 rounded-xl px-3 py-2 text-sm text-ink-700 hover:bg-ink-50"
              >
                <Shield className="h-4 w-4 text-ink-400" /> Admin console
              </Link>
            )}
          </div>
        </div>
      )}

      {compose && (
        <ComposeDialog
          initial={compose}
          onClose={() => setCompose(null)}
          onSent={() => {
            loadListing();
            refreshCounts();
          }}
        />
      )}

      {/* Create / rename folder */}
      {folderEdit && (
        <Modal open onClose={() => setFolderEdit(null)} title={folderEdit.mode === "rename" ? "Rename folder" : "New folder"} width="max-w-sm">
          <form onSubmit={submitFolder} className="space-y-4">
            <FormField label="Folder name" htmlFor="folder-name" required>
              <Input id="folder-name" value={folderName} onChange={(e) => setFolderName(e.target.value)} autoFocus maxLength={100} />
            </FormField>
            <div className="flex justify-end gap-3">
              <button type="button" onClick={() => setFolderEdit(null)} className="rounded-full px-5 py-2 text-sm font-semibold text-ink-700 hover:bg-ink-50">
                Cancel
              </button>
              <button type="submit" disabled={folderBusy} className="inline-flex items-center gap-2 rounded-full bg-brand px-6 py-2 text-sm font-semibold text-white hover:bg-brand-600 disabled:opacity-60">
                {folderBusy && <Spinner className="h-4 w-4 text-white" />}
                {folderEdit.mode === "rename" ? "Rename" : "Create"}
              </button>
            </div>
          </form>
        </Modal>
      )}

      <ConfirmDialog
        open={folderToDelete !== null}
        title="Delete folder"
        message={`Delete "${folderToDelete?.name}"? Its messages move to Trash (recoverable).`}
        confirmLabel="Delete folder"
        danger
        busy={folderBusy}
        onConfirm={confirmDeleteFolder}
        onCancel={() => setFolderToDelete(null)}
      />
    </div>
  );
}

function prefix(p: string, subject: string): string {
  const s = subject || "";
  return s.toLowerCase().startsWith(p.toLowerCase()) ? s : `${p} ${s}`.trim();
}

function quote(d: MailMessageDetail): string {
  const who = participantName(d.from);
  return `\n\n----------\nOn ${new Date(d.createdAt).toLocaleString()}, ${who} wrote:\n${d.bodyText || d.bodyHtml || ""}`;
}
