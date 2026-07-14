'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import {
  AlertCircle,
  CalendarClock,
  CheckCircle2,
  ChevronRight,
  Clock,
  ExternalLink,
  Star,
  Video,
} from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';

type EvaluationStatus =
  | 'PUBLISHED'
  | 'ACKNOWLEDGED'
  | 'AMENDED';

interface InternEvaluation {
  id: string;
  internLifecycleId: string;
  internId: string;
  evaluatorId: string;
  evaluationType: string;
  linkedProjectId?: string | null;
  linkedI983Id?: string | null;
  periodStart?: string | null;
  periodEnd?: string | null;
  scheduledFor?: string | null;
  durationMinutes?: number | null;
  timezone?: string | null;
  zoomMeetingId?: string | null;
  zoomJoinUrl?: string | null;
  /** Applicant-safe DTO never carries zoomStartUrl. */
  zoomStartUrl?: never;
  zoomPassword?: string | null;
  status: EvaluationStatus | 'SCHEDULED';
  overallScore?: number | null;
  technicalSkillsScore?: number | null;
  communicationScore?: number | null;
  professionalismScore?: number | null;
  learningApplicationScore?: number | null;
  strengthsNarrative?: string | null;
  areasForImprovementNarrative?: string | null;
  improvementPlan?: string | null;
  internAcknowledgedAt?: string | null;
  internResponse?: string | null;
  publishedAt?: string | null;
  amendedAt?: string | null;
  amendmentReason?: string | null;
  version: number;
}

interface UpcomingEvaluation {
  id: string;
  evaluationType: string;
  scheduledFor: string;
  durationMinutes?: number | null;
  timezone?: string | null;
  zoomJoinUrl?: string | null;
  zoomPassword?: string | null;
}

const TYPE_LABEL: Record<string, string> = {
  MONTHLY: 'Monthly',
  POST_PROJECT: 'Post-Project',
  STEM_OPT_12_MONTH: 'STEM OPT — 12 Month',
  STEM_OPT_24_MONTH: 'STEM OPT — 24 Month',
  FINAL: 'Final',
};

export default function InternEvaluationsPage() {
  const [history, setHistory] = useState<InternEvaluation[]>([]);
  const [upcoming, setUpcoming] = useState<UpcomingEvaluation[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [hist, up] = await Promise.all([
        api.get<InternEvaluation[]>('/api/v1/evaluation-cycle/mine'),
        api.get<UpcomingEvaluation[]>('/api/v1/evaluation-cycle/upcoming'),
      ]);
      setHistory(hist.data ?? []);
      setUpcoming(up.data ?? []);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load evaluations');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const toAcknowledge = useMemo(
    () => history.filter((e) => e.status === 'PUBLISHED' || (e.status === 'AMENDED' && !e.internAcknowledgedAt)),
    [history],
  );
  const past = useMemo(
    () => history.filter((e) => !toAcknowledge.includes(e)),
    [history, toAcknowledge],
  );

  if (loading) {
    return (
      <InternPageShell title="Evaluations">
        <div className="h-32 animate-pulse rounded-lg bg-slate-50" aria-hidden />
      </InternPageShell>
    );
  }
  if (err) {
    return (
      <InternPageShell title="Evaluations">
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">{err}</p>
      </InternPageShell>
    );
  }
  if (history.length === 0 && upcoming.length === 0) {
    return (
      <InternPageShell title="Evaluations" subtitle="Periodic feedback from your evaluator.">
        <p className="rounded-md border border-dashed border-slate-300 bg-white p-10 text-center text-sm text-slate-500">
          No evaluations yet. Your Evaluator will schedule the first when ready.
        </p>
      </InternPageShell>
    );
  }

  return (
    <InternPageShell title="Evaluations" subtitle="Periodic feedback from your evaluator.">
      {upcoming[0] && <UpcomingHero meeting={upcoming[0]} />}
      {toAcknowledge.length > 0 && (
        <section className="mt-8">
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
            To acknowledge
          </h2>
          <ul className="space-y-2">
            {toAcknowledge.map((e) => <Row key={e.id} ev={e} />)}
          </ul>
        </section>
      )}
      {past.length > 0 && (
        <section className="mt-8">
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
            Past
          </h2>
          <ul className="space-y-2">
            {past.map((e) => <Row key={e.id} ev={e} />)}
          </ul>
        </section>
      )}
    </InternPageShell>
  );
}

