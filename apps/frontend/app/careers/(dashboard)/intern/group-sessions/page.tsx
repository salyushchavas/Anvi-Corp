'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertOctagon, CheckCircle2, RefreshCw, Users, Video } from 'lucide-react';
import api from '@/lib/careers/api';
import InternPageShell from '@/components/intern/InternPageShell';
import { formatInZone } from '@/lib/careers/format-interview-time';
import type { InternGroupSession } from '@/components/intern/UpcomingGroupSessionsCard';

export default function InternGroupSessionsPage() {
  const [rows, setRows] = useState<InternGroupSession[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<InternGroupSession[]>('/api/v1/intern/group-sessions');
      setRows(res.data ?? []);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const now = Date.now();
  const upcoming = rows
    .filter((s) => s.status === 'SCHEDULED'
      && (!s.scheduledAt || new Date(s.scheduledAt).getTime() >= now))
    .sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt));
  const past = rows
    .filter((s) => !upcoming.includes(s))
    .sort((a, b) => b.scheduledAt.localeCompare(a.scheduledAt));

  return (
    <InternPageShell
      title="Group Sessions"
      subtitle="Trainer-hosted Zoom sessions you've been invited to."
    >
      <div className="mb-3 flex items-center justify-end">
        <button type="button" onClick={() => void load()}
          className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1.5 text-xs text-slate-700 hover:bg-slate-50">
          <RefreshCw className="h-3.5 w-3.5" /> Refresh
        </button>
      </div>
      {err && (
        <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {err}
        </p>
      )}
      {loading ? (
        <div className="h-32 animate-pulse rounded-lg bg-slate-100" />
      ) : (
        <>
          <Group title={`Upcoming (${upcoming.length})`} rows={upcoming}
            emptyText="No upcoming group sessions." />
          <Group title={`Past & cancelled (${past.length})`} rows={past}
            emptyText="No past sessions yet." past />
        </>
      )}
    </InternPageShell>
  );
}

function Group({ title, rows, emptyText, past }: {
  title: string;
  rows: InternGroupSession[];
  emptyText: string;
  past?: boolean;
}) {
  return (
    <section className="mb-4 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <h2 className="text-sm font-semibold text-slate-900">{title}</h2>
      {rows.length === 0 ? (
        <p className="mt-2 text-xs text-slate-500">{emptyText}</p>
      ) : (
        <ul className="mt-3 space-y-2">
          {rows.map((s) => <Row key={s.id} s={s} past={past} />)}
        </ul>
      )}
    </section>
  );
}

function Row({ s, past }: { s: InternGroupSession; past?: boolean }) {
  const cancelled = s.status === 'CANCELLED';
  return (
    <li className="rounded-md border border-slate-200 bg-white p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <p className="truncate text-sm font-semibold text-slate-900">{s.topic}</p>
            <StatusPill status={s.status} />
          </div>
          <p className="mt-0.5 text-xs text-slate-500">
            {formatInZone(s.scheduledAt, s.meetingTimezone)}
            {s.durationMinutes ? ` · ${s.durationMinutes} min` : ''}
          </p>
          {s.hostName && (
            <p className="mt-0.5 text-xs text-slate-600">Hosted by {s.hostName}</p>
          )}
          <p className="mt-0.5 inline-flex items-center gap-1 text-[11px] text-slate-500">
            <Users className="h-3 w-3" /> {s.participantCount} intern
            {s.participantCount === 1 ? '' : 's'} invited
          </p>
          {s.description && (
            <p className="mt-1 whitespace-pre-wrap text-xs text-slate-700">{s.description}</p>
          )}
        </div>
        <div className="flex flex-col items-end gap-1 whitespace-nowrap">
          {s.zoomJoinUrl && !cancelled && !past && (
            <a href={s.zoomJoinUrl} target="_blank" rel="noreferrer noopener"
              className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800">
              <Video className="h-3.5 w-3.5" /> Join
            </a>
          )}
          {s.zoomPassword && !cancelled && (
            <p className="text-[10px] text-slate-500">Passcode: {s.zoomPassword}</p>
          )}
        </div>
      </div>
    </li>
  );
}

function StatusPill({ status }: { status: InternGroupSession['status'] }) {
  const map: Record<string, { label: string; cls: string; icon: React.ReactNode }> = {
    SCHEDULED: { label: 'Scheduled', cls: 'bg-brand-50 text-brand-800 border-brand-200',
                 icon: <CheckCircle2 className="h-3 w-3" /> },
    COMPLETED: { label: 'Completed', cls: 'bg-emerald-50 text-emerald-800 border-emerald-200',
                 icon: <CheckCircle2 className="h-3 w-3" /> },
    CANCELLED: { label: 'Cancelled', cls: 'bg-slate-100 text-slate-600 border-slate-200',
                 icon: <AlertOctagon className="h-3 w-3" /> },
  };
  const m = map[status];
  return (
    <span className={`inline-flex items-center gap-1 rounded-full border px-1.5 py-0.5 text-[10px] font-semibold ${m.cls}`}>
      {m.icon} {m.label}
    </span>
  );
}
