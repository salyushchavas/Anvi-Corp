"use client";

import {
  Archive,
  ArrowLeft,
  Flag,
  Forward,
  MailOpen,
  Reply,
  ReplyAll,
  Star,
  Trash2,
} from "lucide-react";
import type { MailMessageDetail, MailMessageSummary } from "@/lib/mail-types";
import { Avatar, Spinner } from "./ui";
import { formatFull, participantName, recipientList } from "./format";

export function ReadingPane({
  entry,
  messages,
  loading,
  busy,
  onReply,
  onReplyAll,
  onForward,
  onStar,
  onImportant,
  onToggleRead,
  onArchive,
  onTrash,
  onDelete,
  onBack,
}: {
  entry: MailMessageSummary | null;
  messages: MailMessageDetail[];
  loading: boolean;
  busy: boolean;
  onReply: () => void;
  onReplyAll: () => void;
  onForward: () => void;
  onStar: () => void;
  onImportant: () => void;
  onToggleRead: () => void;
  onArchive: () => void;
  onTrash: () => void;
  onDelete: () => void;
  onBack: () => void;
}) {
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
        {!inTrash && <ToolbarButton icon={Archive} label="Archive" onClick={onArchive} disabled={busy} />}
        <ToolbarButton icon={Trash2} label={inTrash ? "Delete forever" : "Trash"} onClick={inTrash ? onDelete : onTrash} disabled={busy} danger={inTrash} />
        {busy && <Spinner className="ml-2 h-4 w-4" />}
      </div>

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
            </article>
          ))}
      </div>
    </div>
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
