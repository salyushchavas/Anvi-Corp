'use client';

import Link from 'next/link';
import {
  AlertCircle,
  ArrowRight,
  CheckCircle2,
  Circle,
  Inbox,
  PartyPopper,
  Sparkles,
} from 'lucide-react';
import { useState } from 'react';
import api from '@/lib/api';
import RightSidePanel from '@/components/intern/RightSidePanel';
import InactiveBanner from '@/components/exit/InactiveBanner';
import ExitSummaryCard from '@/components/exit/ExitSummaryCard';
import InternProfileCompletionCard from '@/components/intern/InternProfileCompletionCard';
import UpcomingQaCard from '@/components/intern/UpcomingQaCard';
import InternJourneyStepper, {
  currentMilestoneLabel,
} from '@/components/intern/InternJourneyStepper';
import {
  useInternDashboard,
  type InternDashboardResponse,
  type InternNextAction,
  type InternSelectionAck,
} from '@/components/intern/InternDashboardContext';
import DashboardRefreshButton from '@/components/ui/DashboardRefreshButton';
import { cn } from '@/lib/cn';

export default function InternHomePage() {
  const { data, loading, error, refresh } = useInternDashboard();

  if (loading && !data) {
    return (
      <div className="space-y-6">
        <div className="h-12 w-72 animate-pulse rounded bg-slate-100" aria-hidden />
        <div className="h-14 animate-pulse rounded-lg bg-slate-100" aria-hidden />
        <div className="h-48 animate-pulse rounded-lg bg-slate-100" aria-hidden />
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
        We couldn&apos;t load your dashboard right now. {error ?? 'Please try again.'}
      </div>
    );
  }

  const firstName = data.user.firstName || data.user.email.split('@')[0];
  const stageLabel = currentMilestoneLabel(data.lifecycleStatus);
  const isInactive = data.mode === 'INACTIVE';

  if (isInactive) {
    // INACTIVE keeps a simpler, exit-summary-led layout. Journey bar still
    // renders so the intern sees the completed arc; everything else is the
    // exit summary + next action.
    return (
      <>
        <InactiveBanner exitSummary={data.exitSummary ?? null} />
        <HeroHeader firstName={firstName} stageLabel={stageLabel} onRefresh={refresh} />
        <InternJourneyStepper status={data.lifecycleStatus} className="mb-8" />
        <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_280px]">
          <main className="min-w-0 space-y-6">
            <DoThisNextHero action={data.nextAction} />
            {data.exitSummary && <ExitSummaryCard summary={data.exitSummary} />}
          </main>
          <RightSidePanel />
        </div>
      </>
    );
  }

  return (
    <>
      <HeroHeader firstName={firstName} stageLabel={stageLabel} onRefresh={refresh} />

      {!data.emailVerified && (
        <div
          role="status"
          className="mb-6 flex items-start gap-3 rounded-md border-l-4 border-l-amber-500 bg-amber-50 p-4 text-amber-900"
        >
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" strokeWidth={2.5} />
          <div className="flex-1 text-sm">
            <p className="font-medium">Verify your email to apply</p>
            <p className="mt-0.5 text-xs opacity-90">
              We sent a code to {data.user.email}. Open the action below to resend.
            </p>
          </div>
        </div>
      )}

      <InternJourneyStepper status={data.lifecycleStatus} className="mb-8" />

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_280px]">
        <main className="min-w-0 space-y-6">
          {!data.applyReadiness.complete && (
            <InternProfileCompletionCard readiness={data.applyReadiness} />
          )}

          {data.selectionAck && <SelectionAckCard ack={data.selectionAck} />}

          {/* Surfaces the next-scheduled Q&A session (if any) so the
              intern can join from the dashboard without drilling into
              the project detail page. Hidden when nothing is scheduled. */}
          <UpcomingQaCard />

          <DoThisNextHero action={data.nextAction} />

          <TasksCard data={data} />

          <ActivityCard />
        </main>
        <RightSidePanel />
      </div>
    </>
  );
}

// ─── Hero header ────────────────────────────────────────────────────────

function HeroHeader({
  firstName,
  stageLabel,
  onRefresh,
}: {
  firstName: string;
  stageLabel: string;
  onRefresh: () => Promise<void>;
}) {
  return (
    <header className="mb-6 flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <h1 className="text-2xl font-semibold text-slate-900 sm:text-3xl">
          Welcome back, {firstName}
        </h1>
        <p className="mt-1 text-sm text-slate-600">
          You&apos;re at the <span className="font-medium text-brand-700">{stageLabel}</span> step.
        </p>
      </div>
      <DashboardRefreshButton onRefresh={onRefresh} />
    </header>
  );
}

