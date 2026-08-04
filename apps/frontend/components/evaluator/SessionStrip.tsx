'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { CalendarCheck, CheckCircle2, PenSquare, PlayCircle } from 'lucide-react';
import api from '@/lib/careers/api';
import type { ProjectTimelineEntry } from './perproject-types';

/**
 * One-session-covering-multiple-projects strip. Renders above the
 * project cards when two or more of the intern's project cards share a
 * {@code sessionGroupId}. Purpose:
 *
 *   • ONE Start button covers all projects in the session (no more
 *     "click Start on P1, then click Start on P2").
 *   • After the session, ALL cards read Continue evaluation — never a
 *     mixed Start-here / Continue-there state.
 *   • Live datetime + covered project list so the evaluator sees at a
 *     glance "one session covering these two projects at 3pm CT".
 *
 * <p>Composition + publishing stay per-project on the individual cards
 * (ratings + reports are per-project artifacts). Starting the session
 * from here transitions every group member from SCHEDULED → IN_PROGRESS
 * atomically via {@code POST /session-groups/{id}/start}.</p>
 */
interface Props {
  /** Members of the session group, in card render order. */
  members: ProjectTimelineEntry[];
  /** Called after a successful group start so the parent can refresh
   *  the timeline (all cards flip to IN_PROGRESS / Continue). */
  onGroupStarted: () => void;
}

export default function SessionStrip({ members, onGroupStarted }: Props) {
  const router = useRouter();
  const [starting, setStarting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const first = members[0];
  const sessionGroupId = first?.sessionGroupId ?? null;
  const scheduledFor = first?.evaluationScheduledFor ?? null;
  const timezone = first?.evaluationTimezone ?? null;
  const scheduleLabel = scheduledFor ? formatSchedule(scheduledFor, timezone) : null;

  // Derive the collective phase across members.
  //   ALL SCHEDULED → Start action lives here, cards show state only
  //   MIXED / any IN_PROGRESS → session is live, cards handle compose
  //   ALL published/ack/amended → session is done, all cards → Open
  const phase = derivePhase(members);
  const projectLabels = members
    .map((m) => (m.title ? m.title : `Project ${m.projectSequence}`));

  async function handleStart() {
    if (!sessionGroupId) return;
    setStarting(true);
    setErr(null);
    try {
      const res = await api.post<{
        startedEvaluationIds: string[];
      }>(`/api/v1/evaluator/session-groups/${sessionGroupId}/start`);
      // Route into the first evaluation's compose page (evaluator
      // typically drives the whole session from one composer, then
      // navigates to the next once the first is captured).
      const first = res.data.startedEvaluationIds?.[0]
        ?? members[0]?.evaluationId
        ?? null;
      if (first) {
        router.push(`/careers/evaluator/evaluations/${first}/compose`);
      }
      onGroupStarted();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to start session');
    } finally {
      setStarting(false);
    }
  }

  const barTint =
    phase === 'READY_TO_START' ? 'border-l-brand-500 bg-brand-50/60' :
    phase === 'IN_SESSION' ? 'border-l-amber-400 bg-amber-50/60' :
    'border-l-emerald-500 bg-emerald-50/60';

  return (
    <section className={`rounded-lg border border-slate-200 border-l-4 bg-white shadow-sm ${barTint}`}>
      <div className="flex flex-wrap items-start justify-between gap-3 p-4">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <CalendarCheck className="h-4 w-4 text-brand-700" />
            <h3 className="text-sm font-semibold text-slate-900">
              One session covering {members.length} projects
            </h3>
            <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-700">
              {phaseLabel(phase)}
            </span>
          </div>
          <p className="mt-1 text-xs text-slate-700">
            {projectLabels.join(' + ')}
          </p>
          {scheduleLabel && (
            <p className="mt-0.5 text-[11px] text-slate-600">{scheduleLabel}</p>
          )}
        </div>
        {phase === 'READY_TO_START' && (
          <button
            type="button"
            onClick={handleStart}
            disabled={starting}
            className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white shadow-sm hover:bg-brand-800 disabled:opacity-60"
          >
            <PlayCircle className="h-3.5 w-3.5" />
            {starting ? 'Starting…' : 'Start session'}
          </button>
        )}
        {phase === 'IN_SESSION' && (
          <span className="inline-flex shrink-0 items-center gap-1.5 rounded-md border border-amber-300 bg-amber-50 px-3 py-1.5 text-xs font-semibold text-amber-900">
            <PenSquare className="h-3.5 w-3.5" />
            Session in progress
          </span>
        )}
        {phase === 'COMPLETED' && (
          <span className="inline-flex shrink-0 items-center gap-1.5 rounded-md border border-emerald-300 bg-emerald-50 px-3 py-1.5 text-xs font-semibold text-emerald-900">
            <CheckCircle2 className="h-3.5 w-3.5" />
            Session completed
          </span>
        )}
      </div>
      {err && (
        <p className="mx-4 mb-3 rounded-md border border-red-200 bg-red-50 p-2 text-[11px] text-red-800">
          {err}
        </p>
      )}
    </section>
  );
}

type Phase = 'READY_TO_START' | 'IN_SESSION' | 'COMPLETED';

function derivePhase(members: ProjectTimelineEntry[]): Phase {
  const statuses = members.map((m) => m.evaluationStatus);
  // If any member is still SCHEDULED and none has been started, the
  // whole group is start-able. If any member is IN_PROGRESS OR at least
  // one is composed but another still SCHEDULED, the session is live
  // (Start would regress the composed member). If EVERY member has
  // reached a terminal state (PUBLISHED / ACKNOWLEDGED / AMENDED), the
  // session is done.
  const terminal = new Set(['PUBLISHED', 'ACKNOWLEDGED', 'AMENDED']);
  const allTerminal = statuses.every((s) => s != null && terminal.has(s));
  if (allTerminal) return 'COMPLETED';
  const anyInProgress = statuses.some((s) => s === 'IN_PROGRESS');
  const anyTerminal = statuses.some((s) => s != null && terminal.has(s));
  if (anyInProgress || anyTerminal) return 'IN_SESSION';
  return 'READY_TO_START';
}

function phaseLabel(phase: Phase): string {
  switch (phase) {
    case 'READY_TO_START': return 'Scheduled';
    case 'IN_SESSION':     return 'Session in progress';
    case 'COMPLETED':      return 'Session completed';
  }
}

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
