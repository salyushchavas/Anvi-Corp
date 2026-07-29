'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import {
  CalendarCheck2,
  CalendarPlus,
  CheckCircle2,
  CheckSquare,
  Hourglass,
  PenSquare,
  PlayCircle,
  Square,
} from 'lucide-react';
import api from '@/lib/careers/api';
import ProjectContextPanel from './ProjectContextPanel';
import InlineRecordingPlayer from './InlineRecordingPlayer';
import SchedulePostProjectDialog from './SchedulePostProjectDialog';
import ScheduleFinalSessionDialog from './ScheduleFinalSessionDialog';
import ReferenceQaPanel from '@/components/project/ReferenceQaPanel';
import type { ProjectTimelineEntry, ProjectTimelineResponse } from './perproject-types';

/**
 * The evaluator's Active Evaluee hub — every project rendered as a
 * full-height CARD (Project 1, Project 2, …). Each card carries:
 * project details, trainer Q&A, recording, evaluation status pill, and
 * per-project actions (Schedule Session / Mark session conducted /
 * Compose evaluation).
 *
 * <p>The header exposes a "Schedule Final Session" button + per-card
 * selection checkboxes. Selected projects go into
 * {@link ScheduleFinalSessionDialog} which POSTs one shared Zoom meeting
 * across N POST_PROJECT evaluations — the bulk-schedule server endpoint
 * validates + advances every row atomically.</p>
 */
interface Props {
  lifecycleId: string;
}

const CARD_DISPLAY_STATE: Record<string, { label: string; badge: string }> = {
  NOT_SCHEDULED: { label: 'Not scheduled', badge: 'bg-slate-100 text-slate-700' },
  SCHEDULED: { label: 'Scheduled', badge: 'bg-brand-100 text-brand-800' },
  SESSION_COMPLETED: { label: 'Session completed', badge: 'bg-amber-100 text-amber-900' },
  EVALUATED: { label: 'Evaluated ✓', badge: 'bg-emerald-100 text-emerald-800' },
  IN_PROGRESS_PROJECT: { label: 'Project in progress', badge: 'bg-slate-100 text-slate-500' },
};

/** Derive the user-facing evaluation state from the timeline entry's
 *  raw project + evaluation status fields. */
function displayState(entry: ProjectTimelineEntry): keyof typeof CARD_DISPLAY_STATE {
  if (entry.evaluationStatus === 'PUBLISHED'
      || entry.evaluationStatus === 'ACKNOWLEDGED'
      || entry.evaluationStatus === 'AMENDED') return 'EVALUATED';
  if (entry.evaluationStatus === 'IN_PROGRESS') return 'SESSION_COMPLETED';
  if (entry.evaluationStatus === 'SCHEDULED') return 'SCHEDULED';
  if (entry.evaluationStatus === 'DRAFT') return 'NOT_SCHEDULED';
  return 'IN_PROGRESS_PROJECT';
}

