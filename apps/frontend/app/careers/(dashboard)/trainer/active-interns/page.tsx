'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import api from '@/lib/careers/api';
import PeriodPicker, {
  formatPeriod,
  usePeriodFromUrl,
} from '@/components/ui/PeriodPicker';
import MonthlyRosterTable from '@/components/roster/MonthlyRosterTable';
import type { ActiveInternListPage } from '@/components/trainer/types';
import { useTrainerDashboard } from '@/components/trainer/TrainerDashboardContext';

/**
 * Phase A trainer roster — reworked to use the shared
 * {@link MonthlyRosterTable} so Trainer, Manager, and ERM can't drift
 * visually or behaviourally. Scope = single org-wide Trainer → backend
 * returns every intern active during the requested month (no
 * trainer_id filter).
 */

const POLL_MS = 60_000;

export default function ActiveInternsPage() {
  return (
    <Suspense fallback={<div className="p-6 text-sm text-slate-500">Loading…</div>}>
      <ActiveInternsInner />
    </Suspense>
  );
}

function ActiveInternsInner() {
  const { selectedMonth, setSelectedMonth } = useTrainerDashboard();
  const [period, setPeriod] = usePeriodFromUrl();
  const [data, setData] = useState<ActiveInternListPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  // Seed the on-page PeriodPicker from the sticky global month whenever
  // the sticky month changes. The picker still owns its local state so
  // the on-page ← / → chevrons feel responsive, but every change flows
  // BOTH ways: sticky → page (this effect) and page → sticky (below).
  useEffect(() => {
    const [ys, ms] = selectedMonth.split('-');
    const y = Number(ys);
    const m = Number(ms);
    if (!Number.isNaN(y) && !Number.isNaN(m)
        && (period.year !== y || period.month !== m)) {
      setPeriod({ year: y, month: m });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedMonth]);

  useEffect(() => {
    const asString = `${period.year}-${String(period.month).padStart(2, '0')}`;
    if (asString !== selectedMonth) {
      setSelectedMonth(asString);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [period.year, period.month]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      params.set('y', String(period.year));
      params.set('m', String(period.month));
      params.set('page', '0');
      params.set('pageSize', '100');
      const res = await api.get<ActiveInternListPage>(
        `/api/v1/trainer/active-interns?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? e?.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [period.year, period.month]);

  useEffect(() => {
    void load();
    const id = setInterval(() => { void load(); }, POLL_MS);
    return () => clearInterval(id);
  }, [load]);

  return (
    <div className="mx-auto max-w-7xl space-y-4 p-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">Monthly intern roster</h1>
          <p className="text-xs text-slate-500">
            {formatPeriod(period)} · all interns active that month
          </p>
        </div>
        <PeriodPicker value={period} onChange={setPeriod} />
      </header>

      <MonthlyRosterTable
        data={data}
        loading={loading}
        err={err}
        periodLabel={formatPeriod(period)}
        detailHref={(row) => `/careers/trainer/active-interns/${row.internLifecycleId}`}
        cellActions={{
          // Project cell is actionable when a slot is empty (NO_PROJECTS
          // or PARTIAL). Button takes the trainer to the multi-step
          // assign-project wizard pre-filled with the intern + month.
          projectAssignHref: (row) =>
            `/careers/trainer/assign-project?internId=${row.internLifecycleId}`
            + `&month=${period.year}-${String(period.month).padStart(2, '0')}`,
          // KT cell is actionable when any project's KT isn't done.
          // The "Mark KT done" modal lives on the intern detail page;
          // we deep-link with ?kt={projectId} so the detail page auto-
          // opens the matching modal — one click from the table to the
          // open modal instead of "navigate then click again". When the
          // slot's projectId is unknown (rare — null on unpersisted
          // slots) we fall back to the plain detail page link.
          ktDetailHref: (row, options) => {
            const base = `/careers/trainer/active-interns/${row.internLifecycleId}`;
            return options?.projectId
              ? `${base}?kt=${encodeURIComponent(options.projectId)}`
              : base;
          },
        }}
      />
    </div>
  );
}
