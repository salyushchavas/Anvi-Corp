'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import api from '@/lib/careers/api';

/**
 * B3 remainder — Manager's read-only Terminated-Interns roster.
 *
 * <p>Parallel to the "Inactive Interns" page (which shows the union of
 * COMPLETED + RESIGNED + TERMINATED) but narrowed to just the
 * TERMINATED cohort — management-initiated separations, distinct from
 * voluntary resignation or normal program completion. Rows come from
 * the same {@code /api/v1/manager/inactive-interns} endpoint via the
 * {@code terminatedOnly=true} filter, so the two views share DTOs and
 * the same closure-snapshot enrichment (final timesheet, evaluation
 * link, revocation status).</p>
 *
 * <p>SUPER_ADMIN sees the same set — the role gate is on the endpoint
 * (MANAGER + SUPER_ADMIN); no per-role scoping.</p>
 */

interface TerminatedRow {
  internLifecycleId: string;
  internUserId: string;
  fullName: string | null;
  email: string | null;
  employeeId: string | null;
  activeStatus: string | null;
  endedAt: string | null;
  exitType: string | null;
  exitDate: string | null;
  lastWorkingDay: string | null;
  exitReason: string | null;
  reasonCode: string | null;
  rehireEligible: boolean | null;
  managerName: string | null;
  ermName: string | null;
}

interface TerminatedListResponse {
  items: TerminatedRow[];
  totalElements: number;
  monthYear: string | null;
}

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString();
}

export default function ManagerTerminatedInternsPage() {
  const [data, setData] = useState<TerminatedListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      // All-time by default — terminations are infrequent enough that a
      // month filter would usually hide meaningful history. Matches the
      // Inactive-Interns default.
      const res = await api.get<TerminatedListResponse>(
        '/api/v1/manager/inactive-interns?terminatedOnly=true',
      );
      setData(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const items = data?.items ?? [];
  const total = data?.totalElements ?? 0;

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <header>
        <h1 className="flex items-center gap-2 text-xl font-semibold text-slate-900">
          <AlertTriangle className="h-5 w-5 text-red-600" />
          Terminated interns
        </h1>
        <p className="mt-1 text-xs text-slate-500">
          Management-initiated separations. Distinct from voluntary
          resignation or normal program completion — see{' '}
          <a
            href="/careers/manager/inactive-interns"
            className="text-brand-700 underline hover:text-brand-800"
          >
            Inactive interns
          </a>{' '}
          for the full inactive cohort.
        </p>
      </header>

      {err && (
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </div>
      )}

      {loading && !data ? (
        <div className="h-40 animate-pulse rounded-lg bg-slate-100" />
      ) : items.length === 0 ? (
        <div className="rounded-lg border border-dashed border-slate-300 bg-white p-10 text-center text-sm text-slate-600">
          No terminated interns on record.
        </div>
      ) : (
        <>
          <p className="text-xs text-slate-500">
            {total} terminated intern{total === 1 ? '' : 's'}
          </p>
          <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
            <table className="min-w-full text-sm">
              <thead className="border-b border-slate-200 bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
                <tr>
                  <th className="px-4 py-3 font-medium">Intern</th>
                  <th className="px-4 py-3 font-medium">Employee ID</th>
                  <th className="px-4 py-3 font-medium">Terminated on</th>
                  <th className="px-4 py-3 font-medium">Last working day</th>
                  <th className="px-4 py-3 font-medium">Reason</th>
                  <th className="px-4 py-3 font-medium">Rehire eligible</th>
                  <th className="px-4 py-3 font-medium">Manager</th>
                  <th className="px-4 py-3 font-medium">ERM</th>
                </tr>
              </thead>
              <tbody>
                {items.map((r) => (
                  <tr key={r.internLifecycleId} className="border-b border-slate-100 last:border-0">
                    <td className="px-4 py-3">
                      <div className="font-medium text-slate-900">
                        {r.fullName ?? '(unnamed)'}
                      </div>
                      <div className="text-xs text-slate-500">{r.email ?? '—'}</div>
                    </td>
                    <td className="px-4 py-3 font-mono text-xs text-slate-700">
                      {r.employeeId ?? '—'}
                    </td>
                    <td className="px-4 py-3 text-slate-700">
                      {formatDate(r.exitDate ?? r.endedAt)}
                    </td>
                    <td className="px-4 py-3 text-slate-700">
                      {formatDate(r.lastWorkingDay)}
                    </td>
                    <td className="px-4 py-3">
                      {r.reasonCode && (
                        <div className="text-xs font-medium text-red-700">
                          {r.reasonCode.replace(/_/g, ' ')}
                        </div>
                      )}
                      {r.exitReason && (
                        <div className="mt-0.5 line-clamp-2 text-xs text-slate-600">
                          {r.exitReason}
                        </div>
                      )}
                      {!r.reasonCode && !r.exitReason && (
                        <span className="text-xs text-slate-400">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      {r.rehireEligible === true ? (
                        <span className="inline-flex rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-800">
                          Eligible
                        </span>
                      ) : r.rehireEligible === false ? (
                        <span className="inline-flex rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-800">
                          Not eligible
                        </span>
                      ) : (
                        <span className="text-xs text-slate-400">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-slate-700">{r.managerName ?? '—'}</td>
                    <td className="px-4 py-3 text-slate-700">{r.ermName ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
