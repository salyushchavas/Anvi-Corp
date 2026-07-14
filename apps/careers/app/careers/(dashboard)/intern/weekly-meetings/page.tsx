'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  CalendarClock,
  Clock,
  ExternalLink,
  Key,
  Video,
} from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';

interface WeeklyMeeting {
  id: string;
  internLifecycleId: string;
  scheduledFor: string;
  durationMinutes: number;
  timezone?: string;
  topic: string;
  agenda?: string | null;
  zoomMeetingId?: string | null;
  zoomJoinUrl?: string | null;
  /** Applicant-safe DTO never includes zoomStartUrl — present in TS as
   *  optional for forward compat with staff endpoints only. */
  zoomStartUrl?: never;
  zoomPassword?: string | null;
  hostUserId: string;
  status: 'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';
  recurrence?: string | null;
  recurrenceParentId?: string | null;
}

const STATUS_STYLE: Record<string, string> = {
  SCHEDULED: 'bg-amber-100 text-amber-800',
  COMPLETED: 'bg-slate-100 text-slate-700',
  CANCELLED: 'bg-slate-100 text-slate-600',
  NO_SHOW:   'bg-red-100 text-red-800',
};

export default function InternWeeklyMeetingsPage() {
  const [meetings, setMeetings] = useState<WeeklyMeeting[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<WeeklyMeeting[]>('/api/v1/weekly-meetings/mine');
      setMeetings(res.data ?? []);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load meetings');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const { upcoming, past } = useMemo(() => {
    const now = Date.now();
    const all = [...meetings].sort((a, b) =>
      a.scheduledFor.localeCompare(b.scheduledFor));
    const upcoming = all.filter((m) =>
      m.status === 'SCHEDULED' && new Date(m.scheduledFor).getTime() > now - 60 * 60 * 1000);
    const past = all.filter((m) => !upcoming.includes(m));
    return { upcoming, past };
  }, [meetings]);

  if (loading) {
    return (
      <InternPageShell title="Weekly Meetings">
        <div className="h-48 animate-pulse rounded-lg bg-slate-50" aria-hidden />
      </InternPageShell>
    );
  }
  if (err) {
    return (
      <InternPageShell title="Weekly Meetings">
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">{err}</p>
      </InternPageShell>
    );
  }
  if (meetings.length === 0) {
    return (
      <InternPageShell title="Weekly Meetings" subtitle="Your trainer's support sessions appear here.">
        <p className="rounded-md border border-dashed border-slate-300 bg-white p-10 text-center text-sm text-slate-500">
          No meetings scheduled yet. Your trainer will set up your first session shortly.
        </p>
      </InternPageShell>
    );
  }

  return (
    <InternPageShell title="Weekly Meetings" subtitle="Trainer-led support sessions over Zoom.">
      {upcoming[0] && <HeroCard meeting={upcoming[0]} />}
      {upcoming.length > 1 && (
        <section className="mt-6">
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
            Upcoming
          </h2>
          <ul className="space-y-2">
            {upcoming.slice(1).map((m) => <Row key={m.id} meeting={m} />)}
          </ul>
        </section>
      )}
      {past.length > 0 && (
        <section className="mt-8">
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
            Past
          </h2>
          <ul className="space-y-2">
            {past.slice(0, 12).map((m) => <Row key={m.id} meeting={m} />)}
          </ul>
        </section>
      )}
    </InternPageShell>
  );
}

function HeroCard({ meeting }: { meeting: WeeklyMeeting }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => window.clearInterval(t);
  }, []);
  const scheduled = new Date(meeting.scheduledFor).getTime();
  const minutesUntil = Math.round((scheduled - now) / 60_000);
  const canJoin = meeting.status === 'SCHEDULED'
    && Boolean(meeting.zoomJoinUrl)
    && minutesUntil < 1440
    && (now - scheduled) < 60 * 60 * 1000;

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="inline-flex items-center gap-1.5 rounded-full bg-amber-100 px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-amber-800">
            <CalendarClock className="h-3 w-3" strokeWidth={2.5} />
            Next meeting
          </div>
          <h2 className="mt-2 text-xl font-semibold text-slate-900">{meeting.topic}</h2>
          <p className="mt-1 text-sm text-slate-600">
            {new Date(meeting.scheduledFor).toLocaleString()} ({meeting.timezone ?? 'UTC'})
          </p>
          <p className="mt-1 text-xs text-slate-500">
            <Clock className="mr-1 inline h-3 w-3" />
            {countdownLabel(minutesUntil)}
          </p>
        </div>
        {meeting.zoomJoinUrl && (
          <a
            href={meeting.zoomJoinUrl}
            target="_blank"
            rel="noopener noreferrer"
            aria-disabled={!canJoin}
            className={
              'inline-flex items-center gap-2 rounded-md px-5 py-2.5 text-sm font-semibold transition-colors '
              + (canJoin
                ? 'bg-brand-700 text-white hover:bg-brand-800'
                : 'cursor-not-allowed bg-slate-100 text-slate-400 pointer-events-none')
            }
          >
            <Video className="h-4 w-4" />
            Join Meeting
            <ExternalLink className="h-3 w-3" />
          </a>
        )}
      </div>
      <div className="mt-5 grid gap-4 sm:grid-cols-3">
        <InfoCell icon={<Clock className="h-4 w-4" />} label="Duration" value={`${meeting.durationMinutes} min`} />
        {meeting.zoomPassword && (
          <InfoCell icon={<Key className="h-4 w-4" />} label="Passcode" value={meeting.zoomPassword} />
        )}
        {meeting.recurrence && (
          <InfoCell icon={<CalendarClock className="h-4 w-4" />} label="Series" value="Weekly" />
        )}
      </div>
      {meeting.agenda && (
        <section className="mt-6 rounded-md border border-slate-200 bg-slate-50 p-4">
          <h3 className="text-sm font-semibold text-slate-900">Agenda</h3>
          <p className="mt-2 whitespace-pre-wrap text-sm text-slate-700">{meeting.agenda}</p>
        </section>
      )}
    </section>
  );
}

function Row({ meeting }: { meeting: WeeklyMeeting }) {
  return (
    <li className="flex items-center gap-3 rounded-md border border-slate-200 bg-white p-3">
      <Video className="h-4 w-4 text-slate-400" />
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm text-slate-800">{meeting.topic}</div>
        <div className="text-xs text-slate-500">
          {new Date(meeting.scheduledFor).toLocaleString()} ({meeting.timezone ?? 'UTC'}) · {meeting.durationMinutes}m
        </div>
      </div>
      <span className={'rounded-full px-2 py-0.5 text-xs font-medium ' + (STATUS_STYLE[meeting.status] ?? 'bg-slate-100 text-slate-600')}>
        {meeting.status}
      </span>
    </li>
  );
}

function InfoCell({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="rounded-md border border-slate-200 bg-white p-3">
      <div className="flex items-center gap-1.5 text-xs uppercase tracking-wide text-slate-400">
        {icon}{label}
      </div>
      <div className="mt-1 text-sm font-medium text-slate-900">{value}</div>
    </div>
  );
}

function countdownLabel(minutes: number): string {
  if (minutes < -60) return `Started ${Math.round(-minutes / 60)}h ago`;
  if (minutes < 0) return `Started ${-minutes}m ago`;
  if (minutes < 60) return `Starts in ${minutes}m`;
  if (minutes < 1440) return `Starts in ${Math.round(minutes / 60)}h`;
  return `In ${Math.round(minutes / 1440)} days`;
}
