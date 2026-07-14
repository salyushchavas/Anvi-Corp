'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  Clock,
  Loader2,
  Lock,
  Send,
} from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import TimesheetStatusTracker from '@/components/intern/TimesheetStatusTracker';
import PeriodPicker, {
  formatPeriod,
  usePeriodFromUrl,
} from '@/components/ui/PeriodPicker';

type DayOfWeek = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY'
  | 'SATURDAY' | 'SUNDAY';
type TimesheetStatus = 'DRAFT' | 'SUBMITTED' | 'VERIFIED' | 'APPROVED' | 'REJECTED';

interface TimesheetDay {
  id: string;
  dayOfWeek: DayOfWeek;
  hours: number;
  notes: string | null;
}

interface TimesheetWeek {
  id: string;
  internUserId: string | null;
  internName: string | null;
  weekStart: string;
  status: TimesheetStatus;
  totalHours: number;
  days: TimesheetDay[];
  reviewNote: string | null;
  approvedByName: string | null;
  approvedAt: string | null;
  submittedAt: string | null;
  createdAt: string;
}

interface WeekEntry {
  weekStart: string;
  weekNumber: number;
  daysInMonth: DayOfWeek[];
  timesheet: TimesheetWeek | null;
}

interface MonthResponse {
  monthYear: string;
  monthTotalHours: number;
  submittedWeeks: number;
  totalWeeks: number;
  weeks: WeekEntry[];
}

const DAY_LABEL: Record<DayOfWeek, string> = {
  MONDAY: 'Mon', TUESDAY: 'Tue', WEDNESDAY: 'Wed', THURSDAY: 'Thu', FRIDAY: 'Fri',
  SATURDAY: 'Sat', SUNDAY: 'Sun',
};

