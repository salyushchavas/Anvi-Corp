'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { ArrowRight, Search } from 'lucide-react';
import api from '@/lib/careers/api';
import { useEvaluatorDashboard } from '@/components/evaluator/EvaluatorDashboardContext';
import type {
  ActiveEvalueeProjectRow,
  ActiveEvalueeRow,
  ActiveEvalueesPage,
} from '@/components/evaluator/types';
import {
  evaluatorProjectDisplay,
  type EvaluatorProjectDisplayState,
} from '@/components/evaluator/project-status';

/**
 * Evaluator ⟶ Active Evaluees. One compact row per intern with a status
 * chip per project slot (P1 + P2, DB-capped at 2) and a single Open
 * button routing to the evaluee detail page. All scheduling / start /
 * compose actions live on the detail page — the list is scan-and-jump
 * only, no per-project verbs.
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

  const { selectedMonth, isCurrentMonth } = useEvaluatorDashboard();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (search.trim()) params.set('search', search.trim());
      if (needsAttention) params.set('needsAttention', 'true');
      if (!isCurrentMonth) params.set('month', selectedMonth);
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
  }, [search, needsAttention, page, selectedMonth, isCurrentMonth]);

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
          Interns assigned to you.
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

      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
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
                <th className="px-3 py-2">Project 1</th>
                <th className="px-3 py-2">Project 2</th>
                <th className="px-3 py-2 text-right"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((r) => (
                <Row
                  key={r.lifecycleId}
                  row={r}
                  onOpen={() => router.push(`/careers/evaluator/evaluees/${r.lifecycleId}`)}
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

function Row({ row, onOpen }: {
  row: ActiveEvalueeRow;
  onOpen: () => void;
}) {
  const projectBySeq = (seq: number): ActiveEvalueeProjectRow | null =>
    row.projects?.find((p) => p.sequence === seq) ?? null;
  const p1 = projectBySeq(1);
  const p2 = projectBySeq(2);
  // DB caps slots at 2; the safety valve surfaces any future 3rd+ slot
  // as a compact "+N more" tag in the P2 cell instead of silently
  // dropping it or breaking the fixed column count.
  const overflow = Math.max(0, (row.projects?.length ?? 0) - 2);
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
      <ProjectCell project={p1} />
      <ProjectCell project={p2} overflow={overflow} />
      <td className="whitespace-nowrap px-3 py-2 text-right">
        <button
          type="button"
          onClick={(e) => { e.stopPropagation(); onOpen(); }}
          className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50"
        >
          Open
          <ArrowRight className="h-3 w-3" />
        </button>
      </td>
    </tr>
  );
}

function ProjectCell({ project, overflow = 0 }: {
  project: ActiveEvalueeProjectRow | null;
  overflow?: number;
}) {
  const { label, pill } = friendlyProjectLabel(project);
  return (
    <td className="whitespace-nowrap px-3 py-2">
      <span className={`inline-flex whitespace-nowrap rounded-full px-2 py-0.5 text-[10px] font-semibold ${pill}`}>
        {label}
      </span>
      {overflow > 0 && (
        <span className="ml-1.5 rounded-full bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-500">
          +{overflow} more
        </span>
      )}
    </td>
  );
}

/**
 * Friendly single-glance label per project slot. Uses the shared
 * evaluator display state so wording tracks the card and detail-page
 * vocabulary: PENDING_EVAL surfaces as "Awaiting viva" (that state
 * covers PENDING_VIVA / COMPLETED projects with no eval yet), SCHEDULED
 * / SESSION_COMPLETED / COMPLETED / CANCELLED map to their friendly
 * short forms. Empty slot renders a muted "No project yet" chip so the
 * row column count stays fixed across evaluees.
 */
function friendlyProjectLabel(project: ActiveEvalueeProjectRow | null): {
  label: string;
  pill: string;
} {
  if (!project) {
    return {
      label: 'No project yet',
      pill: 'bg-slate-50 text-slate-400 italic',
    };
  }
  const disp = evaluatorProjectDisplay(project.projectStatus, project.evaluationStatus);
  return {
    label: FRIENDLY_LABEL[disp.state],
    pill: disp.pill,
  };
}

const FRIENDLY_LABEL: Record<EvaluatorProjectDisplayState, string> = {
  IN_PROGRESS:       'In progress',
  PENDING_EVAL:      'Awaiting viva',
  SCHEDULED:         'Scheduled',
  SESSION_COMPLETED: 'Session done',
  COMPLETED:         'Completed',
  CANCELLED:         'Cancelled',
};

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