export default function ProjectCardsSection({ lifecycleId }: Props) {
  const [data, setData] = useState<ProjectTimelineResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [selectionMode, setSelectionMode] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [finalDialogOpen, setFinalDialogOpen] = useState(false);

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

  const entries = data?.entries ?? [];
  const eligibleForBulk = useMemo(
    () => entries.filter((e) => e.evaluationId != null
      && (e.evaluationStatus === 'DRAFT' || e.evaluationStatus === 'SCHEDULED')),
    [entries],
  );

  function toggleSelected(projectId: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(projectId)) next.delete(projectId);
      else next.add(projectId);
      return next;
    });
  }
  function exitSelectionMode() {
    setSelectionMode(false);
    setSelected(new Set());
  }

  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold text-slate-900">Projects</h2>
          <p className="mt-0.5 text-[11px] text-slate-500">
            One card per project. Trainer Q&amp;A, recording, and
            evaluation actions live inside each card.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
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
          {eligibleForBulk.length > 1 && !selectionMode && (
            <button type="button" onClick={() => setSelectionMode(true)}
              className="inline-flex items-center gap-1 rounded-md border border-brand-300 bg-brand-50 px-2.5 py-1 text-xs font-semibold text-brand-800 hover:bg-brand-100">
              <CalendarCheck2 className="h-3.5 w-3.5" />
              Schedule Final Session
            </button>
          )}
          {selectionMode && (
            <div className="flex items-center gap-2">
              <span className="text-[11px] text-slate-500">
                {selected.size} selected
              </span>
              <button type="button"
                onClick={() => setFinalDialogOpen(true)}
                disabled={selected.size === 0}
                className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-2.5 py-1 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60">
                <CalendarCheck2 className="h-3.5 w-3.5" />
                Continue
              </button>
              <button type="button" onClick={exitSelectionMode}
                className="rounded-md border border-slate-200 px-2 py-1 text-xs text-slate-600 hover:bg-slate-50">
                Cancel
              </button>
            </div>
          )}
        </div>
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
          {err}
        </p>
      )}
      {loading && !data && (
        <div className="h-24 animate-pulse rounded-md bg-slate-100" aria-hidden />
      )}
      {data && entries.length === 0 && !loading && (
        <p className="rounded-md border border-dashed border-slate-200 bg-slate-50 p-6 text-center text-sm text-slate-500">
          No projects yet. Projects appear here once the trainer assigns them.
        </p>
      )}

      {entries.length > 0 && (
        <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
          {entries.map((entry) => (
            <ProjectCard
              key={entry.projectId}
              entry={entry}
              selectionMode={selectionMode}
              selected={selected.has(entry.projectId)}
              onToggleSelect={() => toggleSelected(entry.projectId)}
              onChange={load}
            />
          ))}
        </div>
      )}

      {finalDialogOpen && (
        <ScheduleFinalSessionDialog
          eligibleProjects={
            // Selection mode: only rows the user actually picked.
            // (Filter to eligible-for-bulk defensively.)
            eligibleForBulk.filter((p) => selected.has(p.projectId))
          }
          onClose={() => setFinalDialogOpen(false)}
          onScheduled={() => {
            setFinalDialogOpen(false);
            exitSelectionMode();
            void load();
          }}
        />
      )}
    </section>
  );
}

