'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { CheckCircle2, Clock, Hourglass, PlayCircle } from 'lucide-react';
import api from '@/lib/careers/api';
import type { ProjectTimelineEntry, ProjectTimelineResponse } from './perproject-types';

/**
 * The evaluator's per-intern project timeline — every project the intern
 * has, with its evaluation state derived server-side. Renders inline on
 * the Active Evaluee detail so the evaluator sees the complete picture
 * (which projects are done, which are awaiting evaluation, which
 * evaluations already published) without leaving the page.
 *
 * <p>Rows in {@code AWAITING_EVAL} state get a prominent "Evaluate" CTA
 * that jumps to the compose hub — same destination the dashboard queue
 * card links to.</p>
 */
interface Props {
  lifecycleId: string;
}

export default function ProjectTimelineSection({ lifecycleId }: Props) {
  const [data, setData] = useState<ProjectTimelineResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const res = await api.get<ProjectTimelineResponse>(
        `/api/v1/evaluator/evaluees/${lifecycleId}/project-timeline`,
      );
      setData(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load timeline');
    } finally {
      setLoading(false);
    }
  }, [lifecycleId]);
  useEffect(() => { void load(); }, [load]);

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold text-slate-900">Project timeline</h2>
          <p className="mt-0.5 text-[11px] text-slate-500">
            Every project this intern has taken on, with its evaluation state.
          </p>
        </div>
        {data && (
          <div className="flex flex-wrap items-center gap-1.5 text-[11px]">
            <Pill tone="emerald" icon={<CheckCircle2 className="h-3 w-3" />}
              label={`${data.evaluatedCount} evaluated`} />
            {data.awaitingEvaluationCount > 0 && (
              <Pill tone="amber" icon={<Hourglass className="h-3 w-3" />}
                label={`${data.awaitingEvaluationCount} awaiting`} />
            )}
            {data.inProgressCount > 0 && (
              <Pill tone="slate" icon={<PlayCircle className="h-3 w-3" />}
                label={`${data.inProgressCount} in progress`} />
            )}
            <Pill tone="slate" label={`${data.totalProjects} total`} />
          </div>
        )}
      </div>

      {err && (
        <p className="mt-3 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          {err}
        </p>
      )}
      {loading && !data && (
        <div className="mt-3 h-24 animate-pulse rounded-md bg-slate-100" aria-hidden />
      )}
      {data && data.entries.length === 0 && !loading && (
        <p className="mt-3 rounded-md border border-dashed border-slate-200 bg-slate-50 p-6 text-center text-sm text-slate-500">
          No projects yet. Projects appear here once the trainer assigns them.
        </p>
      )}
      {data && data.entries.length > 0 && (
        <ol className="mt-3 space-y-2">
          {data.entries.map((e) => <TimelineRow key={e.projectId} entry={e} />)}
        </ol>
      )}
    </section>
  );
}

function TimelineRow({ entry }: { entry: ProjectTimelineEntry }) {
  const canEvaluate = entry.uiState === 'AWAITING_EVAL' && !!entry.evaluationId;
  const composeHref = entry.evaluationId
    ? `/careers/evaluator/evaluations/${entry.evaluationId}/compose`
    : null;
  const detailHref = entry.evaluationId
    ? `/careers/evaluator/evaluations/${entry.evaluationId}`
    : null;

  const stateStyles = STATE_STYLES[entry.uiState];

  return (
    <li className={`rounded-md border p-3 ${stateStyles.container}`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-slate-100 text-[11px] font-semibold text-slate-700">
              #{entry.projectSequence}
            </span>
            <p className="truncate text-sm font-medium text-slate-900">
              {entry.title ?? '(untitled project)'}
            </p>
            <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${stateStyles.badge}`}>
              {stateStyles.label}
            </span>
          </div>
          <p className="mt-0.5 truncate text-xs text-slate-500">
            {entry.techStack && <>{entry.techStack} · </>}
            {entry.projectStatus.replaceAll('_', ' ')}
            {entry.completedAt && (
              <> · Completed {new Date(entry.completedAt).toLocaleDateString()}</>
            )}
            {entry.evaluationPublishedAt && (
              <> · Evaluated {new Date(entry.evaluationPublishedAt).toLocaleDateString()}</>
            )}
          </p>
          {(entry.evaluationOverallScore != null || entry.evaluationRecommendation) && (
            <p className="mt-0.5 flex items-center gap-2 text-[11px] text-slate-600">
              {entry.evaluationOverallScore != null && (
                <span className="rounded bg-slate-100 px-1.5 py-0.5 font-semibold">
                  Score {entry.evaluationOverallScore}
                </span>
              )}
              {entry.evaluationRecommendation && (
                <span className="rounded bg-slate-100 px-1.5 py-0.5">
                  {entry.evaluationRecommendation.replaceAll('_', ' ')}
                </span>
              )}
            </p>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {canEvaluate && composeHref && (
            <Link href={composeHref}
              className="rounded-md bg-brand-700 px-2.5 py-1 text-[11px] font-semibold text-white hover:bg-brand-800">
              Evaluate
            </Link>
          )}
          {!canEvaluate && detailHref && (
            <Link href={detailHref}
              className="rounded-md border border-slate-200 bg-white px-2.5 py-1 text-[11px] font-medium text-slate-700 hover:bg-slate-50">
              Open
            </Link>
          )}
        </div>
      </div>
    </li>
  );
}

const STATE_STYLES: Record<ProjectTimelineEntry['uiState'], {
  label: string; container: string; badge: string;
}> = {
  IN_PROGRESS: {
    label: 'In progress',
    container: 'border-slate-200 bg-white',
    badge: 'bg-slate-100 text-slate-700',
  },
  AWAITING_EVAL: {
    label: 'Awaiting evaluation',
    container: 'border-amber-300 bg-amber-50',
    badge: 'bg-amber-200 text-amber-900',
  },
  EVALUATED: {
    label: 'Evaluated ✓',
    container: 'border-emerald-200 bg-emerald-50/60',
    badge: 'bg-emerald-100 text-emerald-800',
  },
  AMENDED: {
    label: 'Amended',
    container: 'border-slate-200 bg-white',
    badge: 'bg-slate-100 text-slate-700',
  },
};

function Pill({ tone, icon, label }: {
  tone: 'emerald' | 'amber' | 'slate';
  icon?: React.ReactNode;
  label: string;
}) {
  const cls = tone === 'emerald' ? 'bg-emerald-50 text-emerald-800 border-emerald-200'
    : tone === 'amber' ? 'bg-amber-50 text-amber-900 border-amber-300'
    : 'bg-slate-50 text-slate-700 border-slate-200';
  return (
    <span className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 font-medium ${cls}`}>
      {icon}
      {label}
    </span>
  );
}
