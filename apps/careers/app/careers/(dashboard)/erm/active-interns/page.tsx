'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import { UserPlus } from 'lucide-react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PeriodPicker, {
  formatPeriod,
  usePeriodFromUrl,
} from '@/components/ui/PeriodPicker';
import MonthlyRosterTable from '@/components/roster/MonthlyRosterTable';
import type {
  ActiveInternListPage,
  ActiveInternRow,
} from '@/components/trainer/types';

/**
 * Phase C — ERM active-interns surface, reworked into the shared
 * monthly intern roster. Scope = all interns (any intern active in
 * the requested month). Adds two ERM-only affordances:
 * <ol>
 *   <li>"No manager" badge + filter chip + summary tile so the ERM
 *       can spot interns whose verified timesheets would otherwise
 *       stall with no manager to approve them;</li>
 *   <li>Per-row "Assign manager" button on rows with no manager,
 *       opening a small modal that POSTs the existing
 *       {@code /api/v1/intern-lifecycles/{id}/assign-manager}.</li>
 * </ol>
 *
 * <p>Replaces the older 6-card monitor view; compliance + escalations
 * live on their own dedicated ERM pages.</p>
 */

const POLL_MS = 60_000;

interface ManagerOption {
  userId: string;
  fullName: string;
  email: string;
}

export default function ErmActiveInternsPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <Suspense fallback={<div className="p-6 text-sm text-slate-500">Loading…</div>}>
          <ErmActiveInternsInner />
        </Suspense>
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function ErmActiveInternsInner() {
  const [period, setPeriod] = usePeriodFromUrl();
  const [data, setData] = useState<ActiveInternListPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [managers, setManagers] = useState<ManagerOption[]>([]);
  const [assignFor, setAssignFor] = useState<ActiveInternRow | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      params.set('y', String(period.year));
      params.set('m', String(period.month));
      params.set('page', '0');
      params.set('pageSize', '100');
      const res = await api.get<ActiveInternListPage>(
        `/api/v1/erm/active-interns/roster?${params.toString()}`,
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

  async function ensureManagers() {
    if (managers.length > 0) return;
    try {
      const res = await api.get<ManagerOption[]>(
        '/api/v1/intern-lifecycles/eligible-managers',
      );
      setManagers(res.data ?? []);
    } catch {
      setManagers([]);
    }
  }

  return (
    <div className="mx-auto max-w-7xl space-y-4 p-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">Active interns</h1>
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
        detailHref={(id) => `/careers/erm/active-interns/${id}`}
        showNoManagerControls
        onChanged={load}
        renderRowExtra={(row) => {
          const noMgr = row.reportingStructure?.managerId == null;
          if (!noMgr) return null;
          return (
            <button
              type="button"
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                void ensureManagers();
                setAssignFor(row);
              }}
              className="inline-flex items-center gap-1 rounded-md border border-red-300 px-2 py-1 text-[11px] font-semibold text-red-700 hover:bg-red-50"
            >
              <UserPlus className="h-3 w-3" /> Assign manager
            </button>
          );
        }}
      />

      {assignFor && (
        <AssignManagerModal
          row={assignFor}
          managers={managers}
          onClose={() => setAssignFor(null)}
          onAssigned={() => { setAssignFor(null); void load(); }}
        />
      )}
    </div>
  );
}

function AssignManagerModal({
  row, managers, onClose, onAssigned,
}: {
  row: ActiveInternRow;
  managers: ManagerOption[];
  onClose: () => void;
  onAssigned: () => void;
}) {
  const [selected, setSelected] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    if (!selected) {
      setErr('Pick a manager.');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      await api.post(
        `/api/v1/intern-lifecycles/${row.internLifecycleId}/assign-manager`,
        { managerUserId: selected },
      );
      onAssigned();
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Assignment failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="w-full max-w-md rounded-lg bg-white p-5 shadow-xl">
        <h2 className="text-lg font-semibold text-slate-900">Assign manager</h2>
        <p className="mt-1 text-xs text-slate-500">
          {row.fullName ?? '(unknown)'} has no manager assigned — pick one to
          unblock their timesheet approval chain.
        </p>
        <div className="mt-4">
          <label className="text-xs font-medium text-slate-800">Manager</label>
          <select
            value={selected}
            onChange={(e) => setSelected(e.target.value)}
            className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
          >
            <option value="">Select a manager…</option>
            {managers.map((m) => (
              <option key={m.userId} value={m.userId}>
                {m.fullName || m.email}
              </option>
            ))}
          </select>
          {managers.length === 0 && (
            <p className="mt-1 text-xs text-amber-700">
              No active managers found.
            </p>
          )}
        </div>
        {err && (
          <p className="mt-3 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
            {err}
          </p>
        )}
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={busy || !selected}
            className="rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-50"
          >
            {busy ? 'Assigning…' : 'Assign'}
          </button>
        </div>
      </div>
    </div>
  );
}
