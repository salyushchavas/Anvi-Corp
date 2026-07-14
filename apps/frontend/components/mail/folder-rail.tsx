"use client";

import {
  Archive,
  FileText,
  Folder,
  FolderPlus,
  Inbox,
  PenSquare,
  Pencil,
  Send,
  Star,
  Trash2,
  type LucideIcon,
} from "lucide-react";
import type { MailCustomFolder, MailFolder, MailFolderCount } from "@/lib/mail-types";

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
  activeCustomId,
  customFolders,
  onSelect,
  onSelectCustom,
  onCompose,
  onNewFolder,
  onRenameFolder,
  onDeleteFolder,
}: {
  counts: MailFolderCount[];
  active: FolderView;
  activeCustomId: string | null;
  customFolders: MailCustomFolder[];
  onSelect: (v: FolderView) => void;
  onSelectCustom: (f: MailCustomFolder) => void;
  onCompose: () => void;
  onNewFolder: () => void;
  onRenameFolder: (f: MailCustomFolder) => void;
  onDeleteFolder: (f: MailCustomFolder) => void;
}) {
  const countFor = (f: MailFolder) => counts.find((c) => c.folder === f);
  const systemActive = (key: MailFolder | "STARRED") => activeCustomId === null && active === key;

  return (
    <div className="flex h-full flex-col gap-1 overflow-y-auto p-3">
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
        const isActive = systemActive(key);
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
          systemActive("STARRED") ? "bg-brand-50 font-semibold text-brand-700" : "text-ink-700 hover:bg-ink-50"
        }`}
      >
        <Star className={`h-4 w-4 ${systemActive("STARRED") ? "text-brand" : "text-ink-400"}`} />
        <span className="flex-1 text-left">Starred</span>
      </button>

      {/* Custom folders (A6) */}
      <div className="mt-3 flex items-center justify-between px-3 pb-1">
        <span className="text-xs font-semibold uppercase tracking-wide text-ink-300">Folders</span>
        <button
          type="button"
          onClick={onNewFolder}
          title="New folder"
          aria-label="New folder"
          className="rounded p-1 text-ink-400 hover:bg-ink-50 hover:text-brand"
        >
          <FolderPlus className="h-4 w-4" />
        </button>
      </div>

      {customFolders.length === 0 && (
        <p className="px-3 py-1 text-xs text-ink-300">No custom folders yet.</p>
      )}

      {customFolders.map((f) => {
        const isActive = activeCustomId === f.id;
        return (
          <div
            key={f.id}
            className={`group flex items-center gap-2 rounded-xl px-3 py-2 text-sm transition ${
              isActive ? "bg-brand-50 font-semibold text-brand-700" : "text-ink-700 hover:bg-ink-50"
            }`}
          >
            <button type="button" onClick={() => onSelectCustom(f)} className="flex min-w-0 flex-1 items-center gap-3 text-left">
              <Folder className={`h-4 w-4 flex-shrink-0 ${isActive ? "text-brand" : "text-ink-400"}`} />
              <span className="min-w-0 flex-1 truncate">{f.name}</span>
              {f.unread > 0 && (
                <span className="flex-shrink-0 rounded-full bg-brand px-2 py-0.5 text-xs font-semibold text-white">
                  {f.unread}
                </span>
              )}
            </button>
            <button
              type="button"
              onClick={() => onRenameFolder(f)}
              title="Rename"
              aria-label={`Rename ${f.name}`}
              className="hidden flex-shrink-0 rounded p-1 text-ink-400 hover:text-brand group-hover:block"
            >
              <Pencil className="h-3.5 w-3.5" />
            </button>
            <button
              type="button"
              onClick={() => onDeleteFolder(f)}
              title="Delete"
              aria-label={`Delete ${f.name}`}
              className="hidden flex-shrink-0 rounded p-1 text-ink-400 hover:text-red-600 group-hover:block"
            >
              <Trash2 className="h-3.5 w-3.5" />
            </button>
          </div>
        );
      })}
    </div>
  );
}