function ProjectCard({
  entry, selectionMode, selected, onToggleSelect, onChange,
}: {
  entry: ProjectTimelineEntry;
  selectionMode: boolean;
  selected: boolean;
  onToggleSelect: () => void;
  onChange: () => void;
}) {
  const [scheduleOpen, setScheduleOpen] = useState(false);
  const [markingConducted, setMarkingConducted] = useState(false);
  const [markErr, setMarkErr] = useState<string | null>(null);
  const state = displayState(entry);
  const styles = CARD_DISPLAY_STATE[state];
  const canEvaluate = !!entry.evaluationId
    && entry.evaluationStatus !== 'PUBLISHED'
    && entry.evaluationStatus !== 'ACKNOWLEDGED'
    && entry.evaluationStatus !== 'AMENDED';
  const canSchedule = !!entry.evaluationId
    && (entry.evaluationStatus === 'DRAFT' || entry.evaluationStatus === 'SCHEDULED');
  const canMarkConducted = !!entry.evaluationId
    && entry.evaluationStatus === 'SCHEDULED';
  const eligibleForFinal = !!entry.evaluationId
    && (entry.evaluationStatus === 'DRAFT' || entry.evaluationStatus === 'SCHEDULED');
  const composeHref = entry.evaluationId
    ? `/careers/evaluator/evaluations/${entry.evaluationId}/compose`
    : null;
  const detailHref = entry.evaluationId
    ? `/careers/evaluator/evaluations/${entry.evaluationId}`
    : null;

  async function markConducted() {
    if (!entry.evaluationId) return;
    setMarkErr(null);
    setMarkingConducted(true);
    try {
      // Reuses the workflow start endpoint — SCHEDULED → IN_PROGRESS.
      await api.post(`/api/v1/evaluator/evaluations/${entry.evaluationId}/start`);
      onChange();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setMarkErr(ax.response?.data?.error ?? ax.message ?? 'Failed to mark conducted');
    } finally {
      setMarkingConducted(false);
    }
  }

  return (
    <article className="flex flex-col rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      {/* Header */}
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 flex-1 items-start gap-2">
          {selectionMode && eligibleForFinal && (
            <button type="button" onClick={onToggleSelect}
              className="mt-0.5 text-brand-700 hover:text-brand-800"
              aria-label={selected ? 'Deselect' : 'Select for Final Session'}>
              {selected
                ? <CheckSquare className="h-4 w-4" />
                : <Square className="h-4 w-4" />}
            </button>
          )}
          <div className="min-w-0 flex-1">
            <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              Project {entry.projectSequence}
            </p>
            <h3 className="mt-0.5 text-base font-semibold text-slate-900">
              {entry.title ?? '(untitled project)'}
            </h3>
            <p className="mt-0.5 text-xs text-slate-500">
              {entry.techStack && <>{entry.techStack} · </>}
              Project status: {entry.projectStatus.replaceAll('_', ' ')}
              {entry.completedAt && (
                <> · Completed {new Date(entry.completedAt).toLocaleDateString()}</>
              )}
            </p>
          </div>
        </div>
        <span className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold ${styles.badge}`}>
          {styles.label}
        </span>
      </div>

      {/* Scores (for evaluated projects) */}
      {(entry.evaluationOverallScore != null || entry.evaluationRecommendation) && (
        <div className="mt-2 flex flex-wrap items-center gap-2 text-[11px]">
          {entry.evaluationOverallScore != null && (
            <span className="rounded bg-slate-100 px-1.5 py-0.5 font-semibold text-slate-700">
              Score {entry.evaluationOverallScore}
            </span>
          )}
          {entry.evaluationRecommendation && (
            <span className="rounded bg-slate-100 px-1.5 py-0.5 text-slate-600">
              {entry.evaluationRecommendation.replaceAll('_', ' ')}
            </span>
          )}
          {entry.evaluationPublishedAt && (
            <span className="text-slate-500">
              Published {new Date(entry.evaluationPublishedAt).toLocaleDateString()}
            </span>
          )}
        </div>
      )}

      {/* Full project details */}
      <div className="mt-3 space-y-3">
        <ProjectContextPanel projectId={entry.projectId} />

        {/* Trainer's per-project reference Q&A. alwaysShow → neutral
            empty-state note when nothing was attached. */}
        <ReferenceQaPanel projectId={entry.projectId} alwaysShow />

        {/* Recording */}
        {entry.evaluationId ? (
          <InlineRecordingPlayer evaluationId={entry.evaluationId} />
        ) : (
          <p className="rounded-md border border-dashed border-slate-300 p-3 text-center text-[11px] text-slate-500">
            No evaluation exists yet — recording will appear once one is
            auto-drafted on project completion.
          </p>
        )}
      </div>

      {/* Actions */}
      <div className="mt-auto pt-3">
        <div className="flex flex-wrap items-center gap-2 border-t border-slate-100 pt-3">
          {canSchedule && entry.evaluationId && (
            <button type="button"
              onClick={() => setScheduleOpen(true)}
              className="inline-flex items-center gap-1 rounded-md border border-brand-300 bg-brand-50 px-2.5 py-1 text-xs font-semibold text-brand-800 hover:bg-brand-100">
              <CalendarPlus className="h-3 w-3" />
              {entry.evaluationStatus === 'SCHEDULED' ? 'Reschedule session' : 'Schedule session'}
            </button>
          )}
          {canMarkConducted && (
            <button type="button"
              onClick={markConducted}
              disabled={markingConducted}
              className="inline-flex items-center gap-1 rounded-md border border-amber-300 bg-amber-50 px-2.5 py-1 text-xs font-semibold text-amber-900 hover:bg-amber-100 disabled:opacity-60">
              <CalendarCheck2 className="h-3 w-3" />
              {markingConducted ? 'Marking…' : 'Mark session conducted'}
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
        </div>
        {markErr && (
          <p className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-[11px] text-red-800">
            {markErr}
          </p>
        )}
      </div>

      {scheduleOpen && entry.evaluationId && (
        <SchedulePostProjectDialog
          evaluationId={entry.evaluationId}
          projectTitle={entry.title}
          onClose={() => setScheduleOpen(false)}
          onScheduled={() => { setScheduleOpen(false); onChange(); }}
        />
      )}
    </article>
  );
}

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
