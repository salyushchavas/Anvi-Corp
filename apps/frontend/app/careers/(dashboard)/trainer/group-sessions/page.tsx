'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { AlertOctagon, CheckCircle2, Copy, Plus, RefreshCw, Users, Video, X, XCircle } from 'lucide-react';
import api from '@/lib/careers/api';
import ConfirmDialog from '@/components/ConfirmDialog';
import MeetingTimezoneSelect from '@/components/ui/MeetingTimezoneSelect';
import { formatInZone } from '@/lib/careers/format-interview-time';
import {
  DEFAULT_MEETING_ZONE,
  localInZoneToUtcIso,
  nowPlus30InZone,
} from '@/lib/careers/meeting-timezones';
import { useTrainerDashboard } from '@/components/trainer/TrainerDashboardContext';

type InternRow = {
  internLifecycleId: string;
  fullName: string | null;
  employeeId: string | null;
};

type ParticipantRef = {
  internUserId: string;
  fullName: string | null;
  email: string | null;
};

type GroupSession = {
  id: string;
  topic: string;
  description: string | null;
  scheduledAt: string;
  meetingTimezone: string;
  durationMinutes: number;
  status: 'SCHEDULED' | 'COMPLETED' | 'CANCELLED';
  zoomMeetingId: string | null;
  zoomJoinUrl: string | null;
  zoomStartUrl: string | null;
  zoomPassword: string | null;
  cancelledAt: string | null;
  participants: ParticipantRef[];
  createdAt: string;
  updatedAt: string;
};

export default function TrainerGroupSessionsPage() {
  const { selectedMonth, isCurrentMonth } = useTrainerDashboard();
  const [sessions, setSessions] = useState<GroupSession[]>([]);
  const [interns, setInterns] = useState<InternRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      // Sticky month: past-month bounds scheduled_at to that month's
      // window; current-month leaves params empty (byte-identical to
      // the pre-scoping fetch). Mirrors weekly-meetings.
      if (!isCurrentMonth) {
        const [ys, ms] = selectedMonth.split('-');
        const y = Number(ys), m = Number(ms);
        if (!Number.isNaN(y) && !Number.isNaN(m)) {
          const start = new Date(Date.UTC(y, m - 1, 1));
          const end = new Date(Date.UTC(y, m, 0));
          params.set('fromDate', start.toISOString().slice(0, 10));
          params.set('toDate', end.toISOString().slice(0, 10));
        }
      }
      const qs = params.toString();
      const url = qs
        ? `/api/v1/trainer/group-sessions?${qs}`
        : '/api/v1/trainer/group-sessions';
      const res = await api.get<GroupSession[]>(url);
      setSessions(res.data ?? []);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [isCurrentMonth, selectedMonth]);
  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    (async () => {
      try {
        const res = await api.get<{ items: InternRow[] }>(
          '/api/v1/trainer/active-interns?pageSize=200',
        );
        setInterns(res.data.items ?? []);
      } catch { setInterns([]); }
    })();
  }, []);

  const now = Date.now();
  const upcoming = sessions
    .filter((s) => s.status === 'SCHEDULED' && new Date(s.scheduledAt).getTime() >= now)
    .sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt));
  const past = sessions
    .filter((s) => !upcoming.includes(s))
    .sort((a, b) => b.scheduledAt.localeCompare(a.scheduledAt));

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-xs text-slate-500">
            <Link href="/careers/trainer" className="hover:text-slate-700">
              ← Trainer dashboard
            </Link>
          </p>
          <h1 className="mt-1 text-xl font-semibold text-slate-900">Group Sessions</h1>
          <p className="text-xs text-slate-500">
            Schedule ONE Zoom meeting for multiple interns. Every picked intern
            is emailed the join link.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button type="button" onClick={() => void load()}
            className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-2 text-xs text-slate-700 hover:bg-slate-50">
            <RefreshCw className="h-3.5 w-3.5" /> Refresh
          </button>
          <button type="button" onClick={() => setCreateOpen(true)}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-2 text-sm font-semibold text-white hover:bg-brand-800">
            <Plus className="h-4 w-4" /> New group session
          </button>
        </div>
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {err}
        </p>
      )}

      {loading ? (
        <div className="h-32 animate-pulse rounded-lg bg-slate-100" />
      ) : (
        <>
          <Section title={`Upcoming (${upcoming.length})`} rows={upcoming}
            onCancelled={load} emptyText="No upcoming group sessions." />
          <Section title={`Past & cancelled (${past.length})`} rows={past}
            onCancelled={load} emptyText="No past sessions yet." past />
        </>
      )}

      {createOpen && (
        <CreateDialog
          interns={interns}
          onClose={() => setCreateOpen(false)}
          onCreated={() => { setCreateOpen(false); void load(); }}
        />
      )}
    </div>
  );
}

