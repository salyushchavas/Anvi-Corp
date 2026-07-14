"use client";

import { useRef, useState } from "react";
import {
  ArrowLeft,
  Download,
  FileText,
  Flag,
  Folder,
  FolderInput,
  Forward,
  Inbox,
  MailOpen,
  Reply,
  ReplyAll,
  Star,
  Trash2,
} from "lucide-react";
import type { MailAttachment, MailCustomFolder, MailFolder, MailMessageDetail, MailMessageSummary } from "@/lib/mail-types";
import { Avatar, Spinner } from "./ui";
import { formatBytes, formatFull, participantName, recipientList } from "./format";
import { attachmentsApi } from "@/lib/mail-client";
import { MailApiError } from "@/lib/mail-api";
import { useToast } from "./toast";

export function ReadingPane({
  entry,
  messages,
  loading,
  busy,
  customFolders,
  onReply,
  onReplyAll,
  onForward,
  onStar,
  onImportant,
  onToggleRead,
  onMove,
  onMoveToCustom,
  onTrash,
  onDelete,
  onBack,
}: {
  entry: MailMessageSummary | null;
  messages: MailMessageDetail[];
  loading: boolean;
  busy: boolean;
  customFolders: MailCustomFolder[];
  onReply: () => void;
  onReplyAll: () => void;
  onForward: () => void;
  onStar: () => void;
  onImportant: () => void;
  onToggleRead: () => void;
  onMove: (folder: MailFolder) => void;
  onMoveToCustom: (folderId: string) => void;
  onTrash: () => void;
  onDelete: () => void;
  onBack: () => void;
}) {
  const toast = useToast();
  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const moveBtnRef = useRef<HTMLButtonElement>(null);
  const [moveMenu, setMoveMenu] = useState<{ left: number; top: number } | null>(null);

  function openMoveMenu() {
    const r = moveBtnRef.current?.getBoundingClientRect();
    if (!r) return;
    // Fixed positioning escapes the toolbar's overflow-x-auto clip; clamp on-screen.
    const width = 216;
    setMoveMenu({ left: Math.max(8, Math.min(r.left, window.innerWidth - width - 8)), top: r.bottom + 4 });
  }
  function pickMove(fn: () => void) {
    setMoveMenu(null);
    fn();
  }

  async function downloadAttachment(att: MailAttachment) {
    setDownloadingId(att.id);
    try {
      // Bearer-authed blob through the walled proxy → object URL → <a download>.
      const { blob, filename } = await attachmentsApi.download(att.id);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename || att.filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      toast.error(err instanceof MailApiError ? err.message : "Could not download the attachment.");
    } finally {
      setDownloadingId(null);
    }
  }

  if (!entry) {
    return (
      <div className="hidden h-full flex-col items-center justify-center gap-3 p-8 text-center text-ink-300 lg:flex">
        <MailOpen className="h-12 w-12" />
        <p className="text-sm">Select a message to read.</p>
      </div>
    );
  }

  const subject = messages[0]?.subject ?? entry.subject;
  const inTrash = entry.folder === "TRASH";

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-1 overflow-x-auto border-b border-ink-100 px-3 py-2">
        <button type="button" onClick={onBack} className="flex-shrink-0 rounded-lg p-2 text-ink-500 hover:bg-ink-50 lg:hidden" aria-label="Back">
          <ArrowLeft className="h-4 w-4" />
        </button>
        <ToolbarButton icon={Reply} label="Reply" onClick={onReply} />
        <ToolbarButton icon={ReplyAll} label="Reply all" onClick={onReplyAll} />
        <ToolbarButton icon={Forward} label="Forward" onClick={onForward} />
        <span className="mx-1 h-5 w-px flex-shrink-0 bg-ink-100" />
        <ToolbarButton icon={Star} label="Star" active={entry.isStarred} onClick={onStar} />
        <ToolbarButton icon={Flag} label="Important" active={entry.isImportant} onClick={onImportant} />
        <ToolbarButton icon={MailOpen} label={entry.isRead ? "Mark unread" : "Mark read"} onClick={onToggleRead} />
        <span className="mx-1 h-5 w-px flex-shrink-0 bg-ink-100" />
        <button
          ref={moveBtnRef}
          type="button"
          onClick={() => (moveMenu ? setMoveMenu(null) : openMoveMenu())}
          disabled={busy}
          title="Move to"
          aria-label="Move to"
          aria-haspopup="menu"
          aria-expanded={moveMenu !== null}
          className="flex-shrink-0 rounded-lg p-2 text-ink-500 transition hover:bg-ink-50 hover:text-brand disabled:opacity-40"
        >
          <FolderInput className="h-4 w-4" />
        </button>
        <ToolbarButton icon={Trash2} label={inTrash ? "Delete forever" : "Trash"} onClick={inTrash ? onDelete : onTrash} disabled={busy} danger={inTrash} />
        {busy && <Spinner className="ml-2 h-4 w-4" />}
      </div>

      {moveMenu && (
        <>
          <button type="button" aria-hidden tabIndex={-1} className="fixed inset-0 z-[95] cursor-default" onClick={() => setMoveMenu(null)} />
          <div
            role="menu"
            style={{ left: moveMenu.left, top: moveMenu.top }}
            className="fixed z-[96] max-h-72 w-52 overflow-y-auto rounded-xl border border-ink-100 bg-white py-1 shadow-cardHover"
          >
            <p className="px-3 py-1 text-xs font-semibold uppercase tracking-wide text-ink-300">Move to</p>
            <MoveItem icon={Inbox} label="Inbox" onClick={() => pickMove(() => onMove("INBOX"))} />
            <MoveItem icon={Folder} label="Archive" onClick={() => pickMove(() => onMove("ARCHIVE"))} />
            {customFolders.length > 0 && <div className="my-1 h-px bg-ink-100" />}
            {customFolders.map((f) => (
              <MoveItem key={f.id} icon={Folder} label={f.name} onClick={() => pickMove(() => onMoveToCustom(f.id))} />
            ))}
          </div>
        </>
      )}

      <div className="flex-1 overflow-y-auto">
        <div className="border-b border-ink-100 px-6 py-4">
          <h1 className="text-xl font-bold text-ink-800">{subject || "(no subject)"}</h1>
        </div>

        {loading && (
          <div className="flex h-40 items-center justify-center">
            <Spinner className="h-6 w-6" />
          </div>
        )}

        {!loading &&
          messages.map((m) => (
            <article key={m.messageId} className="border-b border-ink-50 px-6 py-5">
              <header className="mb-4 flex items-start gap-3">
                <Avatar name={m.from.displayName} email={m.from.email} size="h-10 w-10" />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-baseline gap-x-2">
                    <span className="font-semibold text-ink-800">{participantName(m.from)}</span>
                    <span className="text-xs text-ink-300">{m.from.email}</span>
                    <span className="ml-auto text-xs text-ink-300">{formatFull(m.createdAt)}</span>
                  </div>
                  <p className="mt-0.5 text-xs text-ink-400">
                    To: {recipientList(m.to) || "—"}
                    {m.cc.length > 0 && <> · Cc: {recipientList(m.cc)}</>}
                    {m.bcc.length > 0 && <> · Bcc: {recipientList(m.bcc)}</>}
                  </p>
                </div>
              </header>
              {/* Bodies are rendered as ESCAPED PLAIN TEXT — React escapes text
                  nodes, so any HTML in the body is shown literally and never
                  executed (no raw-HTML injection). */}
              <div className="whitespace-pre-wrap break-words text-sm leading-relaxed text-ink-700">
                {m.bodyText || m.bodyHtml || <span className="italic text-ink-300">(no content)</span>}
              </div>

              {m.attachments.length > 0 && (
                <ul className="mt-4 space-y-1.5">
                  {m.attachments.map((a) => (
                    <li
                      key={a.id}
                      className="flex items-center gap-2 rounded-lg border border-ink-100 bg-ink-50 px-3 py-2 text-sm"
                    >
                      <FileText className="h-4 w-4 flex-shrink-0 text-brand" />
                      <span className="min-w-0 flex-1 truncate text-ink-700">{a.filename}</span>
                      <span className="flex-shrink-0 text-xs text-ink-300">{formatBytes(a.sizeBytes)}</span>
                      <button
                        type="button"
                        onClick={() => downloadAttachment(a)}
                        disabled={downloadingId === a.id}
                        className="inline-flex flex-shrink-0 items-center gap-1 rounded-lg border border-ink-100 bg-white px-2.5 py-1 text-xs font-medium text-ink-700 hover:text-brand disabled:opacity-60"
                        aria-label={`Download ${a.filename}`}
                      >
                        {downloadingId === a.id ? <Spinner className="h-3.5 w-3.5" /> : <Download className="h-3.5 w-3.5" />}
                        Download
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </article>
          ))}
      </div>
    </div>
  );
}

function MoveItem({ icon: Icon, label, onClick }: { icon: typeof Inbox; label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      role="menuitem"
      onClick={onClick}
      className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-ink-700 hover:bg-ink-50"
    >
      <Icon className="h-4 w-4 flex-shrink-0 text-ink-400" />
      <span className="min-w-0 flex-1 truncate">{label}</span>
    </button>
  );
}

function ToolbarButton({
  icon: Icon,
  label,
  onClick,
  active,
  disabled,
  danger,
}: {
  icon: typeof Reply;
  label: string;
  onClick: () => void;
  active?: boolean;
  disabled?: boolean;
  danger?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      title={label}
      aria-label={label}
      className={`flex-shrink-0 rounded-lg p-2 transition disabled:opacity-40 ${
        danger ? "text-ink-500 hover:bg-red-50 hover:text-red-600" : "text-ink-500 hover:bg-ink-50 hover:text-brand"
      } ${active ? "text-brand" : ""}`}
    >
      <Icon className={`h-4 w-4 ${active && label === "Star" ? "fill-amber-400 text-amber-400" : ""}`} />
    </button>
  );
}