// ─── Do this next hero ──────────────────────────────────────────────────

function DoThisNextHero({ action }: { action: InternNextAction }) {
  const isResendVerification = action.ctaHref === '/api/v1/auth/resend-verification';
  const ackPrefix = '/api/v1/applications/';
  const ackSuffix = '/acknowledge-selection';
  const isAcknowledgeSelection =
    typeof action.ctaHref === 'string'
    && action.ctaHref.startsWith(ackPrefix)
    && action.ctaHref.endsWith(ackSuffix);

  return (
    <section
      className={cn(
        'rounded-lg border-l-4 border-l-brand-600 border border-slate-200 bg-white p-6 shadow-ds-sm',
      )}
    >
      <div className="flex items-start justify-between gap-4">
        <div className="flex-1">
          <div className="mb-1 inline-flex items-center gap-1.5 rounded-full bg-brand-50 px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-brand-700">
            <Sparkles className="h-3 w-3" strokeWidth={2.5} />
            Do this next
          </div>
          <h2 className="text-xl font-semibold text-slate-900">{action.title}</h2>
          <p className="mt-1 text-sm text-slate-600">{action.description}</p>
        </div>
        {action.waiting && (
          <span className="rounded-full bg-amber-100 px-3 py-1 text-xs font-medium text-amber-800">
            Waiting on {action.waitingFor ?? 'next step'}
          </span>
        )}
      </div>

      {action.ctaLabel && action.ctaHref && !action.waiting && (
        <div className="mt-4">
          {isResendVerification ? (
            <ResendVerificationButton label={action.ctaLabel} />
          ) : isAcknowledgeSelection ? (
            <AcknowledgeSelectionButton label={action.ctaLabel} href={action.ctaHref} />
          ) : (
            <Link
              href={action.ctaHref}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-brand-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2"
            >
              {action.ctaLabel}
              <ArrowRight className="h-4 w-4" strokeWidth={2.5} />
            </Link>
          )}
        </div>
      )}
    </section>
  );
}

