'use client';

/**
 * Phase B2 — shared weekly-rollup table for ERM verify + Manager approve.
 * Rows = interns in scope; columns = the Mon–Fri work-weeks of the
 * selected month. Each cell shows the week's TOTAL hours + a stage
 * badge; daily breakdown is hidden by default and expandable per week.
 *
 * <p>Reused unchanged between the ERM and Manager surfaces — the only
 * difference is the {@code actionLabel} ("Verify" vs "Approve") + the
 * status the per-row action is eligible for. The action handler is
 * supplied by the caller so each surface wires its own endpoint.</p>
 */

import { useMemo, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Lock,
  ShieldCheck,
  XCircle,
} from 'lucide-react';

export type TimesheetStatus = 'DRAFT' | 'SUBMITTED' | 'VERIFIED' | 'APPROVED' | 'REJECTED';
export type DayOfWeek = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY'
  | 'SATURDAY' | 'SUNDAY';

export interface RollupDay {
  id: string;
  dayOfWeek: DayOfWeek;
  hours: number;
  notes: string | null;
}

export interface RollupCell {
  weekStart: string;
  weekNumber: number;
  timesheetId: string | null;
  status: TimesheetStatus | null;
  totalHours: number;
  days: RollupDay[];
  submittedAt: string | null;
  verifiedByName: string | null;
  verifiedAt: string | null;
  approvedByName: string | null;
  approvedAt: string | null;
  reviewNote: string | null;
}

export interface RollupColumn {
  weekStart: string;
  weekNumber: number;
  daysInMonth: DayOfWeek[];
}

export interface RollupRow {
  lifecycleId: string;
  internUserId: string;
  fullName: string | null;
  employeeId: string | null;
  technologyTitle: string | null;
  managerId: string | null;
  managerName: string | null;
  monthTotalHours: number;
  weeks: RollupCell[];
}

export interface RollupSummary {
  totalInterns: number;
  submittedWeeks: number;
  verifiedWeeks: number;
  approvedWeeks: number;
  rejectedWeeks: number;
  missingWeeks: number;
}

export interface WeeklyRollupTableProps {
  columns: RollupColumn[];
  rows: RollupRow[];
  summary: RollupSummary;
  /** Status the primary action is eligible for. */
  actionFor: TimesheetStatus;
  actionLabel: string;
  batchActionLabel: string;
  /** Async — server returns the updated cell (or throws). */
  onAction: (cell: RollupCell, row: RollupRow) => Promise<void>;
  /** Async — server applies rejection with reason. */
  onReject: (cell: RollupCell, row: RollupRow, reason: string) => Promise<void>;
  /** Batch action — service iterates ids; resolves to a summary string. */
  onBatchAction: (ids: string[]) => Promise<{ ok: number; skipped: number }>;
  /** Optional empty-state copy for the table body. */
  emptyMessage?: string;
}

const DAY_LABEL: Record<DayOfWeek, string> = {
  MONDAY: 'Mon', TUESDAY: 'Tue', WEDNESDAY: 'Wed', THURSDAY: 'Thu', FRIDAY: 'Fri',
  SATURDAY: 'Sat', SUNDAY: 'Sun',
};

