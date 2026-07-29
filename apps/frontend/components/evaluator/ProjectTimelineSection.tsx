'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import {
  CalendarPlus,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Hourglass,
  PenSquare,
  PlayCircle,
} from 'lucide-react';
import api from '@/lib/careers/api';
import ProjectContextPanel from './ProjectContextPanel';
import InlineRecordingPlayer from './InlineRecordingPlayer';
import SchedulePostProjectDialog from './SchedulePostProjectDialog';
import ReferenceQaPanel from '@/components/project/ReferenceQaPanel';
import type { ProjectTimelineEntry, ProjectTimelineResponse } from './perproject-types';

/**
 * The evaluator's per-intern project hub — every project + its full
 * context (project detail, trainer Q&A, recording, evaluation status +
 * actions) inline as expandable rows. The evaluator does everything
 * for a project from THIS one page — no navigating away for context.
 *
 * <p>Panels inside each row lazy-mount only when the evaluator expands
 * it, so the initial load stays fast even for interns with 10+ projects.
 * Each panel handles its own empty state cleanly (returns null / shows
 * a neutral note), so a project with no Q&A / no recording renders
 * without broken skeletons.</p>
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
          <h2 className="text-sm font-semibold text-slate-900">Projects</h2>
          <p className="mt-0.5 text-[11px] text-slate-500">
            Every project this intern has taken on. Expand a row to see
            its full details, trainer Q&amp;A, recording, and evaluation
            actions — all inline.
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
          {data.entries.map((e) => (
            <ExpandableRow key={e.projectId} entry={e} onScheduled={load} />
          ))}
        </ol>
      )}
    </section>
  );
}

function ExpandableRow({ entry, onScheduled }: {
  entry: ProjectTimelineEntry;
  onScheduled: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [scheduleOpen, setScheduleOpen] = useState(false);
  const canEvaluate = entry.uiState === 'AWAITING_EVAL' && !!entry.evaluationId;
  const canSchedule = canEvaluate
    && (entry.evaluationStatus === 'DRAFT' || entry.evaluationStatus === 'SCHEDULED');
  const composeHref = entry.evaluationId
    ? `/careers/evaluator/evaluations/${entry.evaluationId}/compose`
    : null;
  const detailHref = entry.evaluationId
    ? `/careers/evaluator/evaluations/${entry.evaluationId}`
    : null;

  const stateStyles = STATE_STYLES[entry.uiState];

  return (
    <li className={`rounded-md border ${stateStyles.container}`}>
      <button
        type="button"
        onClick={() => setExpanded((x) => !x)}
        className="flex w-full items-start justify-between gap-3 rounded-md p-3 text-left hover:bg-white/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
        aria-expanded={expanded}
      >
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="text-slate-500">
              {expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
            </span>
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
      </button>

      {expanded && (
        <div className="space-y-3 border-t border-slate-200 bg-white/60 p-3">
          {/* Full project detail — deliverables, requirements, dates,
              trainer review notes. Lazy-fetched by the panel on mount. */}
          <ProjectContextPanel projectId={entry.projectId} />

          {/* Trainer's per-project reference Q&A. alwaysShow so the
              panel renders a neutral empty-state note instead of a
              silent void when the trainer hasn't attached any pairs. */}
          <ReferenceQaPanel projectId={entry.projectId} alwaysShow />

          {/* Recording playback — the button does the presign fetch
              lazily; no signature minted for a row nobody opens. */}
          {entry.evaluationId ? (
            <InlineRecordingPlayer evaluationId={entry.evaluationId} />
          ) : (
            <p className="rounded-md border border-dashed border-slate-300 p-3 text-center text-[11px] text-slate-500">
              No evaluation exists yet — recording will appear here once one
              is auto-drafted on project completion.
            </p>
          )}

          {/* Inline actions */}
          <div className="flex flex-wrap items-center gap-2 rounded-md border border-slate-200 bg-slate-50 p-3">
            {canSchedule && entry.evaluationId && (
              <button type="button"
                onClick={() => setScheduleOpen(true)}
                className="inline-flex items-center gap-1 rounded-md border border-brand-300 bg-brand-50 px-2.5 py-1 text-xs font-semibold text-brand-800 hover:bg-brand-100">
                <CalendarPlus className="h-3 w-3" />
                {entry.evaluationStatus === 'SCHEDULED' ? 'Reschedule' : 'Schedule session'}
              </button>
            )}
            {canEvaluate && composeHref && (
              <Link href={composeHref}
                className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-2.5 py-1 text-xs font-semibold text-white hover:bg-brand-800">
                <PenSquare className="h-3 w-3" />
                {entry.evaluationStatus === 'IN_PROGRESS' ? 'Continue evaluation' : 'Compose evaluation'}
              </Link>
            )}
            {!canEvaluate && detailHref && (
              <Link href={detailHref}
                className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50">
                Open evaluation
              </Link>
            )}
            {entry.evaluationStatus && (
              <span className="text-[11px] text-slate-500">
                Evaluation status:{' '}
                <span className="font-semibold text-slate-700">
                  {entry.evaluationStatus.replaceAll('_', ' ')}
                </span>
              </span>
            )}
          </div>
        </div>
      )}

      {scheduleOpen && (
        <SchedulePostProjectDialog
          projectId={entry.projectId}
          projectTitle={entry.title}
          onClose={() => setScheduleOpen(false)}
          onScheduled={() => { setScheduleOpen(false); onScheduled(); }}
        />
      )}
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
