"use client";

import { Archive, FileText, Inbox, PenSquare, Send, Star, Trash2, type LucideIcon } from "lucide-react";
import type { MailFolder, MailFolderCount } from "@/lib/mail-types";

export type FolderView = MailFolder | "STARRED";

const FOLDERS: { key: MailFolder; label: string; icon: LucideIcon }[] = [
  { key: "INBOX", label: "Inbox", icon: Inbox },
  { key: "SENT", label: "Sent", icon: Send },
  { key: "DRAFTS", label: "Drafts", icon: FileText },
  { key: "ARCHIVE", label: "Archive", icon: Archive },
  { key: "TRASH", label: "Trash", icon: Trash2 },
];

export function FolderRail({
  counts,
  active,
  onSelect,
  onCompose,
}: {
  counts: MailFolderCount[];
  active: FolderView;
  onSelect: (v: FolderView) => void;
  onCompose: () => void;
}) {
  const countFor = (f: MailFolder) => counts.find((c) => c.folder === f);

  return (
    <div className="flex h-full flex-col gap-1 p-3">
      <button
        type="button"
        onClick={onCompose}
        className="mb-2 inline-flex items-center justify-center gap-2 rounded-full bg-brand-gradient px-5 py-3 text-sm font-semibold text-white shadow-card transition hover:shadow-cardHover"
      >
        <PenSquare className="h-4 w-4" /> Compose
      </button>

      {FOLDERS.map(({ key, label, icon: Icon }) => {
        const c = countFor(key);
        const unread = c?.unread ?? 0;
        const isActive = active === key;
        return (
          <button
            key={key}
            type="button"
            onClick={() => onSelect(key)}
            className={`flex items-center gap-3 rounded-xl px-3 py-2 text-sm transition ${
              isActive ? "bg-brand-50 font-semibold text-brand-700" : "text-ink-700 hover:bg-ink-50"
            }`}
          >
            <Icon className={`h-4 w-4 ${isActive ? "text-brand" : "text-ink-400"}`} />
            <span className="flex-1 text-left">{label}</span>
            {unread > 0 && (
              <span className="rounded-full bg-brand px-2 py-0.5 text-xs font-semibold text-white">{unread}</span>
            )}
          </button>
        );
      })}

      <button
        type="button"
        onClick={() => onSelect("STARRED")}
        className={`flex items-center gap-3 rounded-xl px-3 py-2 text-sm transition ${
          active === "STARRED" ? "bg-brand-50 font-semibold text-brand-700" : "text-ink-700 hover:bg-ink-50"
        }`}
      >
        <Star className={`h-4 w-4 ${active === "STARRED" ? "text-brand" : "text-ink-400"}`} />
        <span className="flex-1 text-left">Starred</span>
      </button>
    </div>
  );
}
