'use client';

import { useState } from 'react';
import { CalendarPlus, X } from 'lucide-react';
import api from '@/lib/careers/api';
import MeetingTimezoneSelect from '@/components/ui/MeetingTimezoneSelect';
import {
  DEFAULT_MEETING_ZONE,
  localInZoneToUtcIso,
  nowPlus30InZone,
} from '@/lib/careers/meeting-timezones';

/**
 * Per-project session-scheduling dialog. Posts to the POST_PROJECT
 * scheduling endpoint, which is distinct from the monthly-cycle
 * schedule (that stays alive on the /schedule-session page during
 * parallel mode).
 */
interface Props {
  evaluationId: string;
  projectTitle: string | null;
  onClose: () => void;
  onScheduled: () => void;
}

export default function SchedulePostProjectDialog({
  evaluationId, projectTitle, onClose, onScheduled,
}: Props) {
  const [timezone, setTimezone] = useState<string>(DEFAULT_MEETING_ZONE);
  const [scheduledFor, setScheduledFor] = useState<string>(
    () => nowPlus30InZone(DEFAULT_MEETING_ZONE),
  );
  const [durationMinutes, setDurationMinutes] = useState(45);
  const [topic, setTopic] = useState<string>(
    () => `Project Evaluation${projectTitle ? ` — ${projectTitle}` : ''}`,
  );
  const [agenda, setAgenda] = useState<string>('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    setErr(null);
    if (!scheduledFor) { setErr('Pick a date/time.'); return; }
    setSubmitting(true);
    try {
      await api.post(
        `/api/v1/evaluator/post-project-evaluations/${evaluationId}/schedule`,
        {
          scheduledFor: localInZoneToUtcIso(scheduledFor, timezone),
          durationMinutes,
          timezone,
          topic: topic.trim() || null,
          agenda: agenda.trim() || null,
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
      <div className="w-full max-w-lg rounded-lg bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-3">
          <h3 className="text-base font-semibold text-slate-900">
            Schedule project evaluation
          </h3>
          <button type="button" onClick={onClose}
            className="rounded-full p-1 hover:bg-slate-100" aria-label="Close">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="space-y-3 p-5 text-sm">
          <p className="text-xs text-slate-500">
            Books a session tied to this project's evaluation. A separate
            monthly-cycle scheduler stays available at{' '}
            <span className="font-mono text-[11px]">/schedule-session</span>{' '}
            during parallel mode.
          </p>
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
              onChange={(e) => setDurationMinutes(parseInt(e.target.value || '45', 10))}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          </Field>
          <Field label="Agenda (optional)">
            <textarea value={agenda} onChange={(e) => setAgenda(e.target.value)}
              rows={3} maxLength={2000}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
          </Field>
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
          <button type="button" onClick={submit} disabled={submitting}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60">
            <CalendarPlus className="h-3.5 w-3.5" />
            {submitting ? 'Scheduling…' : 'Schedule'}
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
