'use client';

import { useMemo, useState } from 'react';
import { CalendarCheck2, X } from 'lucide-react';
import api from '@/lib/careers/api';
import MeetingTimezoneSelect from '@/components/ui/MeetingTimezoneSelect';
import {
  DEFAULT_MEETING_ZONE,
  localInZoneToUtcIso,
  nowPlus30InZone,
} from '@/lib/careers/meeting-timezones';
import type { BulkScheduleResponse, ProjectTimelineEntry } from './perproject-types';

/**
 * "Schedule Final Session" — one Zoom for multiple project evaluations.
 * The evaluator ticks projects (checkboxes) whose POST_PROJECT evals
 * this final session covers, then confirms with a timezone-aware form.
 * All selected evaluations get the same {@code scheduledFor}, one shared
 * Zoom meeting id/join url, and advance status together (SCHEDULED, or
 * IN_PROGRESS when {@code markConducted=true} for retroactive recording).
 */
interface Props {
  /** Only rows whose evaluationId is non-null are eligible — the eval
   *  must exist to schedule against. Server also rejects rows outside
   *  DRAFT/SCHEDULED status. */
  eligibleProjects: ProjectTimelineEntry[];
  onClose: () => void;
  onScheduled: () => void;
}

export default function ScheduleFinalSessionDialog({
  eligibleProjects, onClose, onScheduled,
}: Props) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [scheduledFor, setScheduledFor] = useState<string>(
    () => nowPlus30InZone(DEFAULT_MEETING_ZONE),
  );
  const [timezone, setTimezone] = useState<string>(DEFAULT_MEETING_ZONE);
  const [durationMinutes, setDurationMinutes] = useState(60);
  const [topic, setTopic] = useState<string>('Final Session — multi-project review');
  const [agenda, setAgenda] = useState<string>('');
  const [markConducted, setMarkConducted] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const eligibleEvaluationIds = useMemo(
    () => new Map<string, string>(
      eligibleProjects
        .filter((p) => p.evaluationId != null)
        .map((p) => [p.evaluationId as string, p.projectId] as const),
    ),
    [eligibleProjects],
  );

  function toggle(projectId: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(projectId)) next.delete(projectId);
      else next.add(projectId);
      return next;
    });
  }

  async function submit() {
    setErr(null);
    if (selected.size === 0) {
      setErr('Pick at least one project.');
      return;
    }
    if (!scheduledFor) {
      setErr('Pick a date/time.');
      return;
    }
    const evalIds: string[] = [];
    for (const [evId, projectId] of eligibleEvaluationIds.entries()) {
      if (selected.has(projectId)) evalIds.push(evId);
    }
    if (evalIds.length === 0) {
      setErr('Selected projects have no evaluations to schedule.');
      return;
    }
    setSubmitting(true);
    try {
      await api.post<BulkScheduleResponse>(
        '/api/v1/evaluator/post-project-evaluations/bulk-schedule',
        {
          evaluationIds: evalIds,
          scheduledFor: localInZoneToUtcIso(scheduledFor, timezone),
          durationMinutes,
          timezone,
          topic: topic.trim() || null,
          agenda: agenda.trim() || null,
          markConducted,
        },
      );
      onScheduled();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to schedule');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="flex max-h-[92vh] w-full max-w-2xl flex-col rounded-lg bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-3">
          <h3 className="text-base font-semibold text-slate-900">
            Schedule Final Session
          </h3>
          <button type="button" onClick={onClose}
            className="rounded-full p-1 hover:bg-slate-100" aria-label="Close">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-5 text-sm">
          <p className="text-xs text-slate-500">
            One session covering multiple project evaluations. All selected
            projects will share the same Zoom meeting and scheduled time.
          </p>

          {/* Project selector */}
          <div>
            <p className="text-xs font-semibold text-slate-700">
              Projects to cover ({selected.size} selected of {eligibleProjects.length} eligible)
            </p>
            {eligibleProjects.length === 0 ? (
              <p className="mt-1 rounded-md border border-dashed border-slate-300 p-3 text-center text-[11px] text-slate-500">
                No eligible projects. Only DRAFT / SCHEDULED POST_PROJECT
                evaluations can be bulk-scheduled.
              </p>
            ) : (
              <ul className="mt-1 max-h-56 space-y-1 overflow-y-auto rounded-md border border-slate-200 bg-slate-50 p-2">
                {eligibleProjects.map((p) => {
                  const checked = selected.has(p.projectId);
                  return (
                    <li key={p.projectId}>
                      <label className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-xs hover:bg-white">
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={() => toggle(p.projectId)}
                        />
                        <span className="inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-slate-100 text-[10px] font-semibold text-slate-700">
                          #{p.projectSequence}
                        </span>
                        <span className="truncate font-medium text-slate-900">
                          {p.title ?? '(untitled project)'}
                        </span>
                        {p.techStack && (
                          <span className="ml-1 truncate text-[10px] text-slate-500">
                            · {p.techStack}
                          </span>
                        )}
                        {p.evaluationStatus && (
                          <span className="ml-auto rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-700">
                            {p.evaluationStatus.replaceAll('_', ' ')}
                          </span>
                        )}
                      </label>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>

          <Field label="Topic">
            <input type="text" value={topic} onChange={(e) => setTopic(e.target.value)}
              maxLength={200}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          </Field>
          <Field label="Date & time">
            <input type="datetime-local" value={scheduledFor}
              onChange={(e) => setScheduledFor(e.target.value)}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          </Field>
          <Field label="Timezone">
            <MeetingTimezoneSelect value={timezone} onChange={setTimezone} />
          </Field>
          <Field label="Duration (minutes)">
            <input type="number" min={15} max={180} step={15}
              value={durationMinutes}
              onChange={(e) => setDurationMinutes(parseInt(e.target.value || '60', 10))}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          </Field>
          <Field label="Agenda (optional)">
            <textarea value={agenda} onChange={(e) => setAgenda(e.target.value)}
              rows={3} maxLength={2000}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          </Field>

          <label className="flex items-start gap-2 rounded-md border border-slate-200 bg-slate-50 p-2 text-xs">
            <input
              type="checkbox"
              checked={markConducted}
              onChange={(e) => setMarkConducted(e.target.checked)}
              className="mt-0.5"
            />
            <span>
              <span className="font-semibold text-slate-800">Session already conducted</span>
              <span className="ml-1 text-slate-500">
                — record the meeting retroactively; selected projects
                advance to session-completed (IN_PROGRESS) instead of SCHEDULED.
              </span>
            </span>
          </label>

          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              {err}
            </p>
          )}
        </div>
        <div className="flex items-center justify-end gap-2 border-t border-slate-200 p-4">
          <button type="button" onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm">
            Cancel
          </button>
          <button type="button" onClick={submit}
            disabled={submitting || selected.size === 0}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60">
            <CalendarCheck2 className="h-3.5 w-3.5" />
            {submitting ? 'Scheduling…' : `Schedule for ${selected.size} project${selected.size === 1 ? '' : 's'}`}
          </button>
        </div>
      </div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="text-xs font-semibold text-slate-700">{label}</span>
      <div className="mt-1">{children}</div>
    </label>
  );
}
