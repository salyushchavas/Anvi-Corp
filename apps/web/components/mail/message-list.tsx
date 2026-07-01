"use client";

import { ChevronLeft, ChevronRight, Inbox, Paperclip, Star } from "lucide-react";
import type { MailMessageSummary, MailPage } from "@/lib/mail-types";
import { Avatar, Spinner } from "./ui";
import { formatDate, participantName, recipientList } from "./format";
import type { FolderView } from "./folder-rail";

export function MessageList({
  title,
  view,
  page,
  loading,
  selectedEntryId,
  onSelect,
  onToggleStar,
  onPageChange,
}: {
  title: string;
  view: FolderView;
  page: MailPage<MailMessageSummary> | null;
  loading: boolean;
  selectedEntryId: string | null;
  onSelect: (m: MailMessageSummary) => void;
  onToggleStar: (m: MailMessageSummary) => void;
  onPageChange: (nextPageIndex: number) => void;
}) {
  const outgoing = view === "SENT" || view === "DRAFTS";
  const total = page?.total ?? 0;
  const size = page?.size ?? 25;
  const idx = page?.page ?? 0;
  const from = total === 0 ? 0 : idx * size + 1;
  const to = Math.min((idx + 1) * size, total);

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between border-b border-ink-100 px-4 py-3">
        <h2 className="text-sm font-semibold text-ink-800">{title}</h2>
        {total > 0 && (
          <div className="flex items-center gap-1 text-xs text-ink-400">
            <span>
              {from}–{to} of {total}
            </span>
            <button
              type="button"
              disabled={idx === 0}
              onClick={() => onPageChange(idx - 1)}
              className="rounded p-1 hover:bg-ink-50 disabled:opacity-30"
              aria-label="Previous page"
            >
              <ChevronLeft className="h-4 w-4" />
            </button>
            <button
              type="button"
              disabled={to >= total}
              onClick={() => onPageChange(idx + 1)}
              className="rounded p-1 hover:bg-ink-50 disabled:opacity-30"
              aria-label="Next page"
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        )}
      </div>

      <div className="flex-1 overflow-y-auto">
        {loading && (
          <div className="flex h-40 items-center justify-center">
            <Spinner className="h-6 w-6" />
          </div>
        )}

        {!loading && (!page || page.items.length === 0) && (
          <div className="flex h-full flex-col items-center justify-center gap-3 p-8 text-center text-ink-300">
            <Inbox className="h-10 w-10" />
            <p className="text-sm">Nothing here yet.</p>
          </div>
        )}

        {!loading &&
          page?.items.map((m) => {
            const party = outgoing
              ? { accountId: "", displayName: recipientList(m.to) || "(no recipients)", email: "" }
              : m.from;
            const selected = m.entryId === selectedEntryId;
            return (
              <div
                key={m.entryId}
                role="button"
                tabIndex={0}
                onClick={() => onSelect(m)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    onSelect(m);
                  }
                }}
                className={`flex w-full cursor-pointer items-center gap-3 border-b border-ink-50 px-4 py-3 text-left transition ${
                  selected ? "border-l-2 border-l-brand bg-brand-50" : "hover:bg-ink-50"
                } ${!m.isRead && !outgoing ? "bg-white" : ""}`}
              >
                <Avatar name={party.displayName} email={party.email || undefined} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span
                      className={`truncate text-sm ${
                        !m.isRead && !outgoing ? "font-semibold text-ink-800" : "text-ink-700"
                      }`}
                    >
                      {outgoing ? `To: ${party.displayName}` : participantName(party)}
                    </span>
                    <span className="ml-auto flex-shrink-0 text-xs text-ink-300">{formatDate(m.createdAt)}</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <span
                      className={`truncate text-sm ${
                        !m.isRead && !outgoing ? "font-medium text-ink-700" : "text-ink-500"
                      }`}
                    >
                      {m.subject || "(no subject)"}
                    </span>
                    {m.hasAttachments && <Paperclip className="h-3.5 w-3.5 flex-shrink-0 text-ink-300" />}
                  </div>
                </div>
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    onToggleStar(m);
                  }}
                  className="flex-shrink-0 rounded p-1 hover:bg-white"
                  aria-label={m.isStarred ? "Unstar" : "Star"}
                >
                  <Star
                    className={`h-4 w-4 ${m.isStarred ? "fill-amber-400 text-amber-400" : "text-ink-300"}`}
                  />
                </button>
              </div>
            );
          })}
      </div>
    </div>
  );
}