function UpcomingHero({ meeting }: { meeting: UpcomingEvaluation }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => window.clearInterval(t);
  }, []);
  const scheduled = new Date(meeting.scheduledFor).getTime();
  const minutesUntil = Math.round((scheduled - now) / 60_000);
  const canJoin = Boolean(meeting.zoomJoinUrl)
    && minutesUntil < 1440
    && (now - scheduled) < 60 * 60 * 1000;

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="inline-flex items-center gap-1.5 rounded-full bg-amber-100 px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-amber-800">
            <CalendarClock className="h-3 w-3" strokeWidth={2.5} />
            Evaluation scheduled
          </div>
          <h2 className="mt-2 text-xl font-semibold text-slate-900">
            {TYPE_LABEL[meeting.evaluationType] ?? meeting.evaluationType}
          </h2>
          <p className="mt-1 text-sm text-slate-600">
            {new Date(meeting.scheduledFor).toLocaleString()}{' '}
            ({meeting.timezone ?? 'UTC'})
            {meeting.durationMinutes ? ` · ${meeting.durationMinutes} min` : ''}
          </p>
          <p className="mt-1 text-xs text-slate-500">
            <Clock className="mr-1 inline h-3 w-3" />
            {countdown(minutesUntil)}
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
      <section className="mt-5 rounded-md border border-slate-200 bg-slate-50 p-4 text-sm text-slate-700">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
          What to expect
        </h3>
        <ul className="mt-1 list-inside list-disc space-y-1 text-xs">
          <li>The evaluator will walk through your recent work + impact.</li>
          <li>They'll score across five rubric dimensions.</li>
          <li>Bring questions on growth areas — last few minutes are yours.</li>
        </ul>
      </section>
    </section>
  );
}

function Row({ ev }: { ev: InternEvaluation }) {
  const needsAck = (ev.status === 'PUBLISHED' || ev.status === 'AMENDED')
    && !ev.internAcknowledgedAt;
  return (
    <li>
      <Link
        href={`/careers/intern/evaluations/${ev.id}`}
        className="flex items-center gap-3 rounded-md border border-slate-200 bg-white p-3 transition-colors hover:bg-slate-50"
      >
        <Star className="h-4 w-4 text-slate-400" />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-sm font-medium text-slate-900">
              {TYPE_LABEL[ev.evaluationType] ?? ev.evaluationType}
            </span>
            <span className={
              'rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide '
              + (ev.status === 'PUBLISHED' ? 'bg-slate-100 text-slate-700'
                  : ev.status === 'ACKNOWLEDGED' ? 'bg-green-100 text-green-800'
                  : 'bg-amber-100 text-amber-800')
            }>
              {ev.status}
              {ev.version > 1 ? ` · v${ev.version}` : ''}
            </span>
            {ev.amendedAt && (
              <span className="inline-flex items-center gap-1 rounded-full bg-amber-50 px-2 py-0.5 text-[10px] font-medium text-amber-700">
                <AlertCircle className="h-3 w-3" />
                Amended
              </span>
            )}
          </div>
          <div className="text-xs text-slate-500">
            {ev.publishedAt
              ? `Published ${new Date(ev.publishedAt).toLocaleDateString()}`
              : ''}
          </div>
        </div>
        {needsAck ? (
          <span className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white">
            Acknowledge <ChevronRight className="h-3 w-3" />
          </span>
        ) : (
          <CheckCircle2 className="h-4 w-4 text-green-500" />
        )}
      </Link>
    </li>
  );
}

function countdown(minutes: number): string {
  if (minutes < -60) return `Started ${Math.round(-minutes / 60)}h ago`;
  if (minutes < 0) return `Started ${-minutes}m ago`;
  if (minutes < 60) return `Starts in ${minutes}m`;
  if (minutes < 1440) return `Starts in ${Math.round(minutes / 60)}h`;
  return `In ${Math.round(minutes / 1440)} days`;
}
