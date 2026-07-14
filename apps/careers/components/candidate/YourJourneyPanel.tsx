'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { ChevronDown, ChevronUp, History, RefreshCw } from 'lucide-react';
import api from '@/lib/api';
import { cn } from '@/lib/cn';
import { statusSemantic, statusLabel, type Semantic } from '@/lib/status';
import {
  Card,
  StatusPill,
  Stepper,
  type TimelineStep,
  Button,
  Skeleton,
} from '@/components/ui';

const POLL_INTERVAL_MS = 30_000;

interface NextStepData {
  type?: string | null;
  title?: string | null;
  subtitle?: string | null;
  ctaLabel?: string | null;
  ctaHref?: string | null;
  isWaiting?: boolean;
  waitingFor?: string | null;
}

interface RecentUpdate {
  timestamp?: string | null;
  kind?: string | null;
  message?: string | null;
  source?: string | null;
}

interface DashboardStatus {
  overallStage: string;
  nextStep: NextStepData | null;
  timeline: TimelineStep[];
  recentUpdates: RecentUpdate[];
}

const STAGE_TONE: Record<string, Semantic> = {
  APPLIED: 'neutral',
  SCREENING: 'info',
  INTERVIEW: 'info',
  OFFER: 'info',
  ONBOARDING: 'warning',
  HIRED: 'success',
  ACTIVE: 'success',
  COMPLETED: 'success',
  REJECTED: 'danger',
};

const STAGE_LABEL: Record<string, string> = {
  APPLIED: 'Applied',
  SCREENING: 'Screening',
  INTERVIEW: 'Interview',
  OFFER: 'Offer',
  ONBOARDING: 'Onboarding',
  HIRED: 'Hired',
  ACTIVE: 'Active',
  COMPLETED: 'Completed',
  REJECTED: 'Closed',
};

/**
 * Intern / applicant "Your Journey" panel.
 *
 * - Polls `/api/v1/candidate/dashboard-status` every 30s; pauses while the
 *   tab is hidden (visibilitychange) and resumes on focus.
 * - Network blips swallow into a small "Reconnecting…" pill; last-cached
 *   data stays visible.
 * - The next-step card is the visually dominant surface (Card variant=accent
 *   colored by tone). The vertical Stepper below renders every applicable
 *   step including SKIPPED ones, so the intern sees what isn't required.
 * - Recent activity is collapsed by default unless today has fresh updates.
 */
