'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import {
  CalendarPlus,
  ChevronRight,
  Eye,
  PenSquare,
  PlayCircle,
  Search,
} from 'lucide-react';
import api from '@/lib/careers/api';
import SchedulePostProjectDialog from '@/components/evaluator/SchedulePostProjectDialog';
import type {
  ActiveEvalueeProjectRow,
  ActiveEvalueeRow,
  ActiveEvalueesPage,
} from '@/components/evaluator/types';
import {
  evaluatorProjectDisplay,
  type EvaluatorProjectActionKind,
} from '@/components/evaluator/project-status';

/**
 * Evaluator ⟶ Active Evaluees. One row per intern with two per-project
 * columns (P1 / P2 — capped at 2 by DB CHECK). Each cell shows the
 * derived evaluator label + a single quick-action button that lines up
 * with the label per {@link evaluatorProjectDisplay}. The vocabulary
 * matches ProjectCardsSection so the list and the detail hub speak
 * identically for the same underlying state.
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
  /** Which (projectId, projectTitle) the schedule dialog is bound to. */
  const [scheduleFor, setScheduleFor] = useState<
    { projectId: string; projectTitle: string | null } | null
  >(null);

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

  async function handleStartSession(evaluationId: string) {
    try {
      await api.post(`/api/v1/evaluator/evaluations/${evaluationId}/start`);
      router.push(`/careers/evaluator/evaluations/${evaluationId}/compose`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to start session');
    }
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
                <th className="px-3 py-2">Project 1</th>
                <th className="px-3 py-2">Project 2</th>
                <th className="px-3 py-2"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((r) => (
                <Row
                  key={r.lifecycleId}
                  row={r}
                  onOpen={() => router.push(`/careers/evaluator/evaluees/${r.lifecycleId}`)}
                  onSchedule={(projectId) => setScheduleFor({
                    projectId,
                    projectTitle: null, // list endpoint doesn't carry titles
                  })}
                  onStart={handleStartSession}
                  onNavigate={(href) => router.push(href)}
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

      {scheduleFor && (
        <SchedulePostProjectDialog
          projectId={scheduleFor.projectId}
          projectTitle={scheduleFor.projectTitle}
          onClose={() => setScheduleFor(null)}
          onScheduled={() => { setScheduleFor(null); void load(); }}
        />
      )}
    </div>
  );
}

function Row({ row, onOpen, onSchedule, onStart, onNavigate }: {
  row: ActiveEvalueeRow;
  onOpen: () => void;
  onSchedule: (projectId: string) => void;
  onStart: (evaluationId: string) => Promise<void> | void;
  onNavigate: (href: string) => void;
}) {
  const projectBySeq = (seq: number): ActiveEvalueeProjectRow | null =>
    row.projects?.find((p) => p.sequence === seq) ?? null;
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
      <ProjectCell
        project={projectBySeq(1)}
        onSchedule={onSchedule}
        onStart={onStart}
        onNavigate={onNavigate}
      />
      <ProjectCell
        project={projectBySeq(2)}
        onSchedule={onSchedule}
        onStart={onStart}
        onNavigate={onNavigate}
      />
      <td className="px-3 py-2 text-right">
        <ChevronRight className="h-4 w-4 text-slate-400" />
      </td>
    </tr>
  );
}

function ProjectCell({ project, onSchedule, onStart, onNavigate }: {
  project: ActiveEvalueeProjectRow | null;
  onSchedule: (projectId: string) => void;
  onStart: (evaluationId: string) => Promise<void> | void;
  onNavigate: (href: string) => void;
}) {
  if (!project) {
    return <td className="px-3 py-2 text-xs text-slate-400">—</td>;
  }
  const disp = evaluatorProjectDisplay(project.projectStatus, project.evaluationStatus);
  return (
    <td className="px-3 py-2">
      <span className={`inline-flex rounded-full px-2 py-0.5 text-[10px] font-semibold ${disp.pill}`}>
        {disp.label}
      </span>
      <div className="mt-1">
        <ActionButton
          kind={disp.actionKind}
          label={disp.actionLabel}
          project={project}
          onSchedule={onSchedule}
          onStart={onStart}
          onNavigate={onNavigate}
        />
      </div>
    </td>
  );
}

function ActionButton({ kind, label, project, onSchedule, onStart, onNavigate }: {
  kind: EvaluatorProjectActionKind;
  label: string;
  project: ActiveEvalueeProjectRow;
  onSchedule: (projectId: string) => void;
  onStart: (evaluationId: string) => Promise<void> | void;
  onNavigate: (href: string) => void;
}) {
  if (kind === 'NONE') return null;
  const stop = (e: React.MouseEvent) => e.stopPropagation();
  const commonPrimary =
    'inline-flex items-center gap-1 rounded-md bg-brand-700 px-2 py-0.5 text-[10px] font-semibold text-white hover:bg-brand-800';
  const commonSecondary =
    'inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-0.5 text-[10px] font-medium text-slate-700 hover:bg-slate-50';
  const commonAmber =
    'inline-flex items-center gap-1 rounded-md border border-amber-300 bg-amber-50 px-2 py-0.5 text-[10px] font-semibold text-amber-900 hover:bg-amber-100';

  if (kind === 'SCHEDULE' || kind === 'RESCHEDULE') {
    // Both open the same scheduling dialog. Schedule uses primary tone;
    // Reschedule (only reached from CANCELLED here) uses amber to hint at
    // a recovery action.
    const cls = kind === 'SCHEDULE' ? commonPrimary : commonAmber;
    return (
      <button type="button" className={cls}
        onClick={(e) => { stop(e); onSchedule(project.projectId!); }}
      >
        <CalendarPlus className="h-2.5 w-2.5" />
        {label}
      </button>
    );
  }
  if (kind === 'START') {
    return (
      <button type="button" className={commonPrimary}
        onClick={(e) => { stop(e); if (project.evaluationId) void onStart(project.evaluationId); }}
      >
        <PlayCircle className="h-2.5 w-2.5" />
        {label}
      </button>
    );
  }
  if (kind === 'CONTINUE') {
    return (
      <button type="button" className={commonPrimary}
        onClick={(e) => {
          stop(e);
          if (project.evaluationId) onNavigate(
            `/careers/evaluator/evaluations/${project.evaluationId}/compose`);
        }}
      >
        <PenSquare className="h-2.5 w-2.5" />
        {label}
      </button>
    );
  }
  // OPEN
  return (
    <button type="button" className={commonSecondary}
      onClick={(e) => {
        stop(e);
        if (project.evaluationId) onNavigate(
          `/careers/evaluator/evaluations/${project.evaluationId}`);
      }}
    >
      <Eye className="h-2.5 w-2.5" />
      {label}
    </button>
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
