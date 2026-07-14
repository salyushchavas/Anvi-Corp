'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { ChevronLeft, Filter, Search } from 'lucide-react';
import api from '@/lib/api';
import type { PipelineResponse, FilterOptions } from '@/components/manager/types';

export default function ApplicantPipelinePage() {
  return (
    <Suspense fallback={<div className="p-6 text-sm text-slate-500">Loading…</div>}>
      <PipelineInner />
    </Suspense>
  );
}

function PipelineInner() {
  const sp = useSearchParams();
  const initialStage = sp?.get('stage') ?? 'ALL';

  const [stage, setStage] = useState<string>(initialStage);
  const [jobType, setJobType] = useState<string>('');
  const [ermOwner, setErmOwner] = useState<string>('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);

  const [data, setData] = useState<PipelineResponse | null>(null);
  const [filters, setFilters] = useState<FilterOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  // Filter options are tiny — fetch once on mount.
  useEffect(() => {
    void (async () => {
      try {
        const res = await api.get<FilterOptions>('/api/v1/manager/pipeline/filters');
        setFilters(res.data);
      } catch {
        // non-fatal — page still works without dropdown options
      }
    })();
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const params = new URLSearchParams();
      if (stage && stage !== 'ALL') params.set('stage', stage);
      if (jobType) params.set('technology', jobType); // wire ready; column lands later
      if (ermOwner) params.set('ermOwner', ermOwner);
      if (search.trim()) params.set('search', search.trim());
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<PipelineResponse>(
        `/api/v1/manager/pipeline?${params.toString()}`,
      );
      setData(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load pipeline');
    } finally {
      setLoading(false);
    }
  }, [stage, jobType, ermOwner, search, page]);
  useEffect(() => { void load(); }, [load]);

  return (
    <div className="mx-auto max-w-7xl space-y-4 p-6">
      <p className="text-xs text-slate-500">
        <Link
          href="/careers/manager"
          className="inline-flex items-center gap-1 hover:text-slate-700"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          Manager home
        </Link>
      </p>

      <header className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h1 className="text-xl font-semibold text-slate-900">Applicant Pipeline</h1>
        <p className="text-xs text-slate-500">
          Post-shortlist records across the portfolio — interview state, ERM
          owner, and expected start date. Read-only; ERM owns the decisions.
        </p>
      </header>

      <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
          <label className="md:col-span-2 block">
            <span className="mb-1 inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              <Search className="h-3 w-3" />
              Search name / email / applicant ID
            </span>
            <input
              type="text"
              value={search}
              onChange={(e) => { setSearch(e.target.value); setPage(0); }}
              placeholder="Search…"
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </label>
          <label className="block">
            <span className="mb-1 inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              <Filter className="h-3 w-3" />
              Stage
            </span>
            <select
              value={stage}
              onChange={(e) => { setStage(e.target.value); setPage(0); }}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="ALL">Post-shortlist (default)</option>
              {filters?.stages.map((s) => (
                <option key={s} value={s}>{s.replaceAll('_', ' ')}</option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="mb-1 inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              <Filter className="h-3 w-3" />
              ERM owner
            </span>
            <select
              value={ermOwner}
              onChange={(e) => { setErmOwner(e.target.value); setPage(0); }}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="">All</option>
              {filters?.ermOwners.map((o) => (
                <option key={o.userId} value={o.userId}>{o.fullName}</option>
              ))}
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
              <th className="px-3 py-2 text-left">Applicant</th>
              <th className="px-3 py-2 text-left">Job</th>
              <th className="px-3 py-2 text-left">Stage</th>
              <th className="px-3 py-2 text-left">Interview</th>
              <th className="px-3 py-2 text-left">ERM owner</th>
              <th className="px-3 py-2 text-left">Expected start</th>
              <th className="px-3 py-2 text-right">Age</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading && !data && (
              <tr><td colSpan={7} className="p-6 text-center text-slate-500">Loading…</td></tr>
            )}
            {data && data.items.length === 0 && !loading && (
              <tr><td colSpan={7} className="p-6 text-center text-slate-500">
                No applicants match these filters.
              </td></tr>
            )}
            {data?.items.map((r) => (
              <tr key={r.applicationId} className="hover:bg-slate-50">
                <td className="px-3 py-2">
                  <p className="font-medium text-slate-900">{r.applicantName ?? '—'}</p>
                  <p className="text-[11px] text-slate-500">
                    {r.applicantId ?? ''}{r.applicantEmail ? ` · ${r.applicantEmail}` : ''}
                  </p>
                </td>
                <td className="px-3 py-2">
                  <p className="text-slate-800">{r.jobTitle ?? '—'}</p>
                  <p className="text-[11px] text-slate-500">{r.jobType ?? ''}</p>
                </td>
                <td className="px-3 py-2">
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-700">
                    {r.stage.replaceAll('_', ' ')}
                  </span>
                </td>
                <td className="px-3 py-2 text-[11px] text-slate-700">
                  {r.latestInterviewStatus
                    ? r.latestInterviewStatus.replaceAll('_', ' ')
                    : <span className="text-slate-400">—</span>}
                </td>
                <td className="px-3 py-2 text-[11px] text-slate-700">
                  {r.ermOwnerName ?? <span className="text-amber-700">Unassigned</span>}
                </td>
                <td className="px-3 py-2 text-[11px] text-slate-700">
                  {r.expectedStartDate ?? '—'}
                </td>
                <td className="px-3 py-2 text-right text-[11px] tabular-nums text-slate-700">
                  {r.ageDays}d
                </td>
              </tr>
            ))}
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
