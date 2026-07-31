'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { ChevronDown, ChevronLeft, ChevronRight } from 'lucide-react';

/**
 * Compact month picker used in role-dashboard headers (Evaluator first;
 * Trainer, Manager, ERM follow the same pattern). Renders as a small
 * button with prev/next chevrons flanking a "July 2026" label + a
 * clickable month name that opens a 12-month grid popover.
 *
 * <p>The component is presentational — it doesn't own the URL sync.
 * The caller passes the current {@code value} (a {@code YYYY-MM}
 * string), and receives {@code onChange(next: string)} when the user
 * picks a different month. Every role's dashboard context wires those
 * two through its own {@code ?month=YYYY-MM} URL handling so the
 * MonthPicker itself stays reusable across roles.</p>
 */
export interface MonthPickerProps {
  /** Currently-selected month as {@code YYYY-MM}. */
  value: string;
  /** Fired when the user selects a different month. */
  onChange: (next: string) => void;
  /**
   * Optional lower bound (inclusive), e.g. {@code "2024-01"} — months
   * earlier than this are disabled in the grid + prev button. Omit for
   * no lower bound.
   */
  minMonth?: string;
  /**
   * Optional upper bound (inclusive), e.g. {@code "2026-12"}. Defaults
   * to the current month + 12 (the picker never lets you jump into
   * the far future by accident).
   */
  maxMonth?: string;
}

/** Parse {@code YYYY-MM} into a {@link Date} anchored at day 1 UTC. */
function parseMonth(value: string): Date | null {
  const m = /^(\d{4})-(\d{2})$/.exec(value.trim());
  if (!m) return null;
  const year = Number(m[1]);
  const month = Number(m[2]);
  if (month < 1 || month > 12) return null;
  return new Date(Date.UTC(year, month - 1, 1));
}

/** Serialize {@link Date} to {@code YYYY-MM}. */
function formatMonth(d: Date): string {
  const yyyy = d.getUTCFullYear();
  const mm = String(d.getUTCMonth() + 1).padStart(2, '0');
  return `${yyyy}-${mm}`;
}

function formatLabel(value: string): string {
  const d = parseMonth(value);
  if (!d) return value;
  return d.toLocaleString(undefined, {
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  });
}

function shiftMonth(value: string, delta: number): string {
  const d = parseMonth(value);
  if (!d) return value;
  d.setUTCMonth(d.getUTCMonth() + delta);
  return formatMonth(d);
}

function currentMonth(): string {
  const now = new Date();
  return formatMonth(new Date(Date.UTC(now.getFullYear(), now.getMonth(), 1)));
}

export default function MonthPicker({ value, onChange, minMonth, maxMonth }: MonthPickerProps) {
  const [open, setOpen] = useState(false);
  const popRef = useRef<HTMLDivElement>(null);
  const btnRef = useRef<HTMLButtonElement>(null);

  const now = currentMonth();
  const upperBound = useMemo(() => {
    if (maxMonth) return maxMonth;
    return shiftMonth(now, 12);
  }, [maxMonth, now]);

  const prevValue = shiftMonth(value, -1);
  const nextValue = shiftMonth(value, +1);
  const canGoPrev = !minMonth || prevValue >= minMonth;
  const canGoNext = nextValue <= upperBound;

  // Popover — center the grid on the selected year. Show 12 months
  // (Jan–Dec) so the user can jump anywhere in the year with one
  // click. Year navigation uses < / > headers above the grid.
  const selectedYear = useMemo(() => {
    const d = parseMonth(value);
    return d ? d.getUTCFullYear() : new Date().getUTCFullYear();
  }, [value]);
  const [viewYear, setViewYear] = useState(selectedYear);
  useEffect(() => setViewYear(selectedYear), [selectedYear]);

  // Close on outside click / Escape.
  useEffect(() => {
    if (!open) return;
    function onDoc(e: MouseEvent) {
      const t = e.target as Node;
      if (
        popRef.current && !popRef.current.contains(t)
        && btnRef.current && !btnRef.current.contains(t)
      ) {
        setOpen(false);
      }
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  function pick(month: string) {
    onChange(month);
    setOpen(false);
  }

  return (
    <div className="relative inline-flex items-center gap-1">
      <button
        type="button"
        aria-label="Previous month"
        onClick={() => canGoPrev && onChange(prevValue)}
        disabled={!canGoPrev}
        className="rounded-md border border-slate-200 bg-white p-1 text-slate-600 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
      >
        <ChevronLeft className="h-3.5 w-3.5" />
      </button>

      <button
        ref={btnRef}
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="dialog"
        aria-expanded={open}
        className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1 text-sm font-medium text-slate-800 hover:bg-slate-50"
      >
        {formatLabel(value)}
        <ChevronDown className="h-3.5 w-3.5 text-slate-500" />
      </button>

      <button
        type="button"
        aria-label="Next month"
        onClick={() => canGoNext && onChange(nextValue)}
        disabled={!canGoNext}
        className="rounded-md border border-slate-200 bg-white p-1 text-slate-600 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
      >
        <ChevronRight className="h-3.5 w-3.5" />
      </button>

      {value !== now && (
        <button
          type="button"
          onClick={() => onChange(now)}
          className="ml-1 rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-medium text-brand-700 hover:bg-slate-50"
        >
          Jump to this month
        </button>
      )}

      {open && (
        <div
          ref={popRef}
          role="dialog"
          className="absolute left-8 top-9 z-30 w-64 rounded-lg border border-slate-200 bg-white p-3 shadow-lg"
        >
          <div className="mb-2 flex items-center justify-between">
            <button
              type="button"
              onClick={() => setViewYear((y) => y - 1)}
              className="rounded p-1 text-slate-600 hover:bg-slate-100"
              aria-label="Previous year"
            >
              <ChevronLeft className="h-3.5 w-3.5" />
            </button>
            <p className="text-sm font-semibold text-slate-900">{viewYear}</p>
            <button
              type="button"
              onClick={() => setViewYear((y) => y + 1)}
              className="rounded p-1 text-slate-600 hover:bg-slate-100"
              aria-label="Next year"
            >
              <ChevronRight className="h-3.5 w-3.5" />
            </button>
          </div>
          <div className="grid grid-cols-3 gap-1">
            {Array.from({ length: 12 }, (_, i) => {
              const monthValue = `${viewYear}-${String(i + 1).padStart(2, '0')}`;
              const label = new Date(Date.UTC(viewYear, i, 1)).toLocaleString(undefined, {
                month: 'short',
                timeZone: 'UTC',
              });
              const disabled =
                (!!minMonth && monthValue < minMonth) || monthValue > upperBound;
              const selected = monthValue === value;
              const isNow = monthValue === now;
              return (
                <button
                  key={monthValue}
                  type="button"
                  onClick={() => !disabled && pick(monthValue)}
                  disabled={disabled}
                  className={
                    'rounded px-2 py-1.5 text-xs font-medium '
                    + (selected
                      ? 'bg-brand-700 text-white'
                      : isNow
                        ? 'bg-brand-50 text-brand-700 hover:bg-brand-100'
                        : 'text-slate-700 hover:bg-slate-100')
                    + (disabled ? ' cursor-not-allowed opacity-40' : '')
                  }
                >
                  {label}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * Small helper for callers that need the "current month" default in
 * the same format the picker uses. Exported so a page reading
 * {@code ?month} from the URL can fall back to the same default the
 * MonthPicker considers "this month".
 */
export function currentMonthValue(): string {
  return currentMonth();
}
