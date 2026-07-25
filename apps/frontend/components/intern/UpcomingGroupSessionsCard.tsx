'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { ArrowRight, Users, Video } from 'lucide-react';
import api from '@/lib/careers/api';
import { formatInZone } from '@/lib/careers/format-interview-time';

/**
 * Surfaces the intern's next SCHEDULED group session (trainer-hosted).
 * Hidden when there are no upcoming sessions. One fetch on mount from
 * {@code /api/v1/intern/group-sessions}; the endpoint already scopes to
 * the current user + strips the host start URL.
 */
export default function UpcomingGroupSessionsCard() {
  const [rows, setRows] = useState<InternGroupSession[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api.get<InternGroupSession[]>('/api/v1/intern/group-sessions')
      .then((res) => {
        if (cancelled) return;
        setRows(res.data ?? []);
        setLoaded(true);
      })
      .catch(() => { if (!cancelled) setLoaded(true); });
    return () => { cancelled = true; };
  }, []);

  if (!loaded) return null;
  const now = Date.now();
  const next = rows
    .filter((s) => s.status === 'SCHEDULED'
      && (!s.scheduledAt || new Date(s.scheduledAt).getTime() >= now - 60_000))
    .sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt))[0];
  if (!next) return null;

  return (
    <section className="rounded-lg border border-brand-200 bg-brand-50/60 p-6 shadow-ds-sm">
      <div className="mb-1 inline-flex items-center gap-1.5 rounded-full bg-brand-100 px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-brand-800">
        <Users className="h-3 w-3" strokeWidth={2.5} />
        Upcoming group session
      </div>
      <h2 className="text-xl font-semibold text-slate-900">{next.topic}</h2>
      <p className="mt-1 text-sm text-slate-700">
        {next.hostName ? `${next.hostName}, your Trainer,` : 'Your Trainer'} scheduled
        a group session with {next.participantCount} intern
        {next.participantCount === 1 ? '' : 's'}.
      </p>
      <dl className="mt-3 grid gap-2 text-xs text-slate-700 sm:grid-cols-2">
        <div>
          <dt className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
            When
          </dt>
          <dd className="mt-0.5 text-sm text-slate-900">
            {formatInZone(next.scheduledAt, next.meetingTimezone)}
            {next.durationMinutes ? ` · ${next.durationMinutes} min` : ''}
          </dd>
        </div>
        {next.description && (
          <div>
            <dt className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              About
            </dt>
            <dd className="mt-0.5 line-clamp-2 text-sm text-slate-900">
              {next.description}
            </dd>
          </div>
        )}
      </dl>
      <div className="mt-4 flex flex-wrap items-center gap-2">
        {next.zoomJoinUrl && (
          <a href={next.zoomJoinUrl} target="_blank" rel="noreferrer noopener"
            className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-brand-800">
            <Video className="h-4 w-4" /> Join meeting
          </a>
        )}
        <Link href="/careers/intern/group-sessions"
          className="inline-flex items-center gap-1.5 rounded-md border border-brand-300 bg-white px-3 py-1.5 text-xs font-semibold text-brand-800 hover:bg-brand-100">
          All group sessions <ArrowRight className="h-3.5 w-3.5" />
        </Link>
      </div>
    </section>
  );
}

export interface InternGroupSession {
  id: string;
  topic: string;
  description: string | null;
  scheduledAt: string;
  meetingTimezone: string;
  durationMinutes: number | null;
  status: 'SCHEDULED' | 'COMPLETED' | 'CANCELLED';
  zoomJoinUrl: string | null;
  zoomPassword: string | null;
  hostName: string | null;
  participantCount: number;
}
