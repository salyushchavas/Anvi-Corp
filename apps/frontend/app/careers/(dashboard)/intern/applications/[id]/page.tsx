'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import { CheckCircle2, ChevronLeft, Circle, Clock } from 'lucide-react';
import api from '@/lib/careers/api';
import InternPageShell from '@/components/intern/InternPageShell';
import InfoRequestedBanner from '@/components/intern/applications/InfoRequestedBanner';
import type { ApplicationResponse } from '@/types';

const STAGE_STYLE: Record<string, string> = {
  APPLIED: 'bg-slate-100 text-slate-700',
  SHORTLISTED: 'bg-amber-100 text-amber-800',
  INTERVIEW_SCHEDULED: 'bg-amber-100 text-amber-800',
  INTERVIEWED: 'bg-slate-100 text-slate-700',
  OFFERED: 'bg-green-100 text-green-800',
  HIRED: 'bg-green-100 text-green-800',
  REJECTED: 'bg-red-100 text-red-800',
  WITHDRAWN: 'bg-slate-100 text-slate-600',
};

// W3 #16 — the intern-facing pipeline stages, ordered. Each backend
// application status maps to an index into this array — the timeline
// renders stages before as "done", the current as "in progress",
// stages after as "upcoming". Terminal negative statuses (REJECTED /
// WITHDRAWN) collapse the timeline via `terminalTone`.
const PIPELINE_STAGES = [
  { key: 'applied',    label: 'Applied' },
  { key: 'reviewing',  label: 'Under review' },
  { key: 'interview',  label: 'Interview' },
  { key: 'decision',   label: 'Decision' },
  { key: 'offer',      label: 'Offer' },
];

function pipelineIndexFor(status: string): number {
  switch (status) {
    case 'APPLIED':             return 1; // in-review (past "Applied")
    case 'INFO_REQUESTED':      return 1; // still under review
    case 'SHORTLISTED':         return 2; // interview coming
    case 'INTERVIEW_SCHEDULED': return 2; // interview scheduled
    case 'INTERVIEWED':         return 3; // decision pending
    case 'OFFERED':             return 4; // offer stage
    case 'HIRED':               return 5; // past every stage
    default:                    return 1;
  }
}

interface StatusCopy { current: string; next: string; }

// W3 #16 — human-language explanation for each status. Renders in the
// "Current status" + "What's next" cards. Kept close to the backend
// enum values so both surfaces stay in sync.
function statusCopyFor(status: string): StatusCopy {
  switch (status) {
    case 'APPLIED':
      return {
        current: 'Your application is in the queue for the recruiting team to review.',
        next: 'You\'ll hear back within about 5 business days. Watch your inbox for updates.',
      };
    case 'INFO_REQUESTED':
      return {
        current: 'ERM asked for a bit more information before advancing your application.',
        next: 'Use the banner above to provide the requested details — your application resumes review as soon as you submit.',
      };
    case 'SHORTLISTED':
      return {
        current: 'You\'ve been shortlisted. Your application advances to interviewing.',
        next: 'ERM will reach out with interview details shortly.',
      };
    case 'INTERVIEW_SCHEDULED':
      return {
        current: 'Your interview is on the calendar.',
        next: 'Attend the scheduled interview — check My Interviews for the join link + prep notes.',
      };
    case 'INTERVIEWED':
      return {
        current: 'Your interview is complete. The interviewer is recording feedback.',
        next: 'The hiring manager reviews the scorecard. You\'ll hear the decision on your Home page.',
      };
    case 'OFFERED':
      return {
        current: 'Congratulations — you\'ve received an offer.',
        next: 'Review and sign your offer letter from Offer Letter in the sidebar.',
      };
    case 'HIRED':
      return {
        current: 'You\'ve accepted the offer and onboarding is in motion.',
        next: 'Complete any onboarding documents from Onboarding in the sidebar.',
      };
    case 'REJECTED':
      return {
        current: 'This application didn\'t move forward this time.',
        next: 'New roles are posted regularly — Jobs in the sidebar has the current openings.',
      };
    case 'WITHDRAWN':
      return {
        current: 'You withdrew this application.',
        next: 'Nothing further needed here. Jobs in the sidebar has other openings.',
      };
    default:
      return {
        current: 'Your application is in progress.',
        next: 'Watch your inbox for updates from ERM.',
      };
  }
}

