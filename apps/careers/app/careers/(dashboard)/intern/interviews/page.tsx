'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import {
  CalendarClock,
  Clock,
  ExternalLink,
  Key,
  User as UserIcon,
  Video,
} from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import type { CandidateInterviewResponse, InterviewStatus } from '@/types';
import {
  formatInZone,
  formatLocalIfDifferent,
} from '@/lib/format-interview-time';

const STATUS_STYLE: Record<InterviewStatus, string> = {
  SCHEDULED: 'bg-amber-100 text-amber-800',
  COMPLETED: 'bg-slate-100 text-slate-700',
  CANCELLED: 'bg-slate-100 text-slate-600',
  NO_SHOW: 'bg-red-100 text-red-800',
};

export default function InternInterviewsPage() {
  const [items, setItems] = useState<CandidateInterviewResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<CandidateInterviewResponse[]>('/api/v1/interviews/mine');
      setItems(res.data ?? []);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load your interviews');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const { active, past } = useMemo(() => {
    const sorted = [...items].sort((a, b) =>
      (b.scheduledAt ?? '').localeCompare(a.scheduledAt ?? ''));
    const a = sorted.find((i) => i.status === 'SCHEDULED');
    const others = sorted.filter((i) => i !== a);
    return { active: a, past: others };
  }, [items]);

  if (loading) {
    return (
      <InternPageShell title="Interview Center">
        <div className="h-48 animate-pulse rounded-lg bg-slate-50" aria-hidden />
      </InternPageShell>
    );
  }
  if (err) {
    return (
      <InternPageShell title="Interview Center">
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">{err}</p>
      </InternPageShell>
    );
  }
  if (items.length === 0) {
    return (
      <InternPageShell title="Interview Center" subtitle="Your interview details will appear here.">
        <p className="rounded-md border border-dashed border-slate-300 bg-white p-10 text-center text-sm text-slate-500">
          No interviews scheduled yet. We'll let you know the moment one is set up.
        </p>
      </InternPageShell>
    );
  }

  return (
    <InternPageShell title="Interview Center">
      {active && <InterviewHeroCard interview={active} />}
      {past.length > 0 && (
        <section className="mt-8">
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
            Past interviews
          </h2>
          <ul className="space-y-2">
            {past.map((i) => (
              <li key={i.id}>
                <Link
                  href={`/careers/intern/interviews/${i.id}`}
                  className="flex items-center gap-3 rounded-md border border-slate-200 bg-white p-3 transition-colors hover:bg-slate-50"
                >
                  <Video className="h-4 w-4 text-slate-400" />
                  <div className="flex-1 min-w-0">
                    <div className="text-sm text-slate-700">
                      {i.jobPostingTitle ?? 'Interview'}
                    </div>
                    <div className="text-xs text-slate-500">
                      {formatInZone(i.scheduledAt, i.timezone)}
                    </div>
                  </div>
                  <span className={'rounded-full px-2 py-0.5 text-xs font-medium ' + (STATUS_STYLE[i.status] ?? 'bg-slate-100 text-slate-600')}>
                    {i.status}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
    </InternPageShell>
  );
}

function InterviewHeroCard({ interview }: { interview: CandidateInterviewResponse }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => window.clearInterval(t);
  }, []);
  const scheduled = new Date(interview.scheduledAt).getTime();
  const minutesUntil = Math.round((scheduled - now) / 60000);
  const canJoin = interview.status === 'SCHEDULED'
    && Boolean(interview.meetingUrl)
    && minutesUntil < 1440 // join window opens 24h before
    && (now - scheduled) < 60 * 60 * 1000; // up to 60min after scheduled time

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="inline-flex items-center gap-1.5 rounded-full bg-amber-100 px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-amber-800">
            <CalendarClock className="h-3 w-3" strokeWidth={2.5} />
            {interview.status}
          </div>
          <h2 className="mt-2 text-xl font-semibold text-slate-900">
            {interview.jobPostingTitle ?? 'Interview'}
          </h2>
          <p className="mt-1 text-sm text-slate-600">
            Scheduled for{' '}
            <span className="font-medium text-slate-800">
              {formatInZone(interview.scheduledAt, interview.timezone)}
            </span>
          </p>
          {(() => {
            const local = formatLocalIfDifferent(interview.scheduledAt, interview.timezone);
            return local ? (
              <p className="mt-0.5 text-xs text-slate-500">({local})</p>
            ) : null;
          })()}
          <p className="mt-1 text-xs text-slate-500">
            <Clock className="mr-1 inline h-3 w-3" />
            {countdownLabel(minutesUntil)}
          </p>
        </div>
        {interview.meetingUrl && (
          <a
            href={interview.meetingUrl}
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
        {interview.interviewerName && (
          <InfoCard
            icon={<UserIcon className="h-4 w-4" />}
            label="Interviewer"
            value={interview.interviewerName}
          />
        )}
        <InfoCard
          icon={<Clock className="h-4 w-4" />}
          label="Duration"
          value={`${interview.durationMinutes} min`}
        />
        {interview.zoomPassword && (
          <InfoCard
            icon={<Key className="h-4 w-4" />}
            label="Passcode"
            value={interview.zoomPassword}
          />
        )}
      </div>

      {interview.prepInstructions && (
        <section className="mt-6 rounded-md border border-slate-200 bg-slate-50 p-4">
          <h3 className="text-sm font-semibold text-slate-900">Prep instructions</h3>
          <p className="mt-2 whitespace-pre-wrap text-sm text-slate-700">
            {interview.prepInstructions}
          </p>
        </section>
      )}

      <section className="mt-6 border-t border-slate-100 pt-4">
        <h3 className="text-sm font-semibold text-slate-900">What to expect</h3>
        <ul className="mt-2 space-y-1.5 text-sm text-slate-600">
          <li>• Test your mic and camera 5 minutes before the start.</li>
          <li>• Join from a quiet space; the waiting room is enabled by default.</li>
          <li>• Bring questions about the role — the last 5 minutes are yours.</li>
        </ul>
      </section>
    </section>
  );
}

function InfoCard({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="rounded-md border border-slate-200 bg-white p-3">
      <div className="flex items-center gap-1.5 text-xs uppercase tracking-wide text-slate-400">
        {icon}
        {label}
      </div>
      <div className="mt-1 text-sm font-medium text-slate-900">{value}</div>
    </div>
  );
}

function countdownLabel(minutes: number): string {
  if (minutes < -60) {
    const h = Math.round(-minutes / 60);
    return `Started ${h}h ago`;
  }
  if (minutes < 0) return `Started ${-minutes}m ago`;
  if (minutes < 60) return `Starts in ${minutes}m`;
  if (minutes < 1440) return `Starts in ${Math.round(minutes / 60)}h`;
  return `In ${Math.round(minutes / 1440)} days`;
}