export default function InternTimesheetsPage() {
  const [period, setPeriod] = usePeriodFromUrl();
  const [data, setData] = useState<MonthResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<MonthResponse>(
        `/api/v1/timesheets/me/month?y=${period.year}&m=${period.month}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? e?.message ?? 'Could not load timesheets');
    } finally {
      setLoading(false);
    }
  }, [period.year, period.month]);

  useEffect(() => { void load(); }, [load]);

  function patchWeek(weekStart: string, next: TimesheetWeek) {
    setData((prev) => {
      if (!prev) return prev;
      const weeks = prev.weeks.map((w) =>
        w.weekStart === weekStart ? { ...w, timesheet: next } : w,
      );
      const monthTotal = weeks.reduce((acc, w) => {
        if (!w.timesheet) return acc;
        const inScope = new Set(w.daysInMonth);
        const hours = w.timesheet.days
          .filter((d) => inScope.has(d.dayOfWeek))
          .reduce((s, d) => s + Number(d.hours ?? 0), 0);
        return acc + hours;
      }, 0);
      const submitted = weeks.filter(
        (w) => w.timesheet && w.timesheet.status !== 'DRAFT' && w.timesheet.status !== 'REJECTED',
      ).length;
      return {
        ...prev,
        weeks,
        monthTotalHours: Math.round(monthTotal * 100) / 100,
        submittedWeeks: submitted,
      };
    });
  }

  const pendingCount = data
    ? data.weeks.filter((w) =>
        !w.timesheet
        || w.timesheet.status === 'DRAFT'
        || w.timesheet.status === 'REJECTED',
      ).length
    : 0;

  return (
    <InternPageShell title="Timesheets" subtitle="Enter daily hours and submit per week">
      <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <PeriodPicker value={period} onChange={setPeriod} />
        {data && (
          <div className="flex items-center gap-3 text-xs text-slate-600">
            <span className="rounded-md bg-slate-100 px-2 py-1">
              <strong className="text-slate-900">{data.monthTotalHours.toFixed(2)}h</strong>
              {' total this month'}
            </span>
            <span className={
              'rounded-md px-2 py-1 '
              + (pendingCount === 0
                  ? 'bg-green-50 text-green-800'
                  : 'bg-amber-50 text-amber-900')
            }>
              {pendingCount === 0
                ? 'All weeks submitted'
                : `${pendingCount}/${data.totalWeeks} week${pendingCount === 1 ? '' : 's'} still to submit`}
            </span>
          </div>
        )}
      </div>

      {err && (
        <p className="mb-4 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      {loading && !data ? (
        <div className="space-y-3" aria-hidden>
          <div className="h-32 animate-pulse rounded-lg bg-slate-100" />
          <div className="h-32 animate-pulse rounded-lg bg-slate-100" />
        </div>
      ) : !data || data.weeks.length === 0 ? (
        <p className="rounded-lg border border-dashed border-slate-300 bg-white p-10 text-center text-sm text-slate-500">
          No work-weeks for {formatPeriod(period)}.
        </p>
      ) : (
        <ul className="space-y-4">
          {data.weeks.map((w) => (
            <li key={w.weekStart}>
              <WeekCard entry={w} onChange={patchWeek} />
            </li>
          ))}
        </ul>
      )}
    </InternPageShell>
  );
}

// ── Week card ─────────────────────────────────────────────────────────────

function WeekCard({
  entry, onChange,
}: { entry: WeekEntry; onChange: (weekStart: string, next: TimesheetWeek) => void }) {
  const t = entry.timesheet;
  const status: TimesheetStatus = t?.status ?? 'DRAFT';
  const locked = status === 'SUBMITTED' || status === 'VERIFIED' || status === 'APPROVED';

  const inScope = useMemo(() => new Set(entry.daysInMonth), [entry.daysInMonth]);
  const weekTotal = useMemo(() => {
    if (!t) return 0;
    return t.days
      .filter((d) => inScope.has(d.dayOfWeek))
      .reduce((s, d) => s + Number(d.hours ?? 0), 0);
  }, [t, inScope]);

  return (
    <section className={
      'rounded-lg border bg-white p-4 shadow-sm '
      + (locked ? 'border-slate-200' : 'border-slate-300')
    }>
      <header className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="text-sm font-semibold text-slate-900">
            Week {entry.weekNumber} · {fmtDate(entry.weekStart)}–{fmtDate(addDays(entry.weekStart, 4))}
          </h3>
        </div>
        <StatusPill status={status} />
      </header>

      {status === 'REJECTED' && t?.reviewNote && (
        <div className="mt-3 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-900">
          <p className="font-semibold">Returned for correction</p>
          <p className="mt-0.5 whitespace-pre-wrap">{t.reviewNote}</p>
        </div>
      )}

      {/* Status tracker — only meaningful once the intern has submitted
          the week (DRAFT is the pre-submit baseline; the tracker would
          show empty steps with nothing actionable). Mirrors the
          application-tracker visual via StepperHorizontal. */}
      {status !== 'DRAFT' && (
        <div className="mt-3">
          <TimesheetStatusTracker status={status} />
        </div>
      )}

      <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-5">
        {(['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'] as DayOfWeek[]).map((dow) => {
          const inMonth = inScope.has(dow);
          const day = t?.days.find((d) => d.dayOfWeek === dow) ?? null;
          return (
            <DayCell
              key={dow}
              weekStart={entry.weekStart}
              dayOfWeek={dow}
              inMonth={inMonth}
              locked={locked}
              initialHours={day?.hours ?? null}
              onSaved={(updated) => onChange(entry.weekStart, updated)}
            />
          );
        })}
      </div>

      <footer className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-slate-100 pt-3">
        <p className="text-xs text-slate-600">
          Week total: <strong className="text-slate-900">{weekTotal.toFixed(2)}h</strong>
        </p>
        <SubmitButton
          weekStart={entry.weekStart}
          timesheet={t}
          locked={locked}
          weekTotal={weekTotal}
          onSubmitted={(next) => onChange(entry.weekStart, next)}
        />
      </footer>
    </section>
  );
}

function StatusPill({ status }: { status: TimesheetStatus }) {
  const cfg: Record<TimesheetStatus, { tone: string; label: string; icon: React.ReactNode }> = {
    DRAFT:     { tone: 'bg-slate-100 text-slate-700',  label: 'Not submitted', icon: <Clock className="h-3 w-3" /> },
    SUBMITTED: { tone: 'bg-slate-100 text-slate-700',    label: 'Submitted',     icon: <Lock className="h-3 w-3" /> },
    VERIFIED:  { tone: 'bg-slate-100 text-slate-700', label: 'Verified',     icon: <Lock className="h-3 w-3" /> },
    APPROVED:  { tone: 'bg-green-100 text-green-800', label: 'Approved',   icon: <CheckCircle2 className="h-3 w-3" /> },
    REJECTED:  { tone: 'bg-red-100 text-red-800',    label: 'Returned',      icon: <AlertTriangle className="h-3 w-3" /> },
  };
  const c = cfg[status];
  return (
    <span className={
      'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ring-inset ring-slate-200 '
      + c.tone
    }>
      {c.icon}
      {c.label}
    </span>
  );
}

// ── Day cell ──────────────────────────────────────────────────────────────

function DayCell({
  weekStart, dayOfWeek, inMonth, locked, initialHours, onSaved,
}: {
  weekStart: string;
  dayOfWeek: DayOfWeek;
  inMonth: boolean;
  locked: boolean;
  initialHours: number | null;
  onSaved: (next: TimesheetWeek) => void;
}) {
  const [raw, setRaw] = useState<string>(initialHours != null ? String(initialHours) : '');
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  // Keep the input in sync when the parent reloads (e.g. after submit
  // re-fetches the canonical row) but don't clobber what the intern is
  // currently typing.
  const lastInitial = useRef<number | null | undefined>(undefined);
  useEffect(() => {
    if (lastInitial.current !== initialHours) {
      lastInitial.current = initialHours;
      setRaw(initialHours != null ? String(initialHours) : '');
    }
  }, [initialHours]);

  const disabled = !inMonth || locked;

  async function save() {
    setErr(null);
    const trimmed = raw.trim();
    const hours = trimmed === '' ? 0 : Number(trimmed);
    if (!isFinite(hours) || hours < 0 || hours > 24) {
      setErr('0–24');
      return;
    }
    if (initialHours != null && Math.abs(hours - initialHours) < 0.001) return;
    setSaving(true);
    try {
      const res = await api.put<TimesheetWeek>(
        `/api/v1/timesheets/me/day?weekStart=${weekStart}&day=${dayOfWeek}`,
        { hours },
      );
      onSaved(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div
      className={
        'rounded-md border p-2 text-xs '
        + (inMonth ? 'border-slate-200 bg-white' : 'border-slate-100 bg-slate-50')
      }
    >
      <div className="flex items-center justify-between">
        <span className={inMonth ? 'font-medium text-slate-700' : 'text-slate-400'}>
          {DAY_LABEL[dayOfWeek]}
        </span>
        {saving && <Loader2 className="h-3 w-3 animate-spin text-slate-400" />}
      </div>
      <input
        type="number"
        inputMode="decimal"
        min={0}
        max={24}
        step="0.25"
        value={raw}
        onChange={(e) => setRaw(e.target.value)}
        onBlur={() => { if (!disabled) void save(); }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && !disabled) (e.target as HTMLInputElement).blur();
        }}
        disabled={disabled}
        placeholder={inMonth ? '0' : '—'}
        className={
          'mt-1 w-full rounded-md border px-2 py-1 text-sm '
          + (err ? 'border-red-400 ' : 'border-slate-200 ')
          + (disabled ? 'cursor-not-allowed bg-slate-100 text-slate-500' : 'bg-white')
        }
        aria-label={`Hours for ${DAY_LABEL[dayOfWeek]}, week of ${weekStart}`}
      />
      {err && <p className="mt-1 text-[10px] text-red-700">{err}</p>}
    </div>
  );
}

// ── Submit button ─────────────────────────────────────────────────────────

function SubmitButton({
  weekStart, timesheet, locked, weekTotal, onSubmitted,
}: {
  weekStart: string;
  timesheet: TimesheetWeek | null;
  locked: boolean;
  weekTotal: number;
  onSubmitted: (next: TimesheetWeek) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  if (locked) {
    return (
      <div className="flex items-center gap-2 text-xs text-slate-500">
        {timesheet?.submittedAt && (
          <span>Submitted {fmtInstant(timesheet.submittedAt)}</span>
        )}
        {timesheet?.approvedAt && (
          <span>· Approved {fmtInstant(timesheet.approvedAt)}</span>
        )}
      </div>
    );
  }

  const canSubmit = timesheet != null && weekTotal > 0;

  async function submit() {
    if (!timesheet) {
      setErr('Enter hours for at least one day first.');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      await api.post(`/api/v1/timesheets/${timesheet.id}/submit`);
      // Re-fetch via the week endpoint so we get fresh status + timestamps.
      const res = await api.get<TimesheetWeek>(`/api/v1/timesheets/${timesheet.id}`);
      onSubmitted(res.data);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Submit failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col items-end gap-1">
      <button
        type="button"
        onClick={submit}
        disabled={busy || !canSubmit}
        className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-50"
        title={!canSubmit ? 'Enter hours before submitting' : undefined}
      >
        <Send className="h-3 w-3" />
        {busy ? 'Submitting…' : 'Submit week'}
      </button>
      {err && <p className="text-[10px] text-red-700">{err}</p>}
    </div>
  );
}

// ── Helpers ───────────────────────────────────────────────────────────────

function fmtDate(iso: string): string {
  try {
    return new Date(iso + 'T00:00:00').toLocaleDateString('en-US', {
      month: 'short', day: 'numeric',
    });
  } catch { return iso; }
}

function fmtInstant(iso: string): string {
  try {
    return new Date(iso).toLocaleString('en-US', {
      month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
    });
  } catch { return iso; }
}

function addDays(iso: string, n: number): string {
  const d = new Date(iso + 'T00:00:00');
  d.setDate(d.getDate() + n);
  return d.toISOString().slice(0, 10);
}
