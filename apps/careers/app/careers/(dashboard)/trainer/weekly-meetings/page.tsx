'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import api from '@/lib/api';
import WebexHostStartCard from '@/components/meeting/WebexHostStartCard';
import { AlertTriangle, Calendar, CheckCircle2, Clock, Plus, RefreshCw, X, XCircle, AlertOctagon } from 'lucide-react';

type InternRow = { internLifecycleId: string; fullName: string | null; employeeId: string | null };

type Meeting = {
  id: string;
  internLifecycleId: string;
  scheduledFor: string;
  durationMinutes: number;
  timezone: string | null;
  topic: string;
  agenda: string | null;
  zoomMeetingId: string | null;
  zoomJoinUrl: string | null;
  zoomStartUrl: string | null;
  zoomPassword: string | null;
  zoomUpdateFailed: boolean | null;
  zoomLastError: string | null;
  hostUserId: string;
  status: 'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';
  recurrence: string | null;
  recurrenceParentId: string | null;
  trainerNotes: string | null;
};

export default function TrainerWeeklyMeetingsPage() {
  return (
    <Suspense fallback={<div className="mx-auto max-w-6xl p-6"><div className="h-48 animate-pulse rounded-lg bg-slate-100" /></div>}>
      <WeeklyMeetingsInner />
    </Suspense>
  );
}

function WeeklyMeetingsInner() {
  const sp = useSearchParams();
  const prefillIntern = sp?.get('internId') ?? '';
  const wantNew = sp?.get('action') === 'new';

  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [interns, setInterns] = useState<InternRow[]>([]);
  const [status, setStatus] = useState('');
  const [internFilter, setInternFilter] = useState(prefillIntern);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [scheduleOpen, setScheduleOpen] = useState(wantNew);
  const [actionMeeting, setActionMeeting] = useState<Meeting | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (status) params.set('status', status);
      if (internFilter) params.set('internLifecycleId', internFilter);
      const res = await api.get<Meeting[]>(
        `/api/v1/trainer/weekly-meetings?${params.toString()}`,
      );
      setMeetings(res.data ?? []);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally {
      setLoading(false);
    }
  }, [status, internFilter]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    (async () => {
      try {
        const res = await api.get<{ items: InternRow[] }>(
          '/api/v1/trainer/active-interns?pageSize=100',
        );
        setInterns(res.data.items ?? []);
      } catch { setInterns([]); }
    })();
  }, []);

  const sorted = [...meetings].sort((a, b) => a.scheduledFor.localeCompare(b.scheduledFor));
  const upcoming = sorted.filter((m) => m.status === 'SCHEDULED' && new Date(m.scheduledFor) > new Date());
  const past = sorted.filter((m) => !upcoming.includes(m)).reverse();

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <div className="flex items-start justify-between">
        <div>
          <p className="text-xs text-slate-500">
            <Link href="/careers/trainer" className="hover:text-slate-700">← Trainer dashboard</Link>
          </p>
          <h1 className="mt-1 text-xl font-semibold text-slate-900">Weekly Meetings</h1>
          <p className="text-xs text-slate-500">
            Doc §8 weekly support cadence. Schedule, complete with notes, mark missed, or cancel.
          </p>
        </div>
        <button type="button" onClick={() => setScheduleOpen(true)}
          className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-2 text-sm font-semibold text-white hover:bg-brand-800">
          <Plus className="h-4 w-4" /> Schedule meeting
        </button>
      </div>

      <div className="flex flex-wrap items-center gap-2 rounded-lg border border-slate-200 bg-white p-3">
        <select value={internFilter} onChange={(e) => setInternFilter(e.target.value)}
          className="rounded-md border border-slate-200 px-2 py-1.5 text-sm">
          <option value="">All interns</option>
          {interns.map((i) => <option key={i.internLifecycleId} value={i.internLifecycleId}>{i.fullName}</option>)}
        </select>
        <select value={status} onChange={(e) => setStatus(e.target.value)}
          className="rounded-md border border-slate-200 px-2 py-1.5 text-sm">
          <option value="">All statuses</option>
          <option value="SCHEDULED">Scheduled</option>
          <option value="COMPLETED">Completed</option>
          <option value="NO_SHOW">Missed</option>
          <option value="CANCELLED">Cancelled</option>
        </select>
      </div>

      {err && <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">{err}</p>}

      <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <h3 className="mb-2 text-sm font-semibold text-slate-900">
          Upcoming ({upcoming.length})
        </h3>
        {loading ? (
          <div className="h-24 animate-pulse rounded bg-slate-100" />
        ) : upcoming.length === 0 ? (
          <p className="text-xs text-slate-500">No upcoming meetings.</p>
        ) : (
          <ul className="divide-y divide-slate-100">
            {upcoming.map((m) => (
              <MeetingRow key={m.id} m={m} interns={interns} onAction={() => setActionMeeting(m)} />
            ))}
          </ul>
        )}
      </section>

      <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <h3 className="mb-2 text-sm font-semibold text-slate-900">
          Past meetings ({past.length})
        </h3>
        {loading ? (
          <div className="h-24 animate-pulse rounded bg-slate-100" />
        ) : past.length === 0 ? (
          <p className="text-xs text-slate-500">No past meetings.</p>
        ) : (
          <ul className="divide-y divide-slate-100">
            {past.slice(0, 25).map((m) => (
              <MeetingRow key={m.id} m={m} interns={interns} onAction={() => setActionMeeting(m)} />
            ))}
          </ul>
        )}
      </section>

      {scheduleOpen && (
        <ScheduleModal
          prefillIntern={prefillIntern} interns={interns}
          onClose={() => setScheduleOpen(false)}
          onCreated={() => { setScheduleOpen(false); void load(); }}
        />
      )}
      {actionMeeting && (
        <MeetingActionModal
          meeting={actionMeeting} interns={interns}
          onClose={() => setActionMeeting(null)}
          onChanged={() => { setActionMeeting(null); void load(); }}
        />
      )}
    </div>
  );
}

