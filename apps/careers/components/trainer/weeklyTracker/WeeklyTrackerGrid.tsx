'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  CalendarPlus, Check, CheckCircle2, Clock, Loader2,
  RotateCcw, Video, XCircle,
} from 'lucide-react';
import api from '@/lib/api';

// ── Types (mirror WeeklyTrackerDtos.TrackerResponse) ─────────────────────

export type WeekStatus = 'PENDING' | 'SCHEDULED' | 'DONE' | 'MISSED' | 'CANCELLED';

export interface WeekSlot {
  weekNumber: number;
  weekStart: string; // ISO date "YYYY-MM-DD"
}
export interface InternWeekCell {
  weekNumber: number;
  weekStart: string;
  status: WeekStatus;
  meetingId: string | null;
  scheduledFor: string | null;
  durationMinutes: number | null;
  timezone: string | null;
  topic: string | null;
  zoomJoinUrl: string | null;
  zoomStartUrl: string | null;
}
export interface InternRow {
  internLifecycleId: string;
  internUserId: string | null;
  internName: string | null;
  employeeId: string | null;
  doneCount: number;
  scheduledCount: number;
  pendingCount: number;
  missedCount: number;
  weeks: InternWeekCell[];
}
export interface TrackerResponse {
  year: number;
  month: number;
  asOf: string;
  weeks: WeekSlot[];
  interns: InternRow[];
}

// ── Component ────────────────────────────────────────────────────────────

interface Props {
  /** When set, narrows the grid to a single intern (the per-intern strip on
   *  the intern detail page). Omit for the master view. */
  internLifecycleId?: string;
  /** YearMonth (default = current). Reserved for a future month switcher. */
  year?: number;
  month?: number;
  /** Compact mode for the per-intern strip — drops the leading intern
   *  column + the per-row summary chips. */
  compact?: boolean;
}