export default function YourJourneyPanel() {
  const [data, setData] = useState<DashboardStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reconnecting, setReconnecting] = useState(false);
  const intervalRef = useRef<number | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get<DashboardStatus>('/api/v1/candidate/dashboard-status');
      setData(res.data);
      setError(null);
      setReconnecting(false);
    } catch (err: any) {
      if (data == null) {
        setError(err?.response?.data?.error ?? "Couldn't load your journey.");
      } else {
        setReconnecting(true);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const startPolling = useCallback(() => {
    if (intervalRef.current != null) return;
    intervalRef.current = window.setInterval(() => void load(), POLL_INTERVAL_MS);
  }, [load]);

  const stopPolling = useCallback(() => {
    if (intervalRef.current != null) {
      window.clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  }, []);

  useEffect(() => {
    void load();
    if (typeof document !== 'undefined' && !document.hidden) startPolling();
    const onVis = () => {
      if (document.hidden) stopPolling();
      else {
        void load();
        startPolling();
      }
    };
    document.addEventListener('visibilitychange', onVis);
    return () => {
      document.removeEventListener('visibilitychange', onVis);
      stopPolling();
    };
  }, [load, startPolling, stopPolling]);

  if (error && data == null) {
    return (
      <Card className="bg-red-50 border-red-200">
        <p className="mb-3 text-sm text-red-700">{error}</p>
        <Button variant="secondary" size="sm" onClick={() => void load()}>
          Retry
        </Button>
      </Card>
    );
  }

  if (data == null) {
    return (
      <Card>
        <Skeleton className="mb-3 h-5 w-32" />
        <Skeleton className="mb-3 h-16 w-full rounded-md" />
        <Skeleton className="h-48 w-full rounded-md" />
      </Card>
    );
  }

  const stageTone = STAGE_TONE[data.overallStage] ?? 'neutral';

  return (
    <Card>
      <header className="mb-5 flex flex-wrap items-start justify-between gap-3">
        <div>
          <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
            Your journey
          </p>
          <h2 className="text-lg font-semibold tracking-tight text-slate-900">
            Where you are right now
          </h2>
        </div>
        <div className="flex items-center gap-2">
          {reconnecting && (
            <span className="inline-flex items-center gap-1 rounded-full bg-amber-50 px-2 py-0.5 text-[11px] font-medium text-amber-800">
              <RefreshCw className="h-3 w-3 animate-spin" strokeWidth={2.5} />
              Reconnecting…
            </span>
          )}
          <StatusPill
            status={data.overallStage}
            label={STAGE_LABEL[data.overallStage] ?? statusLabel(data.overallStage)}
            tone={stageTone}
            size="md"
          />
        </div>
      </header>

      {data.nextStep && <NextStepHero step={data.nextStep} tone={stageTone} />}

      <div className="mt-6">
        <Stepper steps={data.timeline ?? []} />
      </div>

      <RecentActivity items={data.recentUpdates ?? []} />
    </Card>
  );
}

function NextStepHero({ step, tone }: { step: NextStepData; tone: Semantic }) {
  if (!step.title) return null;
  const accent: Semantic = step.isWaiting ? 'warning' : tone === 'success' ? 'success' : 'info';
  const bg =
    accent === 'warning'
      ? 'bg-amber-50 border-amber-200'
      : accent === 'success'
        ? 'bg-green-50 border-green-200'
        : 'bg-brand-50 border-brand-200';
  const titleColor =
    accent === 'warning'
      ? 'text-amber-900'
      : accent === 'success'
        ? 'text-green-900'
        : 'text-brand-900';
  return (
    <div
      className={cn(
        'rounded-lg border border-l-4 p-4',
        bg,
        accent === 'warning' && 'border-l-amber-500',
        accent === 'success' && 'border-l-emerald-500',
        accent === 'info' && 'border-l-brand-500',
      )}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <p className={cn('text-sm font-semibold', titleColor)}>{step.title}</p>
          {step.subtitle && (
            <p className="mt-0.5 text-xs text-slate-700">{step.subtitle}</p>
          )}
          {step.isWaiting && step.waitingFor && (
            <p className="mt-1 text-xs text-amber-800">Waiting on {step.waitingFor}</p>
          )}
        </div>
        {!step.isWaiting && step.ctaHref && step.ctaLabel && (
          <Button size="sm" onClick={() => (window.location.href = step.ctaHref!)}>
            {step.ctaLabel}
          </Button>
        )}
      </div>
    </div>
  );
}

// Lane palette: 5 sources, 5 distinct on-palette tones (no teal/blue/violet/indigo).
// HR=green (people-ops), OPERATIONS=brand (action role, was blue),
// TE=amber (review/feedback), RM=red (oversight, was purple — only on-palette
// slot left that preserves distinctness), SYSTEM=slate (neutral default).
const SOURCE_PILL: Record<string, string> = {
  HR: 'bg-green-50 text-green-700 border-green-200',
  OPERATIONS: 'bg-brand-50 text-brand-700 border-brand-200',
  TE: 'bg-amber-50 text-amber-800 border-amber-200',
  RM: 'bg-red-50 text-red-700 border-red-200',
  SYSTEM: 'bg-slate-50 text-slate-700 border-slate-200',
};

function relative(iso?: string | null): string {
  if (!iso) return '';
  try {
    const then = new Date(iso).getTime();
    const diff = Date.now() - then;
    const m = Math.floor(diff / 60000);
    if (m < 1) return 'just now';
    if (m < 60) return `${m}m ago`;
    const h = Math.floor(m / 60);
    if (h < 24) return `${h}h ago`;
    const d = Math.floor(h / 24);
    if (d < 7) return `${d}d ago`;
    return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  } catch {
    return iso;
  }
}

function RecentActivity({ items }: { items: RecentUpdate[] }) {
  const todayCount = items.filter((it) => {
    if (!it.timestamp) return false;
    return Date.now() - new Date(it.timestamp).getTime() < 24 * 60 * 60 * 1000;
  }).length;
  const [open, setOpen] = useState(todayCount > 0);
  return (
    <div className="mt-6">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex w-full items-center justify-between rounded-md px-1 py-1 text-left transition-colors hover:bg-slate-50"
      >
        <span className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-slate-500">
          <History className="h-3.5 w-3.5" strokeWidth={2.5} />
          Recent activity {items.length > 0 && <span className="text-slate-400">({items.length})</span>}
        </span>
        {open ? (
          <ChevronUp className="h-4 w-4 text-slate-500" />
        ) : (
          <ChevronDown className="h-4 w-4 text-slate-500" />
        )}
      </button>
      {open && (
        <div className="mt-2 rounded-md border border-slate-200 bg-slate-50 px-3 py-2.5">
          {items.length === 0 ? (
            <p className="text-sm text-slate-500">No updates yet.</p>
          ) : (
            <ul className="space-y-2">
              {items.map((it, i) => {
                const src = it.source ?? 'SYSTEM';
                return (
                  <li key={i} className="flex items-start gap-2.5">
                    <span
                      className={cn(
                        'mt-0.5 inline-flex items-center rounded-full border px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide',
                        SOURCE_PILL[src] ?? SOURCE_PILL.SYSTEM,
                      )}
                    >
                      {src}
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="text-sm text-slate-800">{it.message ?? '—'}</p>
                      {it.timestamp && (
                        <p className="text-[11px] text-slate-500">{relative(it.timestamp)}</p>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
