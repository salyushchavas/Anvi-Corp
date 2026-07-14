import {
  CheckCircle2,
  FileText,
  RotateCcw,
  Save,
  type LucideIcon,
} from 'lucide-react';
import { formatRelative } from '@/lib/format-date';
import type { I9HistoryEntryResponse } from '@/types';

interface Props {
  entries: I9HistoryEntryResponse[];
}

interface IconStyle {
  Icon: LucideIcon;
  bg: string;
  fg: string;
}

const STYLE: Record<string, IconStyle> = {
  CREATE: {
    Icon: FileText,
    bg: 'bg-gray-100',
    fg: 'text-gray-500',
  },
  SECTION_1_DRAFT_SAVE: {
    Icon: Save,
    bg: 'bg-gray-100',
    fg: 'text-gray-500',
  },
  SECTION_2_DRAFT_SAVE: {
    Icon: Save,
    bg: 'bg-gray-100',
    fg: 'text-gray-500',
  },
  SECTION_1_SUBMIT: {
    Icon: CheckCircle2,
    bg: 'bg-green-100',
    fg: 'text-green-600',
  },
  SECTION_2_SUBMIT: {
    Icon: CheckCircle2,
    bg: 'bg-green-100',
    fg: 'text-green-600',
  },
  REOPEN: {
    Icon: RotateCcw,
    bg: 'bg-amber-100',
    fg: 'text-amber-600',
  },
};

const DEFAULT_STYLE: IconStyle = {
  Icon: FileText,
  bg: 'bg-gray-100',
  fg: 'text-gray-500',
};

export default function AuditHistoryList({ entries }: Props) {
  if (entries.length === 0) {
    return (
      <p className="text-sm text-gray-500">No activity recorded yet.</p>
    );
  }

  return (
    <ol className="relative space-y-1">
      {entries.map((entry, idx) => {
        const style = STYLE[entry.action] ?? DEFAULT_STYLE;
        const Icon = style.Icon;
        const isLast = idx === entries.length - 1;
        const performer = entry.performedByName ?? 'System';
        return (
          <li key={entry.auditId} className="flex items-start gap-3">
            <div className="relative flex flex-col items-center">
              <div
                className={
                  'flex h-8 w-8 items-center justify-center rounded-full ' +
                  style.bg
                }
              >
                <Icon
                  className={'h-4 w-4 ' + style.fg}
                  strokeWidth={2}
                />
              </div>
              {!isLast && (
                <div className="mt-1 w-px flex-1 bg-gray-200" />
              )}
            </div>
            <div className="min-w-0 flex-1 pb-6">
              <div className="text-sm text-gray-900">{entry.summary}</div>
              <div className="mt-0.5 text-xs text-gray-500">
                {formatRelative(entry.timestamp)} · by {performer}
                {entry.performedByRole && (
                  <span className="ml-1 text-gray-400">
                    ({entry.performedByRole})
                  </span>
                )}
              </div>
            </div>
          </li>
        );
      })}
    </ol>
  );
}