function Section({ title, rows, emptyText, onCancelled, past }: {
  title: string;
  rows: GroupSession[];
  emptyText: string;
  onCancelled: () => void;
  past?: boolean;
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <h2 className="text-sm font-semibold text-slate-900">{title}</h2>
      {rows.length === 0 ? (
        <p className="mt-2 text-xs text-slate-500">{emptyText}</p>
      ) : (
        <ul className="mt-3 space-y-2">
          {rows.map((s) => (
            <SessionRow key={s.id} s={s} past={past} onCancelled={onCancelled} />
          ))}
        </ul>
      )}
    </section>
  );
}

function SessionRow({ s, past, onCancelled }: {
  s: GroupSession;
  past?: boolean;
  onCancelled: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [rowErr, setRowErr] = useState<string | null>(null);
  const [cancelConfirmOpen, setCancelConfirmOpen] = useState(false);
  async function cancel() {
    setBusy(true);
    setRowErr(null);
    try {
      await api.post(`/api/v1/trainer/group-sessions/${s.id}/cancel`, {});
      setCancelConfirmOpen(false);
      onCancelled();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setRowErr(ax.response?.data?.error ?? ax.message ?? 'Cancel failed');
    } finally {
      setBusy(false);
    }
  }

  const cancelled = s.status === 'CANCELLED';

  return (
    <li className="rounded-md border border-slate-200 bg-white p-3 text-sm">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <p className="truncate font-semibold text-slate-900">{s.topic}</p>
            <StatusPill status={s.status} />
          </div>
          <p className="mt-0.5 text-xs text-slate-500">
            {formatInZone(s.scheduledAt, s.meetingTimezone)} · {s.durationMinutes} min
          </p>
          {s.description && (
            <p className="mt-1 whitespace-pre-wrap text-xs text-slate-600">{s.description}</p>
          )}
          <div className="mt-2 flex items-center gap-1 text-xs text-slate-600">
            <Users className="h-3.5 w-3.5" />
            <span>
              {s.participants.length} intern{s.participants.length === 1 ? '' : 's'}
              {s.participants.length > 0 && (
                <>
                  {' '}—{' '}
                  <span className="text-slate-500">
                    {s.participants
                      .map((p) => p.fullName ?? '(unnamed)')
                      .slice(0, 3)
                      .join(', ')}
                    {s.participants.length > 3
                      ? ` +${s.participants.length - 3} more`
                      : ''}
                  </span>
                </>
              )}
            </span>
          </div>
        </div>
        <div className="flex flex-col items-end gap-1 whitespace-nowrap">
          {s.zoomJoinUrl && !cancelled && (
            <a href={s.zoomJoinUrl} target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-1 rounded-md border border-brand-200 bg-brand-50 px-2 py-1 text-xs font-semibold text-brand-800 hover:bg-brand-100">
              <Video className="h-3.5 w-3.5" /> Join
            </a>
          )}
          {s.zoomStartUrl && !cancelled && (
            <a href={s.zoomStartUrl} target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-2 py-1 text-xs font-semibold text-white hover:bg-brand-800">
              Host
            </a>
          )}
          {s.zoomJoinUrl && (
            <button type="button"
              onClick={() => { void navigator.clipboard.writeText(s.zoomJoinUrl ?? ''); }}
              className="inline-flex items-center gap-1 text-[11px] text-slate-500 hover:text-slate-700">
              <Copy className="h-3 w-3" /> Copy join link
            </button>
          )}
          {!past && !cancelled && (
            <button type="button" onClick={() => setCancelConfirmOpen(true)} disabled={busy}
              className="inline-flex items-center gap-1 rounded-md border border-red-200 bg-white px-2 py-1 text-[11px] text-red-700 hover:bg-red-50 disabled:opacity-60">
              <XCircle className="h-3 w-3" /> {busy ? 'Cancelling…' : 'Cancel'}
            </button>
          )}
        </div>
      </div>
      {rowErr && (
        <p className="mt-2 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-700">
          {rowErr}
        </p>
      )}
      {/* Themed danger confirm — cancelling notifies every
          participant, so a native browser confirm was too easy to
          fire by accident. */}
      <ConfirmDialog
        open={cancelConfirmOpen}
        onClose={() => setCancelConfirmOpen(false)}
        onConfirm={cancel}
        title={`Cancel "${s.topic}"?`}
        description="Every participant will be notified. The session slot is released."
        confirmLabel="Cancel session"
        variant="danger"
      />
    </li>
  );
}

function StatusPill({ status }: { status: GroupSession['status'] }) {
  const map: Record<string, { label: string; cls: string; icon: React.ReactNode }> = {
    SCHEDULED: {
      label: 'Scheduled',
      cls: 'bg-brand-50 text-brand-800 border-brand-200',
      icon: <CheckCircle2 className="h-3 w-3" />,
    },
    COMPLETED: {
      label: 'Completed',
      cls: 'bg-emerald-50 text-emerald-800 border-emerald-200',
      icon: <CheckCircle2 className="h-3 w-3" />,
    },
    CANCELLED: {
      label: 'Cancelled',
      cls: 'bg-slate-100 text-slate-600 border-slate-200',
      icon: <AlertOctagon className="h-3 w-3" />,
    },
  };
  const m = map[status];
  return (
    <span className={`inline-flex items-center gap-1 rounded-full border px-1.5 py-0.5 text-[10px] font-semibold ${m.cls}`}>
      {m.icon} {m.label}
    </span>
  );
}

function CreateDialog({ interns, onClose, onCreated }: {
  interns: InternRow[];
  onClose: () => void;
  onCreated: () => void;
}) {
  const [topic, setTopic] = useState('');
  const [description, setDescription] = useState('');
  const [timezone, setTimezone] = useState<string>(DEFAULT_MEETING_ZONE);
  const [scheduledFor, setScheduledFor] = useState<string>(
    () => nowPlus30InZone(DEFAULT_MEETING_ZONE),
  );
  const [durationMinutes, setDurationMinutes] = useState(60);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [filter, setFilter] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const filtered = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return interns;
    return interns.filter((i) => {
      const n = (i.fullName ?? '').toLowerCase();
      const e = (i.employeeId ?? '').toLowerCase();
      return n.includes(q) || e.includes(q);
    });
  }, [interns, filter]);

  function toggle(id: string) {
    setPicked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }
  function toggleAll() {
    if (picked.size === filtered.length) {
      setPicked(new Set());
    } else {
      setPicked(new Set(filtered.map((i) => i.internLifecycleId)));
    }
  }

  async function submit() {
    setErr(null);
    if (!topic.trim()) { setErr('Topic is required.'); return; }
    if (picked.size === 0) { setErr('Pick at least one intern.'); return; }
    setBusy(true);
    try {
      await api.post('/api/v1/trainer/group-sessions', {
        topic: topic.trim(),
        description: description.trim() || null,
        scheduledAt: localInZoneToUtcIso(scheduledFor, timezone),
        meetingTimezone: timezone,
        durationMinutes,
        participantLifecycleIds: Array.from(picked),
      });
      onCreated();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Create failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="flex max-h-[90vh] w-full max-w-3xl flex-col overflow-hidden rounded-lg bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-200 p-4">
          <h3 className="text-base font-semibold text-slate-900">New group session</h3>
          <button type="button" onClick={onClose}
            className="rounded-full p-1 hover:bg-slate-100">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="grid grid-cols-1 gap-4 overflow-y-auto p-5 md:grid-cols-2">
          <div className="space-y-3">
            <Field label="Topic" required>
              <input type="text" value={topic} maxLength={200}
                onChange={(e) => setTopic(e.target.value)}
                placeholder="e.g. Week-2 sprint planning"
                className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
            </Field>
            <Field label="Description">
              <textarea value={description} rows={3} maxLength={5000}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="Agenda + what interns should prepare"
                className="w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm" />
            </Field>
            <Field label="Date & time" required>
              <input type="datetime-local" value={scheduledFor}
                onChange={(e) => setScheduledFor(e.target.value)}
                className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
            </Field>
            <Field label="Timezone" required>
              <MeetingTimezoneSelect value={timezone} onChange={setTimezone} />
            </Field>
            <Field label="Duration (minutes)">
              <input type="number" min={15} max={240} step={15}
                value={durationMinutes}
                onChange={(e) => setDurationMinutes(parseInt(e.target.value || '60', 10))}
                className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
            </Field>
          </div>
          <div className="flex min-h-0 flex-col">
            <div className="flex items-center justify-between">
              <label className="text-xs font-semibold text-slate-800">
                Participants {picked.size > 0 && (
                  <span className="ml-1 rounded-full bg-brand-50 px-1.5 py-0.5 text-[10px] text-brand-800">
                    {picked.size}
                  </span>
                )}
              </label>
              <button type="button" onClick={toggleAll}
                className="text-[11px] font-medium text-brand-700 hover:underline">
                {picked.size === filtered.length ? 'Clear all' : 'Select all'}
              </button>
            </div>
            <input type="search" value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder="Filter by name or Employee ID"
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm" />
            <div className="mt-2 flex-1 overflow-y-auto rounded-md border border-slate-200 bg-slate-50">
              {filtered.length === 0 ? (
                <p className="p-3 text-xs text-slate-500">No active interns.</p>
              ) : (
                <ul className="divide-y divide-slate-200">
                  {filtered.map((i) => {
                    const on = picked.has(i.internLifecycleId);
                    return (
                      <li key={i.internLifecycleId}>
                        <label className="flex cursor-pointer items-center gap-2 px-3 py-2 text-sm hover:bg-white">
                          <input type="checkbox" checked={on}
                            onChange={() => toggle(i.internLifecycleId)} />
                          <span className="flex-1 truncate">
                            {i.fullName ?? '(unnamed)'}
                            {i.employeeId && (
                              <span className="ml-2 text-xs text-slate-500">
                                {i.employeeId}
                              </span>
                            )}
                          </span>
                        </label>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
          </div>
        </div>
        {err && (
          <p className="mx-5 mb-3 rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-700">
            {err}
          </p>
        )}
        <div className="flex items-center justify-end gap-2 border-t border-slate-200 p-4">
          <button type="button" onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm hover:bg-slate-50">
            Cancel
          </button>
          <button type="button" onClick={submit} disabled={busy}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60">
            {busy ? 'Creating…' : 'Schedule + notify interns'}
          </button>
        </div>
      </div>
    </div>
  );
}

function Field({ label, required, children }: {
  label: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-slate-800">
        {label}{required && <span className="text-red-500"> *</span>}
      </label>
      {children}
    </div>
  );
}
