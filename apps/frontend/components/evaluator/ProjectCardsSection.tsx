'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import {
  CalendarCheck,
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
import {
  evaluatorProjectDisplay,
  type EvaluatorProjectDisplayState,
} from './project-status';

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

/** Per-state visual chrome for the card status bar (colored left border +
 *  tint). Label + primary action come from {@link evaluatorProjectDisplay}
 *  so the card + Active Evaluees table stay in vocabulary lock-step. */
const CARD_BAR_STYLES: Record<EvaluatorProjectDisplayState, {
  bar: string;
  hint: string;
}> = {
  IN_PROGRESS: {
    bar: 'border-l-4 border-l-slate-400 bg-slate-50',
    hint: 'Project is still in progress — wait for the trainer to verify.',
  },
  PENDING_EVAL: {
    bar: 'border-l-4 border-l-amber-400 bg-amber-50/60',
    hint: 'Trainer-verified — schedule the evaluation session.',
  },
  SCHEDULED: {
    bar: 'border-l-4 border-l-brand-500 bg-brand-50/60',
    hint: 'Session is on the calendar.',
  },
  SESSION_COMPLETED: {
    bar: 'border-l-4 border-l-amber-400 bg-amber-50/60',
    hint: 'Session was conducted — evaluation in progress.',
  },
  COMPLETED: {
    bar: 'border-l-4 border-l-emerald-500 bg-emerald-50/60',
    hint: 'Evaluation published.',
  },
  CANCELLED: {
    bar: 'border-l-4 border-l-red-400 bg-red-50/60',
    hint: 'Previous session was cancelled — reschedule when ready.',
  },
};

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
  const router = useRouter();
  const [scheduleOpen, setScheduleOpen] = useState(false);
  const [startingSession, setStartingSession] = useState(false);
  const [actionErr, setActionErr] = useState<string | null>(null);
  const disp = evaluatorProjectDisplay(entry.projectStatus, entry.evaluationStatus);
  const styles = CARD_BAR_STYLES[disp.state];
  // Bulk Final still requires an existing evaluation (the bulk endpoint
  // takes evaluationIds, not projectIds). Consistent with server contract.
  const eligibleForFinal = !!entry.evaluationId
    && (entry.evaluationStatus === 'DRAFT' || entry.evaluationStatus === 'SCHEDULED');
  const composeHref = entry.evaluationId
    ? `/careers/evaluator/evaluations/${entry.evaluationId}/compose`
    : null;
  const detailHref = entry.evaluationId
    ? `/careers/evaluator/evaluations/${entry.evaluationId}`
    : null;
  // Secondary "Reschedule" surfaces only when the primary action is
  // Start Session (i.e., the row is SCHEDULED) — evaluators sometimes
  // need to move a booked slot instead of starting it.
  const showRescheduleSecondary = disp.state === 'SCHEDULED';

  async function handleStartSession() {
    if (!entry.evaluationId) return;
    setActionErr(null);
    setStartingSession(true);
    try {
      // SCHEDULED → IN_PROGRESS + route straight into the compose form.
      await api.post(`/api/v1/evaluator/evaluations/${entry.evaluationId}/start`);
      router.push(`/careers/evaluator/evaluations/${entry.evaluationId}/compose`);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setActionErr(ax.response?.data?.error ?? ax.message ?? 'Failed to start session');
    } finally {
      setStartingSession(false);
    }
  }

  function triggerPrimaryAction() {
    switch (disp.actionKind) {
      case 'SCHEDULE':
      case 'RESCHEDULE':
        setScheduleOpen(true);
        return;
      case 'START':
        void handleStartSession();
        return;
      case 'CONTINUE':
        if (composeHref) router.push(composeHref);
        return;
      case 'OPEN':
        if (detailHref) router.push(detailHref);
        return;
      default:
        return;
    }
  }

  const scheduledLabel = disp.state === 'SCHEDULED' && entry.evaluationScheduledFor
    ? formatSchedule(entry.evaluationScheduledFor, entry.evaluationTimezone)
    : null;

  const primaryButtonCls =
    disp.actionKind === 'RESCHEDULE'
      ? 'inline-flex shrink-0 items-center gap-1 rounded-md border border-amber-300 bg-amber-50 px-2.5 py-1 text-xs font-semibold text-amber-900 hover:bg-amber-100'
      : disp.actionKind === 'OPEN'
      ? 'inline-flex shrink-0 items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50'
      : 'inline-flex shrink-0 items-center gap-1 rounded-md bg-brand-700 px-2.5 py-1 text-xs font-semibold text-white shadow-sm hover:bg-brand-800';

  const PrimaryIcon =
    disp.actionKind === 'START'
      ? PlayCircle
      : disp.actionKind === 'CONTINUE'
      ? PenSquare
      : disp.actionKind === 'OPEN'
      ? CheckCircle2
      : CalendarPlus;

  return (
    <article className="flex flex-col overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
      {/* Prominent status bar — colored left border + labeled strip.
          One-look answer to "what's this project's session state?"
          Right side carries the single primary action per state. The
          concrete scheduled date/time moved to a footer BELOW the card
          body so the top bar stays a scannable state indicator. */}
      <div className={`flex items-center justify-between gap-3 border-b border-slate-200 px-4 py-2.5 ${styles.bar}`}>
        <div className="flex min-w-0 items-center gap-2">
          <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${disp.pill}`}>
            {disp.label}
          </span>
          <span className="truncate text-[11px] text-slate-600">
            {styles.hint}
          </span>
        </div>
        {disp.actionKind !== 'NONE' && (
          <button type="button"
            onClick={triggerPrimaryAction}
            disabled={startingSession}
            className={primaryButtonCls + ' disabled:opacity-60'}>
            <PrimaryIcon className="h-3 w-3" />
            {startingSession ? 'Starting…' : disp.actionLabel}
          </button>
        )}
      </div>

      {/* Body */}
      <div className="flex flex-1 flex-col p-4">
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

      {/* Secondary action row — surfaces "Reschedule" when the primary
          action is Start Session so evaluators can move a scheduled
          slot instead of starting it. Hidden otherwise. */}
      {(showRescheduleSecondary || actionErr) && (
      <div className="mt-auto pt-3">
        {showRescheduleSecondary && (
          <div className="flex flex-wrap items-center gap-2 border-t border-slate-100 pt-3">
            <button type="button"
              onClick={() => setScheduleOpen(true)}
              className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50">
              <CalendarPlus className="h-3 w-3" />
              Reschedule
            </button>
          </div>
        )}
        {actionErr && (
          <p className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-[11px] text-red-800">
            {actionErr}
          </p>
        )}
      </div>
      )}
      </div>

      {/* Scheduled-session footer — beneath the card body so the
          concrete date/time/timezone lives near the compose / start
          actions instead of competing with the status label at the
          top. Only rendered when a scheduled slot exists (SCHEDULED
          state); other states hide the footer entirely so the card
          height stays clean. */}
      {scheduledLabel && (
        <div className="border-t border-slate-200 bg-brand-50/50 px-4 py-2 text-[11px] text-brand-900">
          <span className="inline-flex items-center gap-1.5">
            <CalendarCheck className="h-3.5 w-3.5" />
            {scheduledLabel}
          </span>
        </div>
      )}

      {scheduleOpen && (
        <SchedulePostProjectDialog
          projectId={entry.projectId}
          projectTitle={entry.title}
          onClose={() => setScheduleOpen(false)}
          onScheduled={() => { setScheduleOpen(false); onChange(); }}
        />
      )}
    </article>
  );
}

/** Human-friendly "Session scheduled: {date} · {time} · {zone}" line. */
function formatSchedule(iso: string, timezone: string | null): string {
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return 'Session scheduled';
    const date = d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
    const time = d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
    const suffix = timezone && timezone !== 'UTC' ? ` · ${timezone}` : '';
    return `Session scheduled: ${date} · ${time}${suffix}`;
  } catch {
    return 'Session scheduled';
  }
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