function AcknowledgeSelectionButton({ label, href }: { label: string; href: string }) {
  const { refresh } = useInternDashboard();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function onClick() {
    setBusy(true);
    setErr(null);
    try {
      await api.post(href);
      await refresh();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error
          ?? (e instanceof Error ? e.message : 'Could not record acknowledgment'),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <button
        type="button"
        onClick={onClick}
        disabled={busy}
        className="inline-flex items-center rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-brand-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2 disabled:opacity-60"
      >
        {busy ? 'Sending…' : label}
      </button>
      {err && <p className="mt-2 text-xs text-red-700">{err}</p>}
    </div>
  );
}

function ResendVerificationButton({ label }: { label: string }) {
  const { data, refresh } = useInternDashboard();
  const [sending, setSending] = useState(false);
  const [done, setDone] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function onClick() {
    if (!data?.user.email) return;
    setSending(true);
    setErr(null);
    try {
      await api.post('/auth/resend-verification', { email: data.user.email });
      setDone(true);
      await refresh();
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not send verification');
    } finally {
      setSending(false);
    }
  }

  if (done) {
    return (
      <p className="text-sm text-green-700">
        Verification email sent — check your inbox.
      </p>
    );
  }

  return (
    <div>
      <button
        type="button"
        onClick={onClick}
        disabled={sending}
        className="inline-flex items-center rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-brand-800 disabled:opacity-60"
      >
        {sending ? 'Sending…' : label}
      </button>
      {err && <p className="mt-2 text-xs text-red-700">{err}</p>}
    </div>
  );
}

// ─── Selection ack (kept for the "you've been selected" surge moment) ──

function SelectionAckCard({ ack }: { ack: InternSelectionAck }) {
  const { refresh } = useInternDashboard();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const jobLabel = ack.jobTitle?.trim() || 'the role';

  async function onClick() {
    setBusy(true);
    setErr(null);
    try {
      await api.post(`/api/v1/applications/${ack.applicationId}/acknowledge-selection`);
      await refresh();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(
        ax.response?.data?.error
          ?? (e instanceof Error ? e.message : 'Could not record acknowledgment'),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="rounded-lg border border-green-200 bg-green-50 p-6 shadow-sm">
      <div className="inline-flex items-center gap-1.5 rounded-full bg-green-100 px-2.5 py-0.5 text-[11px] font-semibold uppercase tracking-wide text-green-800">
        <PartyPopper className="h-3 w-3" strokeWidth={2.5} />
        Congratulations
      </div>
      <h2 className="mt-2 text-xl font-semibold text-green-900">
        You&apos;ve been selected for {jobLabel}
      </h2>
      <p className="mt-1 text-sm text-green-900/90">
        When you&apos;re ready, click the button below. We&apos;ll prepare and send
        your offer letter right after — you&apos;ll receive an IDMS email to review
        and sign.
      </p>
      {ack.applicantVisibleNotes && (
        <div className="mt-3 rounded-md border border-green-200 bg-white p-3 text-sm text-slate-700">
          <p className="text-[11px] font-semibold uppercase tracking-wide text-green-700">
            From the interview team
          </p>
          <p className="mt-1 whitespace-pre-wrap">{ack.applicantVisibleNotes}</p>
        </div>
      )}
      <div className="mt-4">
        <button
          type="button"
          onClick={onClick}
          disabled={busy}
          className="inline-flex items-center rounded-md bg-green-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-green-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-green-500 focus-visible:ring-offset-2 disabled:opacity-60"
        >
          {busy ? 'Sending…' : 'Receive my offer letter'}
        </button>
        {err && <p className="mt-2 text-xs text-red-700">{err}</p>}
      </div>
    </section>
  );
}

// ─── Tasks (derived from existing signals; honest empty state) ─────────

interface DerivedTask {
  key: string;
  label: string;
  href?: string;
}

function deriveTasks(data: InternDashboardResponse): DerivedTask[] {
  const tasks: DerivedTask[] = [];

  // Verify-email is a top-of-funnel todo — covered by the banner above too,
  // but listed here so the consolidated checklist is honest about what's
  // pending the intern.
  if (!data.emailVerified) {
    tasks.push({ key: 'verify-email', label: 'Verify your email' });
  }

  // Profile completion is already surfaced as its own card; the Tasks card
  // gets a single-line entry so the consolidated checklist is complete.
  if (data.applyReadiness && !data.applyReadiness.complete) {
    tasks.push({
      key: 'complete-profile',
      label: 'Complete your profile',
      href: '/careers/intern/profile/complete',
    });
  }

  // Surface the live next-action only when it's a do-now action AND not
  // already represented by the verify-email / profile entries above
  // (avoid duplicating).
  const a = data.nextAction;
  if (a && a.ctaLabel && a.ctaHref && !a.waiting) {
    const ctaIsVerify = a.ctaHref === '/api/v1/auth/resend-verification';
    const ctaIsProfile = a.ctaHref === '/careers/intern/profile/complete';
    if (!ctaIsVerify && !ctaIsProfile) {
      // Selection-ack also drives its own card; skip to avoid duplication.
      const ctaIsSelectionAck =
        a.ctaHref.startsWith('/api/v1/applications/')
        && a.ctaHref.endsWith('/acknowledge-selection');
      if (!ctaIsSelectionAck) {
        const isInternalLink = a.ctaHref.startsWith('/') && !a.ctaHref.startsWith('/api/');
        tasks.push({
          key: 'next-action',
          label: a.title,
          href: isInternalLink ? a.ctaHref : undefined,
        });
      }
    }
  }

  return tasks;
}

function TasksCard({ data }: { data: InternDashboardResponse }) {
  const tasks = deriveTasks(data);

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-ds-sm">
      <h3 className="text-sm font-semibold text-slate-900">Your tasks</h3>
      {tasks.length === 0 ? (
        <div className="mt-3 flex items-start gap-3 rounded-md bg-slate-50 p-4 text-sm text-slate-600">
          <Inbox className="mt-0.5 h-4 w-4 shrink-0 text-slate-400" strokeWidth={2} />
          <span>Nothing pending right now — your team will reach out when there&apos;s a move.</span>
        </div>
      ) : (
        <ul className="mt-3 space-y-2">
          {tasks.map((t) => (
            <li key={t.key} className="flex items-center gap-3 rounded-md border border-slate-100 bg-white px-3 py-2 text-sm">
              <Circle className="h-4 w-4 shrink-0 text-slate-400" strokeWidth={2} />
              {t.href ? (
                <Link
                  href={t.href}
                  className="flex-1 text-slate-800 hover:text-brand-700 hover:underline"
                >
                  {t.label}
                </Link>
              ) : (
                <span className="flex-1 text-slate-800">{t.label}</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

// ─── Activity (no DTO data today — honest empty state) ─────────────────

function ActivityCard() {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-ds-sm">
      <h3 className="text-sm font-semibold text-slate-900">Recent activity</h3>
      <div className="mt-3 flex items-start gap-3 rounded-md bg-slate-50 p-4 text-sm text-slate-600">
        <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-slate-400" strokeWidth={2} />
        <span>Activity will appear here as your journey progresses.</span>
      </div>
    </section>
  );
}