export default function InternApplicationDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const [app, setApp] = useState<ApplicationResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<ApplicationResponse>(`/api/v1/applications/${id}`);
      setApp(res.data);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not load this application');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  if (loading) {
    return (
      <InternPageShell title="Application">
        <div className="h-48 animate-pulse rounded-lg bg-slate-50" aria-hidden />
      </InternPageShell>
    );
  }
  if (err || !app) {
    return (
      <InternPageShell title="Application">
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {err ?? 'Application not found'}
        </p>
      </InternPageShell>
    );
  }

  const showFeedback = app.applicantVisibleFeedback
    && (app.status === 'INTERVIEWED' || app.status === 'REJECTED');

  return (
    <InternPageShell
      title={app.jobPostingTitle ?? 'Application'}
      subtitle={
        <span className={'inline-flex rounded-full px-2 py-0.5 text-xs font-medium ' + (STAGE_STYLE[app.status] ?? 'bg-slate-100 text-slate-700')}>
          {app.status.replaceAll('_', ' ')}
        </span>
      }
    >
      <Link
        href="/careers/intern/applications"
        className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
      >
        <ChevronLeft className="h-4 w-4" strokeWidth={2} /> All applications
      </Link>

      {app.status === 'INFO_REQUESTED' && (
        <InfoRequestedBanner
          applicationId={app.id}
          infoRequestedFieldsCsv={app.infoRequestedFieldsCsv ?? null}
          reasonLabel={app.infoRequestedReasonLabel}
          message={app.infoRequestedMessage}
          requestedAt={app.infoRequestedAt}
          onProvided={() => void load()}
        />
      )}

      {app.status !== 'INFO_REQUESTED' && app.infoProvidedAt && (
        <section className="mb-4 rounded-md border border-slate-200 bg-slate-50 p-4 text-sm">
          <p className="font-semibold text-slate-900">
            Your response was submitted
          </p>
          <p className="mt-0.5 text-[12px] text-slate-500">
            Sent {new Date(app.infoProvidedAt).toLocaleString()}
          </p>
          {app.infoProvidedResponse && (
            <p className="mt-2 whitespace-pre-wrap text-slate-700">
              {app.infoProvidedResponse}
            </p>
          )}
          <p className="mt-2 text-[12px] text-slate-500">
            Your application is back in review. The team will follow up shortly.
          </p>
        </section>
      )}

      {/* W3 #16 — Pipeline timeline + status cards. Renders BEFORE
          the existing feedback + Job/Application grids so the intern
          gets an at-a-glance "where am I + what's next" without
          scrolling. Terminal negative statuses (REJECTED / WITHDRAWN)
          skip the timeline and only render the status/next-step
          cards (already terminal — no forward stages to preview). */}
      {app.status !== 'REJECTED' && app.status !== 'WITHDRAWN' && (
        <ApplicationPipelineTimeline status={app.status} />
      )}
      <ApplicationStatusCards status={app.status} />

      {showFeedback && (
        <section className="mb-6 rounded-lg border border-green-200 bg-green-50 p-5">
          <h2 className="text-sm font-semibold text-green-900">From the team</h2>
          <p className="mt-2 whitespace-pre-wrap text-sm text-green-900">
            {app.applicantVisibleFeedback}
          </p>
        </section>
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-900">Job</h2>
          <dl className="mt-3 space-y-2 text-sm">
            <DetailRow label="Title" value={app.jobPostingTitle} />
            <DetailRow label="Job ID" value={app.jobPostingId} />
          </dl>
        </section>

        <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-900">Your application</h2>
          <dl className="mt-3 space-y-2 text-sm">
            <DetailRow label="Submitted" value={new Date(app.appliedAt).toLocaleString()} />
            {app.statusUpdatedAt && (
              <DetailRow label="Last update" value={new Date(app.statusUpdatedAt).toLocaleString()} />
            )}
            {app.resumeFileName && (
              <DetailRow label="Resume" value={app.resumeFileName} />
            )}
          </dl>
          {app.statementOfInterest && (
            <>
              <h3 className="mt-4 text-xs font-semibold uppercase tracking-wide text-slate-500">
                Your statement
              </h3>
              <p className="mt-2 whitespace-pre-wrap text-sm text-slate-700">
                {app.statementOfInterest}
              </p>
            </>
          )}
        </section>
      </div>
    </InternPageShell>
  );
}

function DetailRow({ label, value }: { label: string; value: React.ReactNode }) {
  if (!value) return null;
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="text-xs uppercase tracking-wide text-slate-400">{label}</dt>
      <dd className="text-right text-sm text-slate-700">{value}</dd>
    </div>
  );
}

