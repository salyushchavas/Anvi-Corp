'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { ChevronLeft, Filter, Search } from 'lucide-react';
import api from '@/lib/api';

interface HistoryRow {
  evaluationId: string;
  entryKind: 'MONTHLY' | 'FINAL' | 'I983';
  internLifecycleId: string;
  internName: string | null;
  employeeId: string | null;
  evaluationType: string;
  status: string;
  version: number;
  periodStart: string | null;
  periodEnd: string | null;
  scheduledFor: string | null;
  publishedAt: string | null;
  acknowledgedAt: string | null;
  overallScore: number | null;
  recommendation: string | null;
}

interface HistoryPage {
  items: HistoryRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export default function EvaluationHistoryPage() {
  return (
    <Suspense fallback={<div className="p-6 text-sm text-slate-500">Loading…</div>}>
      <EvaluationHistoryInner />
    </Suspense>
  );
}

function EvaluationHistoryInner() {
  const sp = useSearchParams();
  const internFilter = sp?.get('intern') ?? '';
  const [search, setSearch] = useState('');
  const [type, setType] = useState<string>('ALL');
  const [status, setStatus] = useState<string>('ALL');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<HistoryPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const params = new URLSearchParams();
      if (search.trim()) params.set('search', search.trim());
      if (type !== 'ALL') params.set('type', type);
      if (status !== 'ALL') params.set('status', status);
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<HistoryPage>(
        `/api/v1/evaluator/history?${params.toString()}`,
      );
      // Optional client-side filter when ?intern= is given.
      const filtered = internFilter
        ? {
            ...res.data,
            items: res.data.items.filter((r) => r.internLifecycleId === internFilter),
          }
        : res.data;
      setData(filtered);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [search, type, status, page, internFilter]);

  useEffect(() => { void load(); }, [load]);

