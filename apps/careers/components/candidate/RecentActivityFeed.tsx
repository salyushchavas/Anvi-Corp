'use client';

import { useState } from 'react';
import { ChevronDown, ChevronUp } from 'lucide-react';

export interface RecentUpdate {
  timestamp?: string | null;
  kind?: string | null;
  message?: string | null;
  source?: string | null;
}

interface Props {
  items: RecentUpdate[];
}

const SOURCE_PILL: Record<string, string> = {
  HR: 'bg-green-100 text-green-800',
  OPERATIONS: 'bg-slate-100 text-slate-700',
  TE: 'bg-amber-100 text-amber-800',
  RM: 'bg-amber-100 text-amber-800',
  SYSTEM: 'bg-slate-100 text-slate-700',
};

function relative(iso?: string | null): string {
  if (!iso) return '';
  try {
    const then = new Date(iso).getTime();
    const now = Date.now();
    const diff = now - then;
    const m = Math.floor(diff / 60000);
    if (m < 1) return 'just now';
    if (m < 60) return `${m}m ago`;
    const h = Math.floor(m / 60);
    if (h < 24) return `${h}h ago`;
    const d = Math.floor(h / 24);
    if (d < 7) return `${d}d ago`;
    return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  } catch {
    return iso;
  }
}

export default function RecentActivityFeed({ items }: Props) {
  const [open, setOpen] = useState(false);
  return (
    <div className="rounded-lg border border-slate-200 bg-white">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex w-full items-center justify-between gap-2 px-4 py-3 text-left"
      >
        <span className="text-sm font-medium text-slate-700">
          Recent activity {items.length > 0 && <span className="text-slate-400">({items.length})</span>}
        </span>
        {open ? (
          <ChevronUp className="h-4 w-4 text-slate-500" strokeWidth={2} />
        ) : (
          <ChevronDown className="h-4 w-4 text-slate-500" strokeWidth={2} />
        )}
      </button>
      {open && (
        <div className="border-t border-slate-200 px-4 py-3">
          {items.length === 0 ? (
            <p className="text-sm text-slate-500">No updates yet.</p>
          ) : (
            <ul className="space-y-2.5">
              {items.map((item, i) => {
                const src = item.source ?? 'SYSTEM';
                return (
                  <li key={i} className="flex items-start gap-3">
                    <span
                      className={
                        'mt-0.5 inline-flex items-center rounded-full px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide ' +
                        (SOURCE_PILL[src] ?? SOURCE_PILL.SYSTEM)
                      }
                    >
                      {src}
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="text-sm text-slate-800">{item.message ?? '—'}</p>
                      {item.timestamp && (
                        <p className="text-[11px] text-slate-500">{relative(item.timestamp)}</p>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