export default function WeeklyRollupTable({
  columns, rows, summary, actionFor, actionLabel, batchActionLabel,
  onAction, onReject, onBatchAction, emptyMessage,
}: WeeklyRollupTableProps) {
  const [expandedKey, setExpandedKey] = useState<string | null>(null);
  const [batchBusy, setBatchBusy] = useState(false);
  const [batchMsg, setBatchMsg] = useState<string | null>(null);

  const batchIds = useMemo(() => {
    const ids: string[] = [];
    for (const r of rows) {
      for (const c of r.weeks) {
        if (c.timesheetId && c.status === actionFor) ids.push(c.timesheetId);
      }
    }
    return ids;
  }, [rows, actionFor]);

  async function runBatch() {
    setBatchBusy(true);
    setBatchMsg(null);
    try {
      const out = await onBatchAction(batchIds);
      setBatchMsg(`${out.ok} ${actionLabel.toLowerCase()}d` + (out.skipped ? ` · ${out.skipped} skipped` : ''));
    } catch (e: any) {
      setBatchMsg(e?.response?.data?.error ?? 'Batch failed');
    } finally {
      setBatchBusy(false);
    }
  }

  return (
    <div className="space-y-3">
      <SummaryStrip summary={summary} />

      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-slate-600">
          {rows.length} intern{rows.length === 1 ? '' : 's'} · click any week to expand its daily breakdown
        </p>
        <div className="flex items-center gap-2">
          {batchMsg && <span className="text-xs text-slate-600">{batchMsg}</span>}
          <button
            type="button"
            onClick={runBatch}
            disabled={batchBusy || batchIds.length === 0}
            className="inline-flex items-center gap-1 rounded-md border border-brand-700 bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-40"
            title={batchIds.length === 0
              ? `No ${actionFor.toLowerCase()} weeks to ${actionLabel.toLowerCase()}`
              : undefined}
          >
            {batchBusy ? '…' : `${batchActionLabel} (${batchIds.length})`}
          </button>
        </div>
      </div>

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {rows.length === 0 ? (
          <p className="p-10 text-center text-sm text-slate-500">
            {emptyMessage ?? 'No interns to show.'}
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="bg-slate-50">
                <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                  <th className="sticky left-0 z-10 bg-slate-50 px-3 py-2">Intern</th>
                  {columns.map((c) => (
                    <th key={c.weekStart} className="px-2 py-2 text-center">
                      <div>W{c.weekNumber}</div>
                      <div className="text-[10px] font-normal lowercase text-slate-400">
                        {fmtRange(c.weekStart)}
                      </div>
                    </th>
                  ))}
                  <th className="px-3 py-2 text-right">Month</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {rows.map((row) => (
                  <Row
                    key={row.lifecycleId}
                    row={row}
                    columns={columns}
                    actionFor={actionFor}
                    actionLabel={actionLabel}
                    expandedKey={expandedKey}
                    setExpandedKey={setExpandedKey}
                    onAction={(c) => onAction(c, row)}
                    onReject={(c, reason) => onReject(c, row, reason)}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

// ── Summary ────────────────────────────────────────────────────────────────

function SummaryStrip({ summary }: { summary: RollupSummary }) {
  const tiles = [
    { label: 'Interns', value: summary.totalInterns, tone: 'slate' },
    { label: 'Submitted', value: summary.submittedWeeks, tone: summary.submittedWeeks > 0 ? 'blue' : 'slate' },
    { label: 'Verified', value: summary.verifiedWeeks, tone: summary.verifiedWeeks > 0 ? 'indigo' : 'slate' },
    { label: 'Approved', value: summary.approvedWeeks, tone: 'emerald' },
    { label: 'Rejected', value: summary.rejectedWeeks, tone: summary.rejectedWeeks > 0 ? 'rose' : 'slate' },
    { label: 'Missing', value: summary.missingWeeks, tone: summary.missingWeeks > 0 ? 'amber' : 'slate' },
  ];
  const bg: Record<string, string> = {
    slate: 'bg-slate-50 text-slate-900',
    blue: 'bg-slate-100 text-slate-700',
    indigo: 'bg-slate-100 text-slate-700',
    emerald: 'bg-green-50 text-green-900',
    rose: 'bg-red-50 text-red-900',
    amber: 'bg-amber-50 text-amber-900',
  };
  return (
    <section className="grid grid-cols-2 gap-2 rounded-lg border border-slate-200 bg-white p-3 sm:grid-cols-3 lg:grid-cols-6">
      {tiles.map((t) => (
        <div key={t.label} className={'rounded-md p-2 ' + bg[t.tone]}>
          <p className="text-[10px] font-semibold uppercase tracking-wide opacity-80">{t.label}</p>
          <p className="mt-0.5 text-lg font-semibold">{t.value}</p>
        </div>
      ))}
    </section>
  );
}

// ── Row ────────────────────────────────────────────────────────────────────

function Row({
  row, columns, actionFor, actionLabel, expandedKey, setExpandedKey,
  onAction, onReject,
}: {
  row: RollupRow;
  columns: RollupColumn[];
  actionFor: TimesheetStatus;
  actionLabel: string;
  expandedKey: string | null;
  setExpandedKey: (k: string | null) => void;
  onAction: (cell: RollupCell) => Promise<void>;
  onReject: (cell: RollupCell, reason: string) => Promise<void>;
}) {
  const expandedCell = row.weeks.find(
    (c) => expandedKey && expandedKey === keyFor(row, c),
  );
  return (
    <>
      <tr className="hover:bg-slate-50">
        <td className="sticky left-0 z-10 bg-white px-3 py-2">
          <p className="text-sm font-medium text-slate-900">{row.fullName ?? '(unknown)'}</p>
          <p className="text-[10px] text-slate-500">
            {row.employeeId ?? '—'}
            {row.technologyTitle ? ' · ' + row.technologyTitle : ''}
          </p>
          {row.managerName && (
            <p className="text-[10px] text-slate-400">Mgr: {row.managerName}</p>
          )}
        </td>
        {columns.map((col) => {
          const cell = row.weeks.find((c) => c.weekStart === col.weekStart);
          if (!cell) return <td key={col.weekStart} className="px-2 py-2 text-center text-xs text-slate-300">—</td>;
          const isExpanded = expandedKey === keyFor(row, cell);
          return (
            <td key={col.weekStart} className="px-2 py-2 text-center">
              <button
                type="button"
                onClick={() => setExpandedKey(isExpanded ? null : keyFor(row, cell))}
                className={
                  'inline-flex flex-col items-center gap-0.5 rounded-md border px-2 py-1 text-xs '
                  + (isExpanded
                      ? 'border-slate-400 bg-slate-50'
                      : 'border-transparent hover:border-slate-200 hover:bg-slate-50')
                }
                aria-expanded={isExpanded}
                aria-label={`${cell.totalHours.toFixed(2)}h · ${cell.status ?? 'MISSING'} · week ${col.weekNumber}`}
              >
                <span className="font-mono text-sm font-semibold text-slate-900">
                  {cell.status == null ? '—' : cell.totalHours.toFixed(2)}
                </span>
                <StatusChip status={cell.status} />
              </button>
            </td>
          );
        })}
        <td className="px-3 py-2 text-right text-sm font-semibold text-slate-900">
          {row.monthTotalHours.toFixed(2)}h
        </td>
      </tr>
      {expandedCell && (
        <tr className="bg-slate-50/60">
          <td colSpan={columns.length + 2} className="px-4 py-3">
            <CellExpanded
              row={row}
              cell={expandedCell}
              actionFor={actionFor}
              actionLabel={actionLabel}
              onClose={() => setExpandedKey(null)}
              onAction={onAction}
              onReject={onReject}
            />
          </td>
        </tr>
      )}
    </>
  );
}

function keyFor(row: RollupRow, cell: RollupCell): string {
  return row.lifecycleId + '::' + cell.weekStart;
}

// ── Status chip ────────────────────────────────────────────────────────────

function StatusChip({ status }: { status: TimesheetStatus | null }) {
  if (status == null) {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-600 ring-1 ring-slate-200 ring-inset">
        Missing
      </span>
    );
  }
  const cfg: Record<TimesheetStatus, { tone: string; label: string; icon: React.ReactNode }> = {
    DRAFT:     { tone: 'bg-slate-100 text-slate-700 ring-slate-200',          label: 'Draft',     icon: null },
    SUBMITTED: { tone: 'bg-slate-100 text-slate-700 ring-slate-200',              label: 'Submitted', icon: <Lock className="h-2.5 w-2.5" /> },
    VERIFIED:  { tone: 'bg-slate-100 text-slate-700 ring-slate-200',        label: 'Verified',  icon: <ShieldCheck className="h-2.5 w-2.5" /> },
    APPROVED:  { tone: 'bg-green-50 text-green-800 ring-green-200',     label: 'Approved',  icon: <CheckCircle2 className="h-2.5 w-2.5" /> },
    REJECTED:  { tone: 'bg-red-50 text-red-800 ring-red-200',              label: 'Rejected',  icon: <AlertTriangle className="h-2.5 w-2.5" /> },
  };
  const c = cfg[status];
  return (
    <span className={
      'inline-flex items-center gap-0.5 rounded-full px-1.5 py-0.5 text-[10px] font-medium ring-1 ring-inset '
      + c.tone
    }>
      {c.icon}
      {c.label}
    </span>
  );
}

// ── Expanded cell ─────────────────────────────────────────────────────────

function CellExpanded({
  row, cell, actionFor, actionLabel, onClose, onAction, onReject,
}: {
  row: RollupRow;
  cell: RollupCell;
  actionFor: TimesheetStatus;
  actionLabel: string;
  onClose: () => void;
  onAction: (cell: RollupCell) => Promise<void>;
  onReject: (cell: RollupCell, reason: string) => Promise<void>;
}) {
  const [busy, setBusy] = useState<'verify' | 'reject' | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');

  async function doAction() {
    setBusy('verify');
    setErr(null);
    try {
      await onAction(cell);
      onClose();
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? `${actionLabel} failed`);
    } finally {
      setBusy(null);
    }
  }

  async function doReject() {
    if (reason.trim().length < 5) {
      setErr('Reason must be at least 5 characters');
      return;
    }
    setBusy('reject');
    setErr(null);
    try {
      await onReject(cell, reason.trim());
      onClose();
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Reject failed');
    } finally {
      setBusy(null);
    }
  }

  const canAct = cell.status === actionFor;
  const canReject = cell.status === 'SUBMITTED' || cell.status === 'VERIFIED';
  const hasDays = cell.days.length > 0;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="text-sm font-semibold text-slate-900">
            Week {cell.weekNumber} · {fmtRange(cell.weekStart)} · {row.fullName}
          </p>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-[11px] text-slate-600">
            <StatusChip status={cell.status} />
            <span><strong className="text-slate-900">{cell.totalHours.toFixed(2)}h</strong> for the week</span>
            {cell.submittedAt && <span>· Submitted {fmtInstant(cell.submittedAt)}</span>}
            {cell.verifiedByName && cell.verifiedAt && (
              <span>· Verified by {cell.verifiedByName} {fmtInstant(cell.verifiedAt)}</span>
            )}
            {cell.approvedByName && cell.approvedAt && (
              <span>· Approved by {cell.approvedByName} {fmtInstant(cell.approvedAt)}</span>
            )}
          </div>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Collapse"
          className="rounded-md p-1 text-slate-500 hover:bg-slate-100"
        >
          <ChevronDown className="h-4 w-4" />
        </button>
      </div>

      {cell.reviewNote && (
        <div className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-900">
          <p className="font-semibold">Last reviewer note</p>
          <p className="mt-0.5 whitespace-pre-wrap">{cell.reviewNote}</p>
        </div>
      )}

      {hasDays ? (
        <div className="rounded-md border border-slate-200 bg-white">
          <table className="min-w-full text-xs">
            <thead className="bg-slate-50">
              <tr className="text-left text-[10px] font-semibold uppercase text-slate-500">
                <th className="px-2 py-1.5">Day</th>
                <th className="px-2 py-1.5 text-right">Hours</th>
                <th className="px-2 py-1.5">Notes</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {cell.days.map((d) => (
                <tr key={d.id}>
                  <td className="px-2 py-1">{DAY_LABEL[d.dayOfWeek]}</td>
                  <td className="px-2 py-1 text-right font-mono">{d.hours.toFixed(2)}</td>
                  <td className="px-2 py-1 text-slate-600">{d.notes ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="text-xs text-slate-500">No day rows yet for this week.</p>
      )}

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>
      )}

      {rejecting ? (
        <div className="space-y-2 rounded-md border border-red-200 bg-red-50 p-2">
          <label className="text-xs font-medium text-red-900">Reason for sending back</label>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            maxLength={500}
            placeholder="What needs to change before the intern resubmits?"
            className="w-full rounded-md border border-red-300 bg-white px-2 py-1 text-xs"
          />
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={() => { setRejecting(false); setReason(''); setErr(null); }}
              className="rounded-md border border-slate-200 px-2 py-1 text-xs"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={doReject}
              disabled={busy != null}
              className="rounded-md border border-red-700 bg-red-700 px-2 py-1 text-xs font-semibold text-white hover:bg-red-800 disabled:opacity-50"
            >
              {busy === 'reject' ? 'Sending…' : 'Send back'}
            </button>
          </div>
        </div>
      ) : (
        <div className="flex justify-end gap-2">
          {canReject && (
            <button
              type="button"
              onClick={() => setRejecting(true)}
              className="inline-flex items-center gap-1 rounded-md border border-red-300 bg-white px-2.5 py-1 text-xs font-medium text-red-700 hover:bg-red-50"
            >
              <XCircle className="h-3 w-3" /> Reject
            </button>
          )}
          {canAct && (
            <button
              type="button"
              onClick={doAction}
              disabled={busy != null}
              className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-50"
            >
              {busy === 'verify' ? '…' : (
                <>
                  <CheckCircle2 className="h-3 w-3" />
                  {actionLabel}
                </>
              )}
            </button>
          )}
          {!canAct && !canReject && (
            <span className="inline-flex items-center gap-1 text-xs text-slate-500">
              <ChevronRight className="h-3 w-3" /> No action available for {cell.status ?? 'MISSING'}
            </span>
          )}
        </div>
      )}
    </div>
  );
}

// ── Helpers ────────────────────────────────────────────────────────────────

function fmtRange(weekStart: string): string {
  try {
    const d = new Date(weekStart + 'T00:00:00');
    const end = new Date(d);
    end.setDate(d.getDate() + 4);
    const fmt = (x: Date) => x.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
    return `${fmt(d)}–${fmt(end)}`;
  } catch { return weekStart; }
}

function fmtInstant(iso: string): string {
  try {
    return new Date(iso).toLocaleString('en-US', {
      month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
    });
  } catch { return iso; }
}