export default function WeeklyTrackerGrid({
  internLifecycleId, year, month, compact = false,
}: Props) {
  const [data, setData] = useState<TrackerResponse | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [action, setAction] = useState<{
    mode: 'schedule' | 'complete';
    internLifecycleId: string;
    internName: string | null;
    weekStart: string;
    weekNumber: number;
    meetingId?: string;
  } | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (year) params.set('y', String(year));
      if (month) params.set('m', String(month));
      if (internLifecycleId) params.set('internLifecycleId', internLifecycleId);
      const qs = params.toString();
      const res = await api.get<TrackerResponse>(
        '/api/v1/trainer/weekly-tracker' + (qs ? '?' + qs : ''));
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load tracker');
    } finally {
      setLoading(false);
    }
  }, [internLifecycleId, year, month]);

  useEffect(() => { void load(); }, [load]);

  const headerTitle = useMemo(() => {
    if (!data) return 'Weekly sessions';
    const d = new Date(data.year, data.month - 1, 1);
    const m = d.toLocaleString(undefined, { month: 'long', year: 'numeric' });
    return `Weekly sessions · ${m}`;
  }, [data]);

  if (loading && !data) {
    return (
      <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <div className="h-5 w-48 animate-pulse rounded bg-slate-100" />
        <div className="mt-3 h-20 animate-pulse rounded bg-slate-50" />
      </section>
    );
  }
  if (err) {
    return (
      <section className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
        {err}
      </section>
    );
  }
  if (!data || data.interns.length === 0) {
    return (
      <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <h3 className="text-sm font-semibold text-slate-900">{headerTitle}</h3>
        <p className="mt-2 text-xs text-slate-500">
          {internLifecycleId
            ? 'No active intern session to track for this month.'
            : 'No active interns in your roster yet.'}
        </p>
      </section>
    );
  }

  return (
    <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
      <header className="flex flex-wrap items-baseline justify-between gap-2 border-b border-slate-100 px-4 py-3">
        <h3 className="text-sm font-semibold text-slate-900">{headerTitle}</h3>
        <p className="text-[11px] text-slate-500">
          {data.interns.length} intern{data.interns.length === 1 ? '' : 's'} · {data.weeks.length} week{data.weeks.length === 1 ? '' : 's'}
        </p>
      </header>

      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="border-b border-slate-100 text-left text-[10px] uppercase tracking-wide text-slate-500">
              {!compact && (
                <th className="sticky left-0 z-10 bg-white px-4 py-2 font-semibold">
                  Intern
                </th>
              )}
              {data.weeks.map((w) => (
                <th key={w.weekStart} className="px-2 py-2 font-semibold">
                  W{w.weekNumber}
                  <div className="font-normal normal-case text-[9px] text-slate-400">
                    {shortDate(w.weekStart)}
                  </div>
                </th>
              ))}
              {!compact && (
                <th className="px-2 py-2 font-semibold">Summary</th>
              )}
            </tr>
          </thead>
          <tbody>
            {data.interns.map((row) => (
              <tr key={row.internLifecycleId} className="border-b border-slate-50 last:border-b-0">
                {!compact && (
                  <td className="sticky left-0 z-10 bg-white px-4 py-2 align-top">
                    <div className="font-medium text-slate-900">
                      {row.internName ?? '(unknown)'}
                    </div>
                    {row.employeeId && (
                      <div className="text-[10px] text-slate-500">{row.employeeId}</div>
                    )}
                  </td>
                )}
                {row.weeks.map((cell) => (
                  <td key={cell.weekStart} className="px-1.5 py-2 align-top">
                    <WeekCell
                      cell={cell}
                      onSchedule={() => setAction({
                        mode: 'schedule',
                        internLifecycleId: row.internLifecycleId,
                        internName: row.internName,
                        weekStart: cell.weekStart,
                        weekNumber: cell.weekNumber,
                      })}
                      onComplete={() => setAction({
                        mode: 'complete',
                        internLifecycleId: row.internLifecycleId,
                        internName: row.internName,
                        weekStart: cell.weekStart,
                        weekNumber: cell.weekNumber,
                        meetingId: cell.meetingId ?? undefined,
                      })}
                    />
                  </td>
                ))}
                {!compact && (
                  <td className="px-2 py-2 text-[10px] align-top">
                    <SummaryChips row={row} />
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {action?.mode === 'schedule' && (
        <ScheduleWeekModal
          internLifecycleId={action.internLifecycleId}
          internName={action.internName}
          weekStart={action.weekStart}
          weekNumber={action.weekNumber}
          onClose={() => setAction(null)}
          onSaved={() => { setAction(null); void load(); }}
        />
      )}
      {action?.mode === 'complete' && action.meetingId && (
        <QuickCompleteModal
          meetingId={action.meetingId}
          internName={action.internName}
          weekStart={action.weekStart}
          weekNumber={action.weekNumber}
          onClose={() => setAction(null)}
          onSaved={() => { setAction(null); void load(); }}
        />
      )}
    </section>
  );
}

// ── Per-cell render ──────────────────────────────────────────────────────

function WeekCell({
  cell, onSchedule, onComplete,
}: {
  cell: InternWeekCell;
  onSchedule: () => void;
  onComplete: () => void;
}) {
  if (cell.status === 'DONE') {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-[10px] font-semibold text-green-800">
        <CheckCircle2 className="h-3 w-3" /> Done
      </span>
    );
  }
  if (cell.status === 'SCHEDULED') {
    return (
      <div className="space-y-1">
        <span className="inline-flex items-center gap-1 rounded-full bg-brand-100 px-2 py-0.5 text-[10px] font-semibold text-brand-800">
          <Clock className="h-3 w-3" /> Scheduled
        </span>
        <div className="text-[10px] text-slate-600">
          {cell.scheduledFor ? shortTime(cell.scheduledFor) : ''}
        </div>
        <div className="flex flex-wrap gap-1">
          {(cell.zoomStartUrl ?? cell.zoomJoinUrl) && (
            <a
              href={cell.zoomStartUrl ?? cell.zoomJoinUrl ?? '#'}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-0.5 rounded-md border border-brand-300 bg-white px-1.5 py-0.5 text-[10px] font-semibold text-brand-700 hover:bg-brand-50"
              title={cell.zoomStartUrl ? 'Join as host' : 'Join meeting'}
            >
              <Video className="h-2.5 w-2.5" />
              {cell.zoomStartUrl ? 'Host' : 'Join'}
            </a>
          )}
          <button
            type="button"
            onClick={onComplete}
            className="inline-flex items-center gap-0.5 rounded-md bg-brand-700 px-1.5 py-0.5 text-[10px] font-semibold text-white hover:bg-brand-800"
          >
            <Check className="h-2.5 w-2.5" /> Done
          </button>
        </div>
      </div>
    );
  }
  if (cell.status === 'MISSED') {
    return (
      <div className="space-y-1">
        <span className="inline-flex items-center gap-1 rounded-full bg-red-100 px-2 py-0.5 text-[10px] font-semibold text-red-800">
          <XCircle className="h-3 w-3" /> Missed
        </span>
        <button
          type="button"
          onClick={onSchedule}
          className="inline-flex items-center gap-0.5 rounded-md border border-slate-300 bg-white px-1.5 py-0.5 text-[10px] font-medium text-slate-700 hover:bg-slate-50"
          title="Re-schedule a replacement session for this week"
        >
          <RotateCcw className="h-2.5 w-2.5" /> Reschedule
        </button>
      </div>
    );
  }
  // PENDING (or CANCELLED → mapped to PENDING server-side)
  return (
    <button
      type="button"
      onClick={onSchedule}
      className="inline-flex items-center gap-1 rounded-md border border-slate-300 bg-white px-2 py-0.5 text-[10px] font-medium text-slate-700 hover:border-brand-300 hover:bg-brand-50 hover:text-brand-800"
      title="Schedule a weekly session for this week"
    >
      <CalendarPlus className="h-3 w-3" /> Schedule
    </button>
  );
}

function SummaryChips({ row }: { row: InternRow }) {
  return (
    <div className="flex flex-wrap gap-1">
      {row.doneCount > 0 && (
        <span className="rounded-full bg-green-100 px-1.5 py-0.5 font-semibold text-green-800">
          {row.doneCount} done
        </span>
      )}
      {row.scheduledCount > 0 && (
        <span className="rounded-full bg-brand-100 px-1.5 py-0.5 font-semibold text-brand-800">
          {row.scheduledCount} scheduled
        </span>
      )}
      {row.pendingCount > 0 && (
        <span className="rounded-full bg-amber-100 px-1.5 py-0.5 font-semibold text-amber-900">
          {row.pendingCount} to schedule
        </span>
      )}
      {row.missedCount > 0 && (
        <span className="rounded-full bg-red-100 px-1.5 py-0.5 font-semibold text-red-800">
          {row.missedCount} missed
        </span>
      )}
    </div>
  );
}

// ── Modals ───────────────────────────────────────────────────────────────

function ScheduleWeekModal({
  internLifecycleId, internName, weekStart, weekNumber, onClose, onSaved,
}: {
  internLifecycleId: string;
  internName: string | null;
  weekStart: string;       // "YYYY-MM-DD" (the week's Monday)
  weekNumber: number;
  onClose: () => void;
  onSaved: () => void;
}) {
  // Default: this week's Monday at 10:00 local time.
  const initialDt = `${weekStart}T10:00`;
  const [dt, setDt] = useState<string>(initialDt);
  const [duration, setDuration] = useState<number>(30);
  const [tz, setTz] = useState<string>(() => {
    try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; }
    catch { return 'UTC'; }
  });
  const [topic, setTopic] = useState<string>('Weekly sync');
  const [agenda, setAgenda] = useState<string>('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    if (!dt) { setErr('Pick a date + time.'); return; }
    setBusy(true); setErr(null);
    try {
      const iso = new Date(dt).toISOString();
      await api.post('/api/v1/trainer/weekly-meetings', {
        internLifecycleId,
        scheduledFor: iso,
        durationMinutes: duration,
        timezone: tz,
        topic: topic.trim() || 'Weekly sync',
        agenda: agenda.trim() || null,
        recurrence: null,
      });
      onSaved();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to schedule');
    } finally { setBusy(false); }
  };

  return (
    <ModalShell onClose={onClose}>
      <div className="border-b border-slate-200 px-5 py-3">
        <h3 className="text-base font-semibold text-slate-900">
          Schedule Week {weekNumber}
          {internName ? ' — ' + internName : ''}
        </h3>
        <p className="mt-0.5 text-xs text-slate-600">
          Creates a Zoom meeting; intern is notified by mail + in-app.
        </p>
      </div>
      <div className="space-y-3 px-5 py-4 text-sm">
        <div className="grid gap-2 sm:grid-cols-3">
          <div className="sm:col-span-2">
            <label className="block text-[11px] font-medium text-slate-700">When</label>
            <input
              type="datetime-local"
              value={dt}
              onChange={(e) => setDt(e.target.value)}
              className="w-full rounded-md border border-slate-300 px-2 py-1 text-sm"
            />
          </div>
          <div>
            <label className="block text-[11px] font-medium text-slate-700">Duration (min)</label>
            <input
              type="number"
              min={15}
              max={180}
              step={5}
              value={duration}
              onChange={(e) => setDuration(Math.max(15, Math.min(180, Number(e.target.value) || 30)))}
              className="w-full rounded-md border border-slate-300 px-2 py-1 text-sm"
            />
          </div>
        </div>
        <div>
          <label className="block text-[11px] font-medium text-slate-700">Timezone</label>
          <input
            type="text"
            value={tz}
            onChange={(e) => setTz(e.target.value)}
            className="w-full rounded-md border border-slate-300 px-2 py-1 text-sm"
          />
        </div>
        <div>
          <label className="block text-[11px] font-medium text-slate-700">Topic</label>
          <input
            type="text"
            value={topic}
            onChange={(e) => setTopic(e.target.value)}
            className="w-full rounded-md border border-slate-300 px-2 py-1 text-sm"
          />
        </div>
        <div>
          <label className="block text-[11px] font-medium text-slate-700">Agenda (optional)</label>
          <textarea
            value={agenda}
            onChange={(e) => setAgenda(e.target.value)}
            rows={2}
            className="w-full resize-y rounded-md border border-slate-300 px-2 py-1 text-sm"
          />
        </div>
        {err && (
          <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
            {err}
          </p>
        )}
      </div>
      <ModalFooter onCancel={onClose} onConfirm={submit} busy={busy} confirmLabel="Schedule" />
    </ModalShell>
  );
}

function QuickCompleteModal({
  meetingId, internName, weekStart, weekNumber, onClose, onSaved,
}: {
  meetingId: string;
  internName: string | null;
  weekStart: string;
  weekNumber: number;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [notes, setNotes] = useState<string>('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const submit = async () => {
    setBusy(true); setErr(null);
    try {
      await api.post(`/api/v1/trainer/weekly-meetings/${meetingId}/quick-complete`, {
        notes: notes.trim() || null,
      });
      onSaved();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to mark done');
    } finally { setBusy(false); }
  };

  return (
    <ModalShell onClose={onClose}>
      <div className="border-b border-slate-200 px-5 py-3">
        <h3 className="text-base font-semibold text-slate-900">
          Mark Week {weekNumber} done
          {internName ? ' — ' + internName : ''}
        </h3>
        <p className="mt-0.5 text-xs text-slate-600">
          Optional short note. Use Weekly Meetings → Complete for a full
          summary; this is the one-click variant.
        </p>
      </div>
      <div className="space-y-2 px-5 py-4 text-sm">
        <label className="block text-[11px] font-medium text-slate-700">
          Notes (optional)
        </label>
        <textarea
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          rows={3}
          placeholder={`Brief recap of week of ${weekStart}…`}
          className="w-full resize-y rounded-md border border-slate-300 px-2 py-1 text-sm"
        />
        {err && (
          <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
            {err}
          </p>
        )}
      </div>
      <ModalFooter onCancel={onClose} onConfirm={submit} busy={busy} confirmLabel="Mark done" />
    </ModalShell>
  );
}

function ModalShell({
  children, onClose,
}: { children: React.ReactNode; onClose: () => void }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      onClick={onClose}>
      <div className="w-full max-w-md rounded-lg bg-white shadow-xl"
        onClick={(e) => e.stopPropagation()}>
        {children}
      </div>
    </div>
  );
}

function ModalFooter({
  onCancel, onConfirm, busy, confirmLabel,
}: {
  onCancel: () => void;
  onConfirm: () => void;
  busy: boolean;
  confirmLabel: string;
}) {
  return (
    <div className="flex justify-end gap-2 border-t border-slate-200 px-5 py-3">
      <button
        type="button"
        onClick={onCancel}
        className="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50"
      >
        Cancel
      </button>
      <button
        type="button"
        onClick={onConfirm}
        disabled={busy}
        className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
      >
        {busy && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
        {busy ? 'Saving…' : confirmLabel}
      </button>
    </div>
  );
}

// ── Helpers ──────────────────────────────────────────────────────────────

function shortDate(iso: string): string {
  try {
    return new Date(iso + 'T00:00:00').toLocaleDateString(undefined, {
      month: 'short', day: 'numeric',
    });
  } catch { return iso; }
}
function shortTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString(undefined, {
      month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
    });
  } catch { return iso; }
}
