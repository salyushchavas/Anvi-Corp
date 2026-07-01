"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { LogOut, Mail, Menu, Search, Shield, X } from "lucide-react";
import { useMailAuth } from "./mail-auth-provider";
import { FolderRail, type FolderView } from "./folder-rail";
import { MessageList } from "./message-list";
import { ReadingPane } from "./reading-pane";
import { ComposeDialog, type ComposeInitial } from "./compose-dialog";
import { Avatar } from "./ui";
import { useToast } from "./toast";
import { messagesApi } from "@/lib/mail-client";
import { MailApiError } from "@/lib/mail-api";
import { participantName } from "./format";
import type {
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

  const loadCounts = useCallback(async () => {
    try {
      setCounts(await messagesApi.folderCounts());
    } catch {
      /* non-fatal */
    }
  }, []);

  const loadListing = useCallback(async () => {
    setListLoading(true);
    try {
      let page: MailPage<MailMessageSummary>;
      if (search) page = await messagesApi.search(search, pageIndex);
      else if (view === "STARRED") page = await messagesApi.starred(pageIndex);
      else page = await messagesApi.listFolder(view, pageIndex);
      setListing(page);
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not load messages.");
      setListing({ items: [], page: 0, size: 25, total: 0 });
    } finally {
      setListLoading(false);
    }
  }, [view, pageIndex, search, toast]);

  useEffect(() => {
    loadCounts();
  }, [loadCounts]);
  useEffect(() => {
    loadListing();
  }, [loadListing]);

  function selectFolder(v: FolderView) {
    setView(v);
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
        loadCounts();
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
      if (flag === "isRead") loadCounts();
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
    loadCounts();
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

  const listTitle = search ? `Search: ${search}` : FOLDER_TITLES[view];
  const isAdmin = account?.role === "ADMIN" || account?.role === "SUPER_ADMIN";

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
          <div className="flex items-center gap-2">
            <Avatar name={account?.displayName} email={account?.email} size="h-8 w-8" />
            <button type="button" onClick={logout} className="rounded-lg p-2 text-ink-500 hover:bg-ink-50" title="Sign out" aria-label="Sign out">
              <LogOut className="h-4 w-4" />
            </button>
          </div>
        </div>
      </header>

      <div className="flex flex-1 overflow-hidden">
        <aside className="hidden w-60 flex-shrink-0 border-r border-ink-100 lg:block">
          <FolderRail counts={counts} active={view} onSelect={selectFolder} onCompose={() => setCompose({})} />
        </aside>

        <section
          className={`w-full flex-shrink-0 flex-col border-r border-ink-100 lg:flex lg:w-96 ${
            selected && mobileReading ? "hidden" : "flex"
          }`}
        >
          <MessageList
            title={listTitle}
            view={view}
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
            onReply={() => composeFromReply("reply")}
            onReplyAll={() => composeFromReply("replyAll")}
            onForward={() => composeFromReply("forward")}
            onStar={() => toggleFlag("isStarred")}
            onImportant={() => toggleFlag("isImportant")}
            onToggleRead={() => toggleFlag("isRead")}
            onArchive={() => moveSelected("ARCHIVE")}
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
          <div className="absolute left-0 top-0 h-full w-64 bg-white shadow-cardHover">
            <div className="flex items-center justify-between border-b border-ink-100 px-3 py-3">
              <span className="font-bold text-ink-800">Anvi {APP_NAME}</span>
              <button type="button" onClick={() => setNavOpen(false)} className="rounded p-1 text-ink-400 hover:bg-ink-50">
                <X className="h-5 w-5" />
              </button>
            </div>
            <FolderRail counts={counts} active={view} onSelect={selectFolder} onCompose={() => { setCompose({}); setNavOpen(false); }} />
            {isAdmin && (
              <Link href="/mail/admin" className="mx-3 mt-1 flex items-center gap-2 rounded-xl px-3 py-2 text-sm text-ink-700 hover:bg-ink-50">
                <Shield className="h-4 w-4 text-ink-400" /> Admin console
              </Link>
            )}
          </div>
        </div>
      )}

      {compose && <ComposeDialog initial={compose} onClose={() => setCompose(null)} onSent={() => { loadListing(); loadCounts(); }} />}
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
