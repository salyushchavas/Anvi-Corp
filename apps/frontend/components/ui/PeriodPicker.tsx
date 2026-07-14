'use client';

/**
 * Year + Month period picker. Built for the monthly intern roster (Phase A
 * Trainer view) but generic: Manager + ERM rosters in Phase C reuse it
 * unchanged. No role / route coupling — the parent owns the value via
 * {@code value} + {@code onChange}.
 *
 * <p>{@link usePeriodFromUrl} is the recommended hook for URL-synced
 * usage: it reads {@code ?y=&m=} from the URL, defaults to "this month",
 * and pushes shallow updates so refreshes preserve the period.</p>
 */

import { useCallback } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { ChevronLeft, ChevronRight, Home } from 'lucide-react';

export interface Period {
  year: number;
  month: number; // 1-12
}

const MONTH_LABEL = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

interface Props {
  value: Period;
  onChange: (next: Period) => void;
  /** Earliest selectable year. Defaults to current year - 3. */
  minYear?: number;
  /** Latest selectable year. Defaults to current year + 1. */
  maxYear?: number;
  className?: string;
}

export default function PeriodPicker({
  value, onChange, minYear, maxYear, className,
}: Props) {
  const now = thisMonth();
  const min = minYear ?? now.year - 3;
  const max = maxYear ?? now.year + 1;
  const years: number[] = [];
  for (let y = max; y >= min; y--) years.push(y);

  function shift(delta: number) {
    onChange(shiftMonth(value, delta));
  }
  function isThisMonth() {
    return value.year === now.year && value.month === now.month;
  }

  return (
    <div className={
      'inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white p-1 shadow-sm '
      + (className ?? '')
    }>
      <button
        type="button"
        onClick={() => shift(-1)}
        className="rounded-md p-1.5 text-slate-600 hover:bg-slate-100"
        aria-label="Previous month"
      >
        <ChevronLeft className="h-4 w-4" />
      </button>
      <select
        value={value.month}
        onChange={(e) => onChange({ ...value, month: Number(e.target.value) })}
        className="rounded-md bg-transparent px-2 py-1 text-sm font-medium text-slate-900 hover:bg-slate-50"
        aria-label="Month"
      >
        {MONTH_LABEL.map((label, idx) => (
          <option key={idx} value={idx + 1}>{label}</option>
        ))}
      </select>
      <select
        value={value.year}
        onChange={(e) => onChange({ ...value, year: Number(e.target.value) })}
        className="rounded-md bg-transparent px-2 py-1 text-sm font-medium text-slate-900 hover:bg-slate-50"
        aria-label="Year"
      >
        {years.map((y) => (
          <option key={y} value={y}>{y}</option>
        ))}
      </select>
      <button
        type="button"
        onClick={() => shift(1)}
        className="rounded-md p-1.5 text-slate-600 hover:bg-slate-100"
        aria-label="Next month"
      >
        <ChevronRight className="h-4 w-4" />
      </button>
      <button
        type="button"
        onClick={() => onChange(now)}
        disabled={isThisMonth()}
        className="ml-1 inline-flex items-center gap-1 rounded-md border border-slate-200 px-2 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-40 disabled:hover:bg-transparent"
        title="Jump to current month"
      >
        <Home className="h-3 w-3" /> This month
      </button>
    </div>
  );
}

// ── Helpers ────────────────────────────────────────────────────────────────

export function thisMonth(): Period {
  const d = new Date();
  return { year: d.getFullYear(), month: d.getMonth() + 1 };
}

export function shiftMonth(p: Period, delta: number): Period {
  const idx = (p.year * 12 + (p.month - 1)) + delta;
  return {
    year: Math.floor(idx / 12),
    month: (idx % 12) + 1,
  };
}

export function formatPeriod(p: Period): string {
  return `${MONTH_LABEL[p.month - 1]} ${p.year}`;
}

/** Backend-friendly "YYYY-MM" key — matches Project.monthYear shape. */
export function periodToMonthYear(p: Period): string {
  return `${p.year}-${String(p.month).padStart(2, '0')}`;
}

/**
 * URL-synced period state. Defaults to current month when ?y/?m are
 * absent or invalid. Pushes shallow updates via
 * {@code router.replace} so back/forward navigation Just Works without
 * forcing a re-render.
 */
export function usePeriodFromUrl(): [Period, (next: Period) => void] {
  const router = useRouter();
  const sp = useSearchParams();
  const fallback = thisMonth();
  const y = parseInt(sp?.get('y') ?? '', 10);
  const m = parseInt(sp?.get('m') ?? '', 10);
  const value: Period = isFinite(y) && y > 1900 && isFinite(m) && m >= 1 && m <= 12
    ? { year: y, month: m }
    : fallback;

  const set = useCallback((next: Period) => {
    const params = new URLSearchParams(sp?.toString() ?? '');
    params.set('y', String(next.year));
    params.set('m', String(next.month));
    router.replace(`?${params.toString()}`, { scroll: false });
  }, [router, sp]);

  return [value, set];
}