  return (
    <div className="mx-auto max-w-7xl space-y-4 p-6">
      <p className="text-xs text-slate-500">
        <Link
          href="/careers/evaluator"
          className="inline-flex items-center gap-1 hover:text-slate-700"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          Evaluator home
        </Link>
      </p>

      <header className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h1 className="text-xl font-semibold text-slate-900">Evaluation History</h1>
        <p className="text-xs text-slate-500">
          Every evaluation you&apos;ve published or amended — monthly, I-983,
          and final — across all evaluees.
        </p>
      </header>

      <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
          <label className="md:col-span-2 block">
            <span className="mb-1 inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              <Search className="h-3 w-3" />
              Search intern or employee ID
            </span>
            <input
              type="text"
              value={search}
              onChange={(e) => { setSearch(e.target.value); setPage(0); }}
              placeholder="Name or SKZ-EMP-…"
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </label>
          <label className="block">
            <span className="mb-1 inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              <Filter className="h-3 w-3" />
              Type
            </span>
            <select
              value={type}
              onChange={(e) => { setType(e.target.value); setPage(0); }}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="ALL">All</option>
              <option value="MONTHLY">Monthly</option>
              <option value="FINAL">Final</option>
              <option value="I983">I-983</option>
            </select>
          </label>
          <label className="block">
            <span className="mb-1 inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              <Filter className="h-3 w-3" />
              Status
            </span>
            <select
              value={status}
              onChange={(e) => { setStatus(e.target.value); setPage(0); }}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="ALL">All</option>
              <option value="PUBLISHED">Published</option>
              <option value="ACKNOWLEDGED">Acknowledged</option>
              <option value="AMENDED">Amended</option>
              <option value="SCHEDULED">Scheduled</option>
              <option value="IN_PROGRESS">In progress</option>
            </select>
          </label>
        </div>
      </section>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <section className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full text-sm">
          <thead className="bg-slate-50 text-[11px] uppercase tracking-wide text-slate-600">
            <tr>
              <th className="px-3 py-2 text-left">Intern</th>
              <th className="px-3 py-2 text-left">Type</th>
              <th className="px-3 py-2 text-left">Status</th>
              <th className="px-3 py-2 text-left">Period</th>
              <th className="px-3 py-2 text-left">Published</th>
              <th className="px-3 py-2 text-left">Acknowledged</th>
              <th className="px-3 py-2 text-left">Score</th>
              <th className="px-3 py-2 text-left">Recommendation</th>
              <th className="px-3 py-2"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading && !data && (
              <tr><td colSpan={9} className="p-6 text-center text-slate-500">Loading…</td></tr>
            )}
            {data && data.items.length === 0 && (
              <tr><td colSpan={9} className="p-6 text-center text-slate-500">
                No evaluations match these filters.
              </td></tr>
            )}
            {data?.items.map((r) => {
              const typeStyle = r.entryKind === 'I983'
                ? 'bg-amber-100 text-amber-800'
                : r.entryKind === 'FINAL'
                  ? 'bg-amber-100 text-amber-800'
                  : 'bg-slate-100 text-slate-700';
              const href = r.entryKind === 'I983'
                ? `/careers/evaluator/i983-evaluations/${r.evaluationId}`
                : `/careers/evaluator/evaluations/${r.evaluationId}`;
              return (
                <tr key={r.evaluationId + r.entryKind} className="hover:bg-slate-50">
                  <td className="px-3 py-2">
                    <p className="font-medium text-slate-900">{r.internName ?? '—'}</p>
                    <p className="text-[11px] text-slate-500">{r.employeeId ?? ''}</p>
                  </td>
                  <td className="px-3 py-2">
                    <span className={'rounded-full px-2 py-0.5 text-[10px] font-semibold ' + typeStyle}>
                      {r.evaluationType.replaceAll('_', ' ')}
                    </span>
                    {r.version > 1 && (
                      <span className="ml-1 rounded-full bg-slate-50 px-1.5 py-0.5 text-[10px] font-semibold text-slate-700">
                        v{r.version}
                      </span>
                    )}
                  </td>
                  <td className="px-3 py-2">
                    <span className="rounded-full bg-green-50 px-2 py-0.5 text-[10px] font-semibold text-green-700">
                      {r.status}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-[11px] text-slate-700">
                    {r.periodStart ?? '—'} → {r.periodEnd ?? '—'}
                  </td>
                  <td className="px-3 py-2 text-[11px] text-slate-700">
                    {r.publishedAt ? new Date(r.publishedAt).toLocaleDateString() : '—'}
                  </td>
                  <td className="px-3 py-2 text-[11px] text-slate-700">
                    {r.acknowledgedAt
                      ? new Date(r.acknowledgedAt).toLocaleDateString()
                      : <span className="text-amber-700">pending</span>}
                  </td>
                  <td className="px-3 py-2 text-[11px] tabular-nums text-slate-900">
                    {r.overallScore != null ? `${r.overallScore} / 5` : '—'}
                  </td>
                  <td className="px-3 py-2 text-[11px] text-slate-700">
                    {r.recommendation
                      ? r.recommendation.replaceAll('_', ' ')
                      : '—'}
                  </td>
                  <td className="px-3 py-2 text-right">
                    <Link
                      href={href}
                      className="text-[11px] font-medium text-brand-700 hover:underline"
                    >
                      Open →
                    </Link>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </section>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between rounded-md border border-slate-200 bg-white px-3 py-2 text-xs text-slate-600">
          <p>
            Page {data.page + 1} of {data.totalPages} · {data.totalElements} total
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              className="rounded-md border border-slate-200 bg-white px-3 py-1 hover:bg-slate-50 disabled:opacity-50"
            >
              Prev
            </button>
            <button
              type="button"
              disabled={page + 1 >= data.totalPages}
              onClick={() => setPage((p) => p + 1)}
              className="rounded-md border border-slate-200 bg-white px-3 py-1 hover:bg-slate-50 disabled:opacity-50"
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
