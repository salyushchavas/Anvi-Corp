'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
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

interface RollupResponse {
  monthYear: string;
  columns: RollupColumn[];
  summary: RollupSummary;
  interns: RollupRow[];
}

export default function ErmTimesheetsPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <Suspense fallback={<div className="p-6 text-sm text-slate-500">Loading…</div>}>
          <ErmTimesheetsInner />
        </Suspense>
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function ErmTimesheetsInner() {
  const [period, setPeriod] = usePeriodFromUrl();
  const [data, setData] = useState<RollupResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<RollupResponse>(
        `/api/v1/timesheets/rollup?y=${period.year}&m=${period.month}&scope=erm`,
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

  async function verifyCell(cell: RollupCell) {
    await api.post(`/api/v1/erm/timesheets/${cell.timesheetId}/verify`);
    await load();
  }
  async function rejectCell(cell: RollupCell, _row: RollupRow, reason: string) {
    await api.post(
      `/api/v1/erm/timesheets/${cell.timesheetId}/reject`,
      { reason },
    );
    await load();
  }
  async function rejectCellAdapter(cell: RollupCell, row: RollupRow, reason: string) {
    return rejectCell(cell, row, reason);
  }
  async function verifyAll(ids: string[]): Promise<{ ok: number; skipped: number }> {
    if (ids.length === 0) return { ok: 0, skipped: 0 };
    const res = await api.post<Record<string, string>>(
      '/api/v1/erm/timesheets/verify-batch',
      { ids },
    );
    const entries = Object.values(res.data ?? {});
    const ok = entries.filter((v) => v === 'VERIFIED').length;
    await load();
    return { ok, skipped: entries.length - ok };
  }

  return (
    <div className="mx-auto max-w-7xl space-y-4 p-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">Verify timesheets</h1>
          <p className="text-xs text-slate-500">
            {formatPeriod(period)} · all active interns — verify each Submitted week to release it for Manager approval.
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
          actionFor="SUBMITTED"
          actionLabel="Verify"
          batchActionLabel="Verify all submitted"
          onAction={(cell) => verifyCell(cell)}
          onReject={rejectCellAdapter}
          onBatchAction={verifyAll}
          emptyMessage={`No interns active in ${formatPeriod(period)}.`}
        />
      )}
    </div>
  );
}
