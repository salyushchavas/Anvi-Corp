'use client';

import Link from 'next/link';
import {
  CalendarClock,
  CheckCircle2,
  ClipboardList,
  GraduationCap,
  LifeBuoy,
  Sparkles,
} from 'lucide-react';
import type { FocusItem } from './types';

/**
 * "This week" focus strip — top-of-dashboard triage row that condenses
 * the trainer's actionable workload into ≤5 clickable pills.
 *
 * Zero-count items are hidden so the strip stays scannable; if every
 * item is zero the whole strip collapses to a single "all caught up"
 * pill. Each non-zero item is a Link → the existing list page the
 * trainer needs to act on.
 */

const ICON: Record<string, React.ComponentType<{ className?: string }>> = {
  SESSIONS_THIS_WEEK: CalendarClock,
  KT_PENDING: GraduationCap,
  PROJECTS_TO_ASSIGN: ClipboardList,
  REVIEWS_PENDING: ClipboardList,
  DOUBTS_WAITING: LifeBuoy,
};

export default function TrainerFocusStrip({
  items,
  loading,
}: {
  items: FocusItem[] | undefined;
  loading: boolean;
}) {
  if (loading) {
    return (
      <section
        aria-label="This week focus strip"
        className="rounded-lg border border-slate-200 bg-white px-3 py-2 shadow-sm"
      >
        <div className="h-7 w-full animate-pulse rounded bg-slate-100" />
      </section>
    );
  }

  const live = (items ?? []).filter((i) => i.count > 0);

  return (
    <section
      aria-label="This week focus strip"
      className="rounded-lg border border-slate-200 bg-white px-3 py-2 shadow-sm"
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
          This week
        </span>
        {live.length === 0 ? (
          <span className="inline-flex items-center gap-1.5 rounded-full bg-green-50 px-2.5 py-1 text-xs font-medium text-green-800 ring-1 ring-inset ring-green-200">
            <CheckCircle2 className="h-3.5 w-3.5" /> All caught up
          </span>
        ) : (
          live.map((it) => {
            const Icon = ICON[it.key] ?? Sparkles;
            return (
              <Link
                key={it.key}
                href={it.actionUrl}
                className="group inline-flex items-center gap-1.5 rounded-full border border-slate-200 bg-slate-50 px-2.5 py-1 text-xs text-slate-700 transition-colors hover:border-brand-300 hover:bg-brand-50 hover:text-brand-800"
              >
                <Icon className="h-3.5 w-3.5 text-brand-600 group-hover:text-brand-700" />
                <span className="font-semibold tabular-nums">{it.count}</span>
                <span className="text-slate-600 group-hover:text-brand-700">
                  {it.label.toLowerCase()}
                </span>
              </Link>
            );
          })
        )}
      </div>
    </section>
  );
}
