'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import api from '@/lib/api';
import PeriodPicker, {
  formatPeriod,
  usePeriodFromUrl,
} from '@/components/ui/PeriodPicker';
import WeeklyRollupTable, {
  type RollupCell,
  type RollupColumn,
  type RollupRow,
  type RollupSummary,
} from '@/components/timesheet/WeeklyRollupTable';

/**
 * Phase B2 — Manager timesheet approval. Reworked to use the shared
 * WeeklyRollupTable: rows = interns the manager owns, columns = the
 * Mon-Fri work-weeks of the selected month, action = Approve (requires
 * the row to be VERIFIED). Wraps in Suspense so usePeriodFromUrl's
 * useSearchParams doesn't bail at static prerender.
 */

interface RollupResponse {
  monthYear: string;
  columns: RollupColumn[];
  summary: RollupSummary;
  interns: RollupRow[];
}

export default function TimesheetApprovalsPage() {
  return (
    <Suspense fallback={<div className="p-6 text-sm text-slate-500">Loading…</div>}>
      <TimesheetApprovalsInner />
    </Suspense>
  );
}

function TimesheetApprovalsInner() {
  const [period, setPeriod] = usePeriodFromUrl();
  const [data, setData] = useState<RollupResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<RollupResponse>(
        `/api/v1/timesheets/rollup?y=${period.year}&m=${period.month}&scope=manager`,
      );
      setData(res.data);
      setErr(null);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? e?.message ?? 'Could not load rollup');
    } finally {
      setLoading(false);
    }
  }, [period.year, period.month]);

  useEffect(() => { void load(); }, [load]);

  async function approveCell(cell: RollupCell) {
    await api.post(`/api/v1/manager/timesheets/${cell.timesheetId}/approve`);
    await load();
  }
  async function rejectCell(cell: RollupCell, _row: RollupRow, reason: string) {
    await api.post(
      `/api/v1/manager/timesheets/${cell.timesheetId}/reject`,
      { reason },
    );
    await load();
  }
  async function approveAll(ids: string[]): Promise<{ ok: number; skipped: number }> {
    if (ids.length === 0) return { ok: 0, skipped: 0 };
    const res = await api.post<Record<string, string>>(
      '/api/v1/manager/timesheets/approve-batch',
      { ids },
    );
    const entries = Object.values(res.data ?? {});
    const ok = entries.filter((v) => v === 'APPROVED').length;
    await load();
    return { ok, skipped: entries.length - ok };
  }

  return (
    <div className="mx-auto max-w-7xl space-y-4 p-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">Timesheet approvals</h1>
          <p className="text-xs text-slate-500">
            {formatPeriod(period)} · your direct reports. Approve each Verified week to finalize it.
          </p>
        </div>
        <PeriodPicker value={period} onChange={setPeriod} />
      </header>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>
      )}

      {loading && !data ? (
        <div className="h-48 animate-pulse rounded-lg bg-slate-100" aria-hidden />
      ) : !data ? null : (
        <WeeklyRollupTable
          columns={data.columns}
          rows={data.interns}
          summary={data.summary}
          actionFor="VERIFIED"
          actionLabel="Approve"
          batchActionLabel="Approve all verified"
          onAction={(cell) => approveCell(cell)}
          onReject={rejectCell}
          onBatchAction={approveAll}
          emptyMessage={`No interns assigned to you in ${formatPeriod(period)}.`}
        />
      )}
    </div>
  );
}
