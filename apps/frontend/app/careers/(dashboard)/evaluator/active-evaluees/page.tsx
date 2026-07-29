'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { ChevronRight, PenSquare, Search } from 'lucide-react';
import api from '@/lib/careers/api';
import type {
  ActiveEvalueeProjectRow,
  ActiveEvalueeRow,
  ActiveEvalueesPage,
} from '@/components/evaluator/types';

/**
 * Evaluator ⟶ Active Evaluees. One row per intern with per-project
 * status + evaluation columns (P1 / P2 — capped at 2 by DB CHECK).
 *
 * Quick-action "Evaluate P{n}" button appears only when the project is
 * ready (SUBMITTED or later) AND the evaluation isn't already finalized.
 * Otherwise renders "View" (for finalized evaluations) or an em-dash.
 */
export default function ActiveEvalueesPage() {
  return (
    <Suspense fallback={<div className="mx-auto max-w-6xl p-6"><div className="h-48 animate-pulse rounded-lg bg-slate-100" /></div>}>
      <ActiveEvalueesInner />
    </Suspense>
  );
}

function ActiveEvalueesInner() {
  const router = useRouter();
  const sp = useSearchParams();
  const prefillFilter = sp?.get('filter') ?? '';

  const [search, setSearch] = useState('');
  const [needsAttention, setNeedsAttention] = useState(prefillFilter === 'overdue');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<ActiveEvalueesPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (search.trim()) params.set('search', search.trim());
      if (needsAttention) params.set('needsAttention', 'true');
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<ActiveEvalueesPage>(
        `/api/v1/evaluator/active-evaluees?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load evaluees');
    } finally {
      setLoading(false);
    }
  }, [search, needsAttention, page]);

  useEffect(() => { void load(); }, [load]);

  function clearFilters() {
    setSearch('');
    setNeedsAttention(false);
    setPage(0);
  }

  const rows = data?.items ?? [];

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <div>
        <p className="text-xs text-slate-500">
          <Link href="/careers/evaluator" className="hover:text-slate-700">← Evaluator home</Link>
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">Active Evaluees</h1>
        <p className="text-xs text-slate-500">
          Interns assigned to you (auto-linked via DEFAULT_EVALUATOR_EMAIL at offer sign).
        </p>
      </div>

      <div className="space-y-2 rounded-lg border border-slate-200 bg-white p-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative">
            <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" />
            <input
              value={search}
              onChange={(e) => { setPage(0); setSearch(e.target.value); }}
              placeholder="Search name or employee ID"
              className="w-72 rounded-md border border-slate-200 pl-8 pr-3 py-1.5 text-sm"
            />
          </div>
          <label className="inline-flex cursor-pointer items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50">
            <input
              type="checkbox"
              checked={needsAttention}
              onChange={(e) => { setPage(0); setNeedsAttention(e.target.checked); }}
              className="h-3.5 w-3.5"
            />
            Needs attention
          </label>
          <button
            type="button"
            onClick={clearFilters}
            className="text-xs font-medium text-brand-700 hover:underline"
          >
            Clear filters
          </button>
          <span className="ml-auto text-xs text-slate-500">
            {data?.totalElements ?? 0} evaluees
          </span>
        </div>
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-48 animate-pulse" />
        ) : rows.length === 0 ? (
          <EmptyState />
        ) : (
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-3 py-2">Intern</th>
                <th className="px-3 py-2">Technology</th>
                <th className="px-3 py-2">Project 1 status</th>
                <th className="px-3 py-2">Project 2 status</th>
                <th className="px-3 py-2">Project 1 evaluation</th>
                <th className="px-3 py-2">Project 2 evaluation</th>
                <th className="px-3 py-2"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((r) => (
                <Row
                  key={r.lifecycleId}
                  row={r}
                  onOpen={() => router.push(`/careers/evaluator/evaluees/${r.lifecycleId}`)}
                  onEvaluate={(evId, finalized) =>
                    router.push(finalized
                      ? `/careers/evaluator/evaluations/${evId}`
                      : `/careers/evaluator/evaluations/${evId}/compose`)}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between text-xs text-slate-600">
          <span>Page {data.page + 1} of {data.totalPages}</span>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={data.page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              className="rounded-md border border-slate-200 px-3 py-1 disabled:opacity-50"
            >
              Prev
            </button>
            <button
              type="button"
              disabled={data.page + 1 >= data.totalPages}
              onClick={() => setPage((p) => p + 1)}
              className="rounded-md border border-slate-200 px-3 py-1 disabled:opacity-50"
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

const FINALIZED_EVAL_STATUSES = new Set(['PUBLISHED', 'ACKNOWLEDGED', 'AMENDED']);
const READY_PROJECT_STATUSES = new Set(['SUBMITTED', 'COMPLETED']);

function Row({ row, onOpen, onEvaluate }: {
  row: ActiveEvalueeRow;
  onOpen: () => void;
  onEvaluate: (evaluationId: string, finalized: boolean) => void;
}) {
  const projectBySeq = (seq: number): ActiveEvalueeProjectRow | null =>
    row.projects?.find((p) => p.sequence === seq) ?? null;
  const p1 = projectBySeq(1);
  const p2 = projectBySeq(2);
  return (
    <tr className="cursor-pointer hover:bg-slate-50" onClick={onOpen}>
      <td className="px-3 py-2">
        <p className="text-sm font-medium text-slate-900">{row.internName ?? '—'}</p>
        <p className="text-[11px] text-slate-500">
          {row.employeeId ?? '—'}
          {row.trainerName && <span className="ml-2">Trainer: {row.trainerName}</span>}
        </p>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">{row.technology ?? '—'}</td>
      <ProjectStatusCell project={p1} />
      <ProjectStatusCell project={p2} />
      <ProjectEvaluationCell project={p1} label="P1" onEvaluate={onEvaluate} />
      <ProjectEvaluationCell project={p2} label="P2" onEvaluate={onEvaluate} />
      <td className="px-3 py-2 text-right">
        <ChevronRight className="h-4 w-4 text-slate-400" />
      </td>
    </tr>
  );
}

function ProjectStatusCell({ project }: { project: ActiveEvalueeProjectRow | null }) {
  if (!project) {
    return <td className="px-3 py-2 text-xs text-slate-400">—</td>;
  }
  return (
    <td className="px-3 py-2">
      <span className="inline-flex rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-700">
        {(project.projectStatus ?? '—').replaceAll('_', ' ')}
      </span>
    </td>
  );
}

function ProjectEvaluationCell({ project, label, onEvaluate }: {
  project: ActiveEvalueeProjectRow | null;
  label: 'P1' | 'P2';
  onEvaluate: (evaluationId: string, finalized: boolean) => void;
}) {
  if (!project) {
    return <td className="px-3 py-2 text-xs text-slate-400">—</td>;
  }
  const evalStatus = project.evaluationStatus;
  const projectReady = READY_PROJECT_STATUSES.has(project.projectStatus ?? '');
  const finalized = evalStatus != null && FINALIZED_EVAL_STATUSES.has(evalStatus);
  const showEvaluate = project.evaluationId != null && projectReady && !finalized;
  const showView = project.evaluationId != null && finalized;
  const statusLabel = evalStatus
    ? evalStatus === 'DRAFT' ? 'Not scheduled'
      : evalStatus === 'SCHEDULED' ? 'Scheduled'
      : evalStatus === 'IN_PROGRESS' ? 'Session completed'
      : finalized ? 'Evaluated'
      : evalStatus.replaceAll('_', ' ')
    : '—';
  return (
    <td className="px-3 py-2">
      <p className="text-[11px] text-slate-600">{statusLabel}</p>
      {showEvaluate && (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onEvaluate(project.evaluationId!, false);
          }}
          className="mt-1 inline-flex items-center gap-1 rounded-md bg-brand-700 px-2 py-0.5 text-[10px] font-semibold text-white hover:bg-brand-800"
        >
          <PenSquare className="h-2.5 w-2.5" />
          Evaluate {label}
        </button>
      )}
      {showView && !showEvaluate && (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onEvaluate(project.evaluationId!, true);
          }}
          className="mt-1 inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-0.5 text-[10px] font-medium text-slate-700 hover:bg-slate-50"
        >
          View
        </button>
      )}
    </td>
  );
}

function EmptyState() {
  return (
    <div className="flex flex-col items-center gap-3 p-12 text-center">
      <p className="text-sm font-medium text-slate-800">No active evaluees yet.</p>
      <p className="max-w-sm text-xs text-slate-500">
        Interns are auto-linked to you at offer sign when{' '}
        <code className="rounded bg-slate-100 px-1 py-0.5 text-[10px]">DEFAULT_EVALUATOR_EMAIL</code>{' '}
        resolves to your account. If you expect to see interns here, confirm the
        env var is set on Railway and matches your email exactly.
      </p>
    </div>
  );
}