/**
 * W3 #16 — horizontal 5-stage pipeline (Applied → Under review →
 * Interview → Decision → Offer). Renders each stage as one of three
 * visual states derived from `pipelineIndexFor(status)`:
 *   • done       — stage index < current (green check)
 *   • active     — stage index === current (brand ring, subtle pulse)
 *   • upcoming   — stage index > current (muted slate)
 * Hidden entirely for terminal negative statuses at the call site.
 */
function ApplicationPipelineTimeline({ status }: { status: string }) {
  const currentIdx = pipelineIndexFor(status);
  return (
    <section
      aria-label="Application pipeline"
      className="mb-6 overflow-x-auto rounded-lg border border-slate-200 bg-white p-5 shadow-sm"
    >
      <ol className="flex min-w-[36rem] items-start justify-between gap-2">
        {PIPELINE_STAGES.map((stage, idx) => {
          const isDone = idx < currentIdx;
          const isActive = idx === currentIdx;
          const isUpcoming = idx > currentIdx;
          return (
            <li
              key={stage.key}
              className="flex flex-1 flex-col items-center gap-1.5 text-center"
              aria-current={isActive ? 'step' : undefined}
            >
              <div
                className={
                  'flex h-8 w-8 items-center justify-center rounded-full '
                  + (isDone
                    ? 'bg-emerald-100 text-emerald-700 ring-1 ring-emerald-200'
                    : isActive
                      ? 'bg-brand-100 text-brand-700 ring-2 ring-brand-500'
                      : 'bg-slate-100 text-slate-400 ring-1 ring-slate-200')
                }
              >
                {isDone ? (
                  <CheckCircle2 className="h-4 w-4" />
                ) : isActive ? (
                  <Clock className="h-4 w-4" />
                ) : (
                  <Circle className="h-3.5 w-3.5" />
                )}
              </div>
              <span
                className={
                  'text-xs font-medium '
                  + (isDone
                    ? 'text-slate-700'
                    : isActive
                      ? 'text-brand-800'
                      : 'text-slate-400')
                }
              >
                {stage.label}
              </span>
              {isUpcoming ? null : null}
            </li>
          );
        })}
      </ol>
    </section>
  );
}

/**
 * W3 #16 — Current status + What's next side-by-side. Copy comes from
 * {@link statusCopyFor}; same enum values the pipeline timeline reads.
 * Renders even for terminal statuses (REJECTED / WITHDRAWN) so the
 * intern still gets a one-line orientation + a "here's what to do
 * next" nudge (e.g. "browse other openings").
 */
function ApplicationStatusCards({ status }: { status: string }) {
  const copy = statusCopyFor(status);
  return (
    <div className="mb-6 grid gap-4 lg:grid-cols-2">
      <section className="rounded-lg border border-slate-200 bg-slate-50 p-5">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
          Current status
        </h3>
        <p className="mt-2 text-sm text-slate-800">{copy.current}</p>
      </section>
      <section className="rounded-lg border border-brand-200 bg-brand-50/50 p-5">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-brand-700">
          What&apos;s next
        </h3>
        <p className="mt-2 text-sm text-slate-800">{copy.next}</p>
      </section>
    </div>
  );
}