function MeetingRow({ m, interns, onAction }: {
  m: Meeting; interns: InternRow[]; onAction: () => void;
}) {
  const internName = interns.find((i) => i.internLifecycleId === m.internLifecycleId)?.fullName ?? '—';
  const when = new Date(m.scheduledFor);
  return (
    <li className="flex items-center justify-between px-2 py-2">
      <div>
        <p className="text-sm font-medium text-slate-900">
          {m.topic}
          <span className="ml-2 text-[10px] font-normal text-slate-500">
            · {internName}
          </span>
        </p>
        <p className="text-[11px] text-slate-500">
          <Clock className="mr-1 inline h-3 w-3" />
          {when.toLocaleString()} · {m.durationMinutes} min
          {m.recurrence === 'WEEKLY' && <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5">recurring</span>}
        </p>
      </div>
      <div className="flex items-center gap-2">
        <StatusBadge status={m.status} />
        <button type="button" onClick={onAction}
          className="rounded-md border border-slate-200 px-2 py-1 text-[11px] font-medium text-slate-700">
          Open
        </button>
      </div>
    </li>
  );
}

function StatusBadge({ status }: { status: Meeting['status'] }) {
  const styles: Record<Meeting['status'], string> = {
    SCHEDULED: 'bg-slate-100 text-slate-700',
    COMPLETED: 'bg-green-100 text-green-800',
    CANCELLED: 'bg-slate-200 text-slate-700',
    NO_SHOW: 'bg-red-100 text-red-800',
  };
  return (
    <span className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${styles[status]}`}>
      {status.replace('_', ' ')}
    </span>
  );
}

function ScheduleModal({ prefillIntern, interns, onClose, onCreated }: {
  prefillIntern: string; interns: InternRow[];
  onClose: () => void; onCreated: () => void;
}) {
  const [internLifecycleId, setInternLifecycleId] = useState(prefillIntern);
  const [datetime, setDatetime] = useState('');
  const [duration, setDuration] = useState(30);
  const [topic, setTopic] = useState('');
  const [agenda, setAgenda] = useState('');
  const [recurrence, setRecurrence] = useState<'NONE' | 'WEEKLY'>('NONE');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    if (!internLifecycleId || !datetime || !topic.trim()) {
      setErr('Intern, date/time, and topic are required.');
      return;
    }
    setSubmitting(true);
    try {
      await api.post('/api/v1/trainer/weekly-meetings', {
        internLifecycleId,
        scheduledFor: new Date(datetime).toISOString(),
        durationMinutes: duration,
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        topic: topic.trim(),
        agenda: agenda.trim() || null,
        recurrence: recurrence === 'WEEKLY' ? 'WEEKLY' : null,
      });
      onCreated();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal title="Schedule weekly meeting" onClose={onClose}>
      <Field label="Intern*">
        <select value={internLifecycleId} onChange={(e) => setInternLifecycleId(e.target.value)}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm">
          <option value="">— pick —</option>
          {interns.map((i) => <option key={i.internLifecycleId} value={i.internLifecycleId}>{i.fullName}</option>)}
        </select>
      </Field>
      <Field label="Date + time*">
        <input type="datetime-local" value={datetime} onChange={(e) => setDatetime(e.target.value)}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
      </Field>
      <Field label="Duration (minutes)">
        <select value={duration} onChange={(e) => setDuration(Number(e.target.value))}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm">
          <option value={15}>15</option><option value={30}>30</option>
          <option value={45}>45</option><option value={60}>60</option>
        </select>
      </Field>
      <Field label="Topic*">
        <input value={topic} onChange={(e) => setTopic(e.target.value)} maxLength={200}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
      </Field>
      <Field label="Agenda (optional)">
        <textarea value={agenda} onChange={(e) => setAgenda(e.target.value)} rows={3} maxLength={2000}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
      </Field>
      <Field label="Recurrence">
        <select value={recurrence} onChange={(e) => setRecurrence(e.target.value as 'NONE' | 'WEEKLY')}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm">
          <option value="NONE">One-time</option>
          <option value="WEEKLY">Weekly (12 occurrences)</option>
        </select>
      </Field>
      {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
      <div className="flex justify-end gap-2">
        <button type="button" onClick={onClose} className="rounded-md border border-slate-200 px-3 py-1.5 text-sm">Cancel</button>
        <button type="button" onClick={submit} disabled={submitting}
          className="rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:bg-slate-300">
          {submitting ? 'Scheduling…' : 'Schedule'}
        </button>
      </div>
    </Modal>
  );
}

function MeetingActionModal({ meeting, interns, onClose, onChanged }: {
  meeting: Meeting; interns: InternRow[];
  onClose: () => void; onChanged: () => void;
}) {
  const [action, setAction] = useState<'view' | 'complete' | 'reschedule' | 'mark-missed' | 'cancel'>('view');
  const internName = interns.find((i) => i.internLifecycleId === meeting.internLifecycleId)?.fullName ?? '—';

  return (
    <Modal title={`Meeting · ${meeting.topic}`} onClose={onClose}>
      <div className="space-y-2 text-sm">
        <Row k="Intern" v={internName} />
        <Row k="When" v={new Date(meeting.scheduledFor).toLocaleString()} />
        <Row k="Duration" v={`${meeting.durationMinutes} min`} />
        <Row k="Status" v={meeting.status} />
        {meeting.zoomMeetingId && (
          <div className="pt-1">
            <WebexHostStartCard
              providerMeetingId={meeting.zoomMeetingId}
              startUrl={meeting.zoomStartUrl}
            />
          </div>
        )}
        <ZoomRegenerateBanner meeting={meeting} onRegenerated={onChanged} />
        {meeting.trainerNotes && (
          <details className="rounded-md border border-slate-200 bg-slate-50 p-2">
            <summary className="cursor-pointer text-xs font-semibold">Trainer notes</summary>
            <pre className="mt-2 whitespace-pre-wrap font-mono text-[11px] text-slate-700">{meeting.trainerNotes}</pre>
          </details>
        )}
      </div>

      {meeting.status === 'SCHEDULED' && action === 'view' && (
        <div className="flex flex-wrap gap-2 border-t border-slate-100 pt-3">
          <button type="button" onClick={() => setAction('complete')}
            className="inline-flex items-center gap-1 rounded-md bg-green-700 px-3 py-1.5 text-xs font-semibold text-white">
            <CheckCircle2 className="h-3.5 w-3.5" /> Complete
          </button>
          <button type="button" onClick={() => setAction('reschedule')}
            className="inline-flex items-center gap-1 rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700">
            <Calendar className="h-3.5 w-3.5" /> Reschedule
          </button>
          <button type="button" onClick={() => setAction('mark-missed')}
            className="inline-flex items-center gap-1 rounded-md border border-amber-300 px-3 py-1.5 text-xs font-medium text-amber-800">
            <AlertOctagon className="h-3.5 w-3.5" /> Mark missed
          </button>
          <button type="button" onClick={() => setAction('cancel')}
            className="inline-flex items-center gap-1 rounded-md border border-red-300 px-3 py-1.5 text-xs font-medium text-red-800">
            <XCircle className="h-3.5 w-3.5" /> Cancel
          </button>
        </div>
      )}

      {action === 'complete' && (
        <CompleteForm meetingId={meeting.id} onDone={onChanged} onCancel={() => setAction('view')} />
      )}
      {action === 'reschedule' && (
        <RescheduleForm meeting={meeting} onDone={onChanged} onCancel={() => setAction('view')} />
      )}
      {action === 'mark-missed' && (
        <MarkMissedForm meetingId={meeting.id} onDone={onChanged} onCancel={() => setAction('view')} />
      )}
      {action === 'cancel' && (
        <CancelForm meetingId={meeting.id} onDone={onChanged} onCancel={() => setAction('view')} />
      )}
    </Modal>
  );
}

function CompleteForm({ meetingId, onDone, onCancel }: {
  meetingId: string; onDone: () => void; onCancel: () => void;
}) {
  const [attendance, setAttendance] = useState('TRAINER+INTERN');
  const [notes, setNotes] = useState('');
  const [actionItems, setActionItems] = useState('');
  const [blockers, setBlockers] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function submit() {
    if (notes.trim().length < 50) { setErr('Notes must be at least 50 chars'); return; }
    setBusy(true);
    try {
      await api.post(`/api/v1/trainer/weekly-meetings/${meetingId}/complete`, {
        attendance, notes, actionItems: actionItems.trim() || null, blockers: blockers.trim() || null,
      });
      onDone();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally { setBusy(false); }
  }
  return (
    <div className="space-y-3 border-t border-slate-100 pt-3">
      <Field label="Attendance">
        <select value={attendance} onChange={(e) => setAttendance(e.target.value)}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm">
          <option value="TRAINER+INTERN">Trainer + Intern</option>
          <option value="TRAINER_ONLY">Trainer only</option>
          <option value="OTHER">Other (capture in notes)</option>
        </select>
      </Field>
      <Field label={`Notes (≥ 50 chars — ${notes.length})*`}>
        <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={4} maxLength={5000}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
      </Field>
      <Field label="Action items (optional)">
        <textarea value={actionItems} onChange={(e) => setActionItems(e.target.value)} rows={2} maxLength={2000}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
      </Field>
      <Field label="Blockers (optional)">
        <textarea value={blockers} onChange={(e) => setBlockers(e.target.value)} rows={2} maxLength={2000}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
      </Field>
      {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
      <FormButtons busy={busy} onCancel={onCancel} onSubmit={submit} label="Mark completed" tone="emerald" />
    </div>
  );
}

function RescheduleForm({ meeting, onDone, onCancel }: {
  meeting: Meeting; onDone: () => void; onCancel: () => void;
}) {
  const [datetime, setDatetime] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function submit() {
    if (!datetime) { setErr('Pick a new date/time'); return; }
    if (reason.trim().length < 20) { setErr('Reason must be at least 20 chars'); return; }
    setBusy(true);
    try {
      await api.patch(`/api/v1/trainer/weekly-meetings/${meeting.id}`, {
        newScheduledFor: new Date(datetime).toISOString(),
        newDurationMinutes: meeting.durationMinutes,
        reason: reason.trim(),
      });
      onDone();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally { setBusy(false); }
  }
  return (
    <div className="space-y-3 border-t border-slate-100 pt-3">
      <Field label="New date + time*">
        <input type="datetime-local" value={datetime} onChange={(e) => setDatetime(e.target.value)}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
      </Field>
      <Field label={`Reason (≥ 20 chars — ${reason.length})*`}>
        <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} maxLength={1000}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
      </Field>
      {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
      <FormButtons busy={busy} onCancel={onCancel} onSubmit={submit} label="Reschedule" tone="teal" />
    </div>
  );
}

function MarkMissedForm({ meetingId, onDone, onCancel }: {
  meetingId: string; onDone: () => void; onCancel: () => void;
}) {
  const [missedBy, setMissedBy] = useState<'INTERN' | 'TRAINER' | 'BOTH'>('INTERN');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function submit() {
    if (reason.trim().length < 20) { setErr('Reason must be at least 20 chars'); return; }
    setBusy(true);
    try {
      await api.post(`/api/v1/trainer/weekly-meetings/${meetingId}/mark-missed`, {
        missedBy, reason: reason.trim(),
      });
      onDone();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally { setBusy(false); }
  }
  return (
    <div className="space-y-3 border-t border-slate-100 pt-3">
      <Field label="Missed by">
        <select value={missedBy} onChange={(e) => setMissedBy(e.target.value as typeof missedBy)}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm">
          <option value="INTERN">Intern</option><option value="TRAINER">Trainer</option><option value="BOTH">Both</option>
        </select>
      </Field>
      <Field label={`Reason (≥ 20 chars — ${reason.length})*`}>
        <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} maxLength={1000}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
      </Field>
      {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
      <FormButtons busy={busy} onCancel={onCancel} onSubmit={submit} label="Mark missed" tone="amber" />
    </div>
  );
}

function CancelForm({ meetingId, onDone, onCancel }: {
  meetingId: string; onDone: () => void; onCancel: () => void;
}) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function submit() {
    setBusy(true);
    try {
      await api.post(`/api/v1/trainer/weekly-meetings/${meetingId}/cancel`, {
        reason: reason.trim() || null,
      });
      onDone();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally { setBusy(false); }
  }
  return (
    <div className="space-y-3 border-t border-slate-100 pt-3">
      <Field label="Reason (optional)">
        <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} maxLength={1000}
          className="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm" />
      </Field>
      {err && <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">{err}</p>}
      <FormButtons busy={busy} onCancel={onCancel} onSubmit={submit} label="Cancel meeting" tone="rose" />
    </div>
  );
}

function FormButtons({ busy, onCancel, onSubmit, label, tone }: {
  busy: boolean; onCancel: () => void; onSubmit: () => void; label: string;
  tone: 'teal' | 'emerald' | 'rose' | 'amber';
}) {
  const cls: Record<string, string> = {
    teal: 'bg-brand-700 hover:bg-brand-800',
    emerald: 'bg-green-700 hover:bg-green-800',
    rose: 'bg-red-700 hover:bg-red-800',
    amber: 'bg-amber-600 hover:bg-amber-700',
  };
  return (
    <div className="flex justify-end gap-2">
      <button type="button" onClick={onCancel} className="rounded-md border border-slate-200 px-3 py-1.5 text-sm">
        Back
      </button>
      <button type="button" onClick={onSubmit} disabled={busy}
        className={`rounded-md px-4 py-1.5 text-sm font-semibold text-white disabled:bg-slate-300 ${cls[tone]}`}>
        {busy ? 'Submitting…' : label}
      </button>
    </div>
  );
}

/**
 * Surfaces a persistent Zoom failure on the meeting row and offers a
 * one-click Regenerate. Triggers when:
 *   - the most recent Zoom PATCH on reschedule failed (zoomUpdateFailed=true), or
 *   - the row is SCHEDULED but has no Zoom meeting id (initial create failed).
 * Hidden for completed/cancelled meetings and for recurring series, since
 * the backend blocks regenerate on those for desync reasons.
 */
function ZoomRegenerateBanner({ meeting, onRegenerated }: {
  meeting: Meeting; onRegenerated: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const isSeries = meeting.recurrence === 'WEEKLY' || meeting.recurrenceParentId != null;
  const updateFailed = meeting.zoomUpdateFailed === true;
  const missingZoom = meeting.status === 'SCHEDULED' && meeting.zoomMeetingId == null;
  if (isSeries || (!updateFailed && !missingZoom)) return null;

  async function regenerate() {
    setBusy(true); setErr(null);
    try {
      await api.post(`/api/v1/trainer/weekly-meetings/${meeting.id}/zoom/regenerate`);
      onRegenerated();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Regenerate failed');
    } finally { setBusy(false); }
  }

  const title = updateFailed
    ? 'Meeting update failed on reschedule'
    : 'Meeting link is missing';
  const body = updateFailed
    ? 'The stored meeting may now disagree with the new time. Regenerate to recreate it at the current schedule.'
    : 'The original meeting create call failed when this was scheduled. Regenerate to attach a fresh link.';

  return (
    <div className="rounded-md border border-amber-200 bg-amber-50 p-2.5 text-xs text-amber-900">
      <div className="flex items-start gap-2">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" strokeWidth={2} />
        <div className="flex-1">
          <p className="font-semibold">{title}</p>
          <p className="mt-0.5">{body}</p>
          {meeting.zoomLastError && (
            <p className="mt-1 break-all font-mono text-[10px] opacity-80">
              {meeting.zoomLastError}
            </p>
          )}
          {err && (
            <p className="mt-1 rounded-sm bg-white/60 p-1.5 font-mono text-[10px]">{err}</p>
          )}
        </div>
        <button type="button" onClick={regenerate} disabled={busy}
          className="inline-flex items-center gap-1 rounded-md border border-amber-400 px-2 py-1 text-[11px] font-semibold text-amber-900 hover:bg-white/60 disabled:opacity-60">
          <RefreshCw className={'h-3 w-3 ' + (busy ? 'animate-spin' : '')} />
          {busy ? 'Regenerating…' : 'Regenerate'}
        </button>
      </div>
    </div>
  );
}

function Modal({ title, children, onClose }: {
  title: string; children: React.ReactNode; onClose: () => void;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="flex max-h-[90vh] w-full max-w-lg flex-col rounded-lg bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-3">
          <h3 className="text-base font-semibold text-slate-900">{title}</h3>
          <button type="button" onClick={onClose} className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="flex-1 space-y-3 overflow-y-auto p-5">
          {children}
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

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex justify-between border-b border-slate-100 py-1 text-sm">
      <span className="text-slate-500">{k}</span>
      <span className="text-right text-slate-800">{v}</span>
    </div>
  );
}
