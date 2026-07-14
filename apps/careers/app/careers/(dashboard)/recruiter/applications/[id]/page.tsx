'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import {
  ArrowLeft,
  Briefcase,
  Calendar,
  Check,
  ClipboardList,
  Download,
  ExternalLink,
  Mail,
  MapPin,
  Send,
  Star,
  X,
} from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ProtectedRoute from '@/components/ProtectedRoute';
import ConfirmDialog from '@/components/ConfirmDialog';
import ApplicationStatusBadge from '@/components/ApplicationStatusBadge';
import { formatRelative } from '@/lib/format-date';
import type {
  ApplicationResponse,
  ApplicationStatus,
  InterviewRecommendation,
  InterviewScorecardSummary,
  Page,
  RecruiterDecisionRequest,
  ScreeningStaffResponse,
  ScreeningSummaryResponse,
} from '@/types';

// Statuses where the Shortlist action is disabled. Includes SCREENING_SENT
// because SCREENING_SENT -> SHORTLISTED is illegal in LEGAL_TRANSITIONS — the
// candidate must complete the screening (or be rejected/withdrawn) first.
const SHORTLIST_LOCKED: ReadonlyArray<ApplicationStatus> = [
  'SCREENING_SENT',
  'SHORTLISTED',
  'INTERVIEW_SCHEDULED',
  'INTERVIEWED',
  // Phase 2.3 — past Shortlist; shortlist would 400 against LEGAL_TRANSITIONS.
  'SELECTED_CONDITIONAL',
  'OFFERED',
  'ACCEPTED',
  'ONBOARDING',
  'ACTIVE',
  'COMPLETED',
];

export default function RecruiterReviewPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM']}>
      <DashboardLayout title="Review applicant">
        <Body />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body() {
  const params = useParams();
  const router = useRouter();
  const id =
    typeof params?.id === 'string'
      ? params.id
      : Array.isArray(params?.id)
        ? params.id[0]
        : null;

  const [app, setApp] = useState<ApplicationResponse | null>(null);
  const [allApps, setAllApps] = useState<ApplicationResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Decision panel state
  const [rating, setRating] = useState<number>(0);
  const [note, setNote] = useState<string>('');
  const [busy, setBusy] = useState<
    'shortlist' | 'reject' | 'send_screening' | 'conditional_select' | null
  >(null);
  const [rejectOpen, setRejectOpen] = useState(false);

  // Screening state. {screening} is the staff view (with score + answers when
  // COMPLETED). Null while loading or when the app has no screening yet.
  const [screening, setScreening] = useState<ScreeningStaffResponse | null>(null);
  const [screeningChecked, setScreeningChecked] = useState(false);

  // Phase 2.2 — latest interview scorecard for the application. Null until
  // the request resolves; remains null when no interview has feedback yet
  // (200 with body, 204, or 404 — all collapse to "no scorecard").
  const [scorecard, setScorecard] = useState<InterviewScorecardSummary | null>(null);

  const load = useCallback(async () => {
    if (!id) return;
    setError(null);
    try {
      const [appRes, listRes] = await Promise.all([
        api.get<ApplicationResponse>(`/api/v1/applications/${id}`),
        api.get<Page<ApplicationResponse>>('/api/v1/applications', {
          params: { size: 200 },
        }),
      ]);
      setApp(appRes.data);
      setAllApps(listRes.data?.content ?? []);
      setRating(appRes.data.recruiterRating ?? 0);
      setNote(appRes.data.recruiterNotes ?? '');
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't load this application.");
      setApp(null);
    }
    // Best-effort: a 404 here just means no screening has been sent yet,
    // which is the common case for APPLIED. Don't bubble the error.
    try {
      const screeningRes = await api.get<ScreeningStaffResponse>(
        `/api/v1/applications/${id}/screening`,
      );
      setScreening(screeningRes.data);
    } catch {
      setScreening(null);
    } finally {
      setScreeningChecked(true);
    }
    // Phase 2.2 — scorecard summary. Backend returns 204 when no interview
    // has feedback yet, which axios surfaces as a 2xx with empty body.
    try {
      const scorecardRes = await api.get<InterviewScorecardSummary>(
        `/api/v1/applications/${id}/scorecard`,
      );
      setScorecard(scorecardRes.status === 204 ? null : scorecardRes.data ?? null);
    } catch {
      setScorecard(null);
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const tabCounts = useMemo(() => {
    if (!allApps) return { applied: 0, shortlisted: 0, interviewed: 0 };
    let applied = 0;
    let shortlisted = 0;
    let interviewed = 0;
    for (const a of allApps) {
      if (a.status === 'APPLIED') applied++;
      else if (a.status === 'SHORTLISTED') shortlisted++;
      else if (
        a.status === 'INTERVIEW_SCHEDULED' ||
        a.status === 'INTERVIEWED'
      ) {
        interviewed++;
      }
    }
    return { applied, shortlisted, interviewed };
  }, [allApps]);

  async function sendScreening() {
    if (!app || busy) return;
    setBusy('send_screening');
    try {
      const res = await api.post<ScreeningSummaryResponse>(
        `/api/v1/applications/${app.id}/screening/send`,
      );
      // Re-fetch app + screening so the status badge + screening panel reflect
      // the transition. The send is idempotent — if already sent, the panel
      // simply re-renders with the existing screening.
      await load();
      toast.success(
        res.data.status === 'COMPLETED'
          ? 'Screening already completed by candidate.'
          : 'Screening sent to candidate.',
      );
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? "Couldn't send screening.");
    } finally {
      setBusy(null);
    }
  }

  async function sendConditionalSelect() {
    if (!app || busy) return;
    setBusy('conditional_select');
    try {
      const res = await api.post<ApplicationResponse>(
        `/api/v1/applications/${app.id}/conditional-select`,
      );
      setApp(res.data);
      toast.success('Conditional selection confirmation sent.');
    } catch (err: any) {
      // 400 from the lifecycle guard (illegal source state) is surfaced verbatim
      // so the recruiter sees "Cannot move application from ... to SELECTED_CONDITIONAL"
      // rather than a generic toast.
      toast.error(
        err?.response?.data?.error ?? "Couldn't send conditional selection.",
      );
    } finally {
      setBusy(null);
    }
  }

  async function callDecision(kind: 'shortlist' | 'reject') {
    if (!app || busy) return;
    const previous = app;
    const optimisticStatus: ApplicationStatus =
      kind === 'shortlist' ? 'SHORTLISTED' : 'REJECTED';
    const body: RecruiterDecisionRequest = {
      rating: rating > 0 ? rating : undefined,
      note: note.trim() || undefined,
    };
    setBusy(kind);
    setApp({
      ...app,
      status: optimisticStatus,
      recruiterRating: body.rating ?? app.recruiterRating,
      recruiterNotes: body.note ?? app.recruiterNotes,
    });
    try {
      const res = await api.post<ApplicationResponse>(
        `/api/v1/applications/${app.id}/${kind}`,
        body
      );
      setApp(res.data);
      setRating(res.data.recruiterRating ?? 0);
      setNote(res.data.recruiterNotes ?? '');
      toast.success(
        kind === 'shortlist' ? 'Shortlisted ✓' : 'Application rejected'
      );
    } catch (err: any) {
      setApp(previous);
      toast.error(
        err?.response?.data?.error ??
          (kind === 'shortlist' ? "Couldn't shortlist." : "Couldn't reject.")
      );
    } finally {
      setBusy(null);
      setRejectOpen(false);
    }
  }

  if (app === null && !error) return <ReviewSkeleton />;

  if (error && !app) {
    return (
      <div className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-700">
        <p className="mb-2">{error}</p>
        <Link
          href="/careers/erm"
          className="rounded border border-red-300 px-3 py-1 text-xs font-medium hover:bg-red-100"
        >
          Back to pipeline
        </Link>
      </div>
    );
  }

  if (!app) return null;

  const canShortlist =
    !SHORTLIST_LOCKED.includes(app.status) &&
    app.status !== 'REJECTED' &&
    app.status !== 'WITHDRAWN';
  const canReject = app.status !== 'REJECTED';
  // Screening is only legally sendable from APPLIED. Once SCREENING_SENT or
  // beyond, the existing screening row is shown; the button is hidden.
  const canSendScreening = app.status === 'APPLIED' && !screening;
  // Phase 2.3 — conditional selection is only legally sendable from INTERVIEWED.
  // We additionally require a submitted scorecard so the recruiter has the
  // 2.2 signal in front of them before committing to "selected, pending offer".
  const canSendConditional =
    app.status === 'INTERVIEWED' && scorecard != null;

  return (
    <>
      {/* (1) Filter tab strip */}
      <FilterTabs
        counts={tabCounts}
        currentStatus={app.status}
        onClick={() => router.push('/careers/erm')}
      />

      {/* (2) Main card */}
      <div className="rounded-lg border border-gray-200 bg-white p-5">
        <Link
          href="/careers/erm"
          className="mb-3 inline-flex items-center gap-1 text-xs text-gray-600 hover:text-gray-900"
        >
          <ArrowLeft className="h-3.5 w-3.5" strokeWidth={2} />
          Back to pipeline
        </Link>

        <HeaderRow app={app} />
        <MetaRow app={app} />

        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1.4fr_1fr]">
          <div className="space-y-4">
            <ResumeColumn app={app} />
            <ScorecardPanel scorecard={scorecard} />
            <ScreeningPanel
              screening={screening}
              checked={screeningChecked}
            />
          </div>
          <ActionPanel
            app={app}
            rating={rating}
            note={note}
            onRatingChange={setRating}
            onNoteChange={setNote}
            canShortlist={canShortlist}
            canReject={canReject}
            canSendScreening={canSendScreening}
            screeningStatus={screening?.status ?? null}
            canSendConditional={canSendConditional}
            busy={busy}
            onShortlist={() => void callDecision('shortlist')}
            onRejectClick={() => setRejectOpen(true)}
            onSendScreening={() => void sendScreening()}
            onSendConditional={() => void sendConditionalSelect()}
          />
        </div>
      </div>

      <ConfirmDialog
        open={rejectOpen}
        onClose={() => setRejectOpen(false)}
        onConfirm={() => callDecision('reject')}
        title="Reject this application?"
        description={`This marks ${app.candidateName ?? "the candidate's"} application as rejected. The action is logged in the audit history.`}
        confirmLabel="Reject"
        variant="danger"
      />
    </>
  );
}

// ── Filter tab strip ────────────────────────────────────────────────────────

function FilterTabs({
  counts,
  currentStatus,
  onClick,
}: {
  counts: { applied: number; shortlisted: number; interviewed: number };
  currentStatus: ApplicationStatus;
  onClick: () => void;
}) {
  // Highlight the pill that matches the current applicant's status; default to APPLIED.
  const activeKey: 'APPLIED' | 'SHORTLISTED' | 'INTERVIEWED' =
    currentStatus === 'SHORTLISTED'
      ? 'SHORTLISTED'
      : currentStatus === 'INTERVIEW_SCHEDULED' ||
          currentStatus === 'INTERVIEWED'
        ? 'INTERVIEWED'
        : 'APPLIED';
  const pills: { key: typeof activeKey; label: string; count: number }[] = [
    { key: 'APPLIED', label: 'Applied', count: counts.applied },
    { key: 'SHORTLISTED', label: 'Shortlisted', count: counts.shortlisted },
    { key: 'INTERVIEWED', label: 'Interviewed', count: counts.interviewed },
  ];
  return (
    <div className="mb-4 flex flex-wrap gap-2">
      {pills.map((p) => {
        const active = p.key === activeKey;
        return (
          <button
            key={p.key}
            type="button"
            onClick={onClick}
            className={
              'rounded-full border px-4 py-1.5 text-sm font-medium transition-colors ' +
              (active
                ? 'border-accent bg-accent/10 text-accent'
                : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50')
            }
          >
            {p.label} · {p.count}
          </button>
        );
      })}
    </div>
  );
}

// ── Header + meta ───────────────────────────────────────────────────────────

function HeaderRow({ app }: { app: ApplicationResponse }) {
  const initials = (app.candidateName ?? '?')
    .split(/\s+/)
    .filter(Boolean)
    .map((p) => p[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();
  return (
    <div className="mb-3 flex items-start justify-between gap-3">
      <div className="flex items-center gap-3">
        <div
          className="flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-full bg-accent/10 text-base font-semibold text-accent"
          aria-hidden="true"
        >
          {initials || '?'}
        </div>
        <div>
          <div className="text-base font-medium text-gray-900">
            {app.candidateName ?? '(unnamed candidate)'}
          </div>
          <div className="text-sm text-gray-500">
            {app.jobPostingTitle ?? '(unlinked posting)'}
          </div>
          {app.candidateEmail && (
            <div className="mt-0.5 flex items-center gap-1 text-xs text-gray-400">
              <Mail className="h-3 w-3" strokeWidth={2} />
              {app.candidateEmail}
            </div>
          )}
        </div>
      </div>
      <ApplicationStatusBadge status={app.status} />
    </div>
  );
}

function MetaRow({ app }: { app: ApplicationResponse }) {
  return (
    <div className="mb-4 flex flex-wrap gap-x-5 gap-y-2 text-sm text-gray-500">
      <span className="inline-flex items-center gap-1.5">
        <Briefcase className="h-4 w-4" strokeWidth={2} />— yrs experience
      </span>
      <span className="inline-flex items-center gap-1.5">
        <Calendar className="h-4 w-4" strokeWidth={2} />
        Applied {formatRelative(app.appliedAt)}
      </span>
      <span className="inline-flex items-center gap-1.5">
        <MapPin className="h-4 w-4" strokeWidth={2} />—
      </span>
    </div>
  );
}

// ── Resume column ───────────────────────────────────────────────────────────

function ResumeColumn({ app }: { app: ApplicationResponse }) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [loadingBlob, setLoadingBlob] = useState(false);
  const [blobError, setBlobError] = useState<string | null>(null);
  const filenameRef = useRef<string>(app.resumeFileName ?? 'resume.pdf');

  useEffect(() => {
    if (!app.resumeId) return;
    let cancelled = false;
    let createdUrl: string | null = null;
    setLoadingBlob(true);
    setBlobError(null);

    (async () => {
      try {
        const res = await api.get(`/api/v1/resumes/${app.resumeId}/download`, {
          responseType: 'blob',
        });
        if (cancelled) return;
        const ctRaw = res.headers['content-type'];
        const contentType =
          typeof ctRaw === 'string' ? ctRaw : 'application/octet-stream';

        const cdRaw = res.headers['content-disposition'];
        const cd = typeof cdRaw === 'string' ? cdRaw : '';
        const m = cd.match(/filename="?([^";]+)"?/);
        filenameRef.current = m?.[1] ?? app.resumeFileName ?? 'resume.pdf';

        const blob = new Blob([res.data], { type: contentType });
        createdUrl = URL.createObjectURL(blob);
        if (!cancelled) setBlobUrl(createdUrl);
      } catch (err: any) {
        if (!cancelled) {
          setBlobError(
            err?.response?.data?.error ?? "Couldn't load resume preview."
          );
        }
      } finally {
        if (!cancelled) setLoadingBlob(false);
      }
    })();

    return () => {
      cancelled = true;
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [app.resumeId, app.resumeFileName]);

  function handleViewFull() {
    if (!blobUrl) return;
    window.open(blobUrl, '_blank', 'noopener');
  }

  function handleDownload() {
    if (!blobUrl) return;
    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = filenameRef.current;
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  return (
    <section>
      <h3 className="mb-2 text-sm font-medium text-gray-900">Resume</h3>

      {!app.resumeId && (
        <div className="rounded-md border border-dashed border-gray-300 bg-gray-50 p-8 text-center text-sm text-gray-500">
          No resume uploaded.
        </div>
      )}

      {app.resumeId && loadingBlob && (
        <div className="flex h-[480px] items-center justify-center rounded-md border border-gray-200 bg-gray-50">
          <div className="flex flex-col items-center gap-2">
            <div className="h-8 w-8 animate-spin rounded-full border-2 border-accent border-t-transparent" />
            <p className="text-xs text-gray-500">Loading preview…</p>
          </div>
        </div>
      )}

      {app.resumeId && blobError && (
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {blobError}
        </div>
      )}

      {app.resumeId && blobUrl && (
        <iframe
          src={blobUrl}
          title={`Resume: ${filenameRef.current}`}
          className="h-[480px] w-full rounded-md border border-gray-200"
        />
      )}

      {app.resumeId && blobUrl && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={handleViewFull}
            className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50"
          >
            <ExternalLink className="h-3.5 w-3.5" strokeWidth={2} />
            View full
          </button>
          <button
            type="button"
            onClick={handleDownload}
            className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50"
          >
            <Download className="h-3.5 w-3.5" strokeWidth={2} />
            Download
          </button>
          {app.resumeFileName && (
            <span className="ml-1 text-xs text-gray-500">
              {app.resumeFileName}
            </span>
          )}
        </div>
      )}
    </section>
  );
}

// ── Action panel ────────────────────────────────────────────────────────────

function ActionPanel({
  app,
  rating,
  note,
  onRatingChange,
  onNoteChange,
  canShortlist,
  canReject,
  canSendScreening,
  screeningStatus,
  canSendConditional,
  busy,
  onShortlist,
  onRejectClick,
  onSendScreening,
  onSendConditional,
}: {
  app: ApplicationResponse;
  rating: number;
  note: string;
  onRatingChange: (v: number) => void;
  onNoteChange: (v: string) => void;
  canShortlist: boolean;
  canReject: boolean;
  canSendScreening: boolean;
  screeningStatus: 'SENT' | 'COMPLETED' | null;
  canSendConditional: boolean;
  busy:
    | 'shortlist'
    | 'reject'
    | 'send_screening'
    | 'conditional_select'
    | null;
  onShortlist: () => void;
  onRejectClick: () => void;
  onSendScreening: () => void;
  onSendConditional: () => void;
}) {
  return (
    <aside className="flex flex-col gap-3 rounded-md border border-gray-200 p-4">
      {canSendScreening && (
        <button
          type="button"
          onClick={onSendScreening}
          disabled={busy !== null}
          className="inline-flex w-full items-center justify-center gap-2 rounded-md border border-accent/30 bg-accent/5 px-4 py-2 text-sm font-semibold text-accent transition-colors hover:bg-accent/10 disabled:opacity-50"
        >
          {busy === 'send_screening' ? (
            <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-accent border-t-transparent" />
          ) : (
            <Send className="h-4 w-4" strokeWidth={2} />
          )}
          Send screening
        </button>
      )}
      {screeningStatus === 'SENT' && (
        <p className="rounded-md border border-slate-300 bg-slate-100 px-3 py-2 text-xs text-slate-700">
          Screening sent — waiting on the candidate.
        </p>
      )}
      {canSendConditional && (
        <button
          type="button"
          onClick={onSendConditional}
          disabled={busy !== null}
          className="inline-flex w-full items-center justify-center gap-2 rounded-md border border-brand-300 bg-brand-50 px-4 py-2 text-sm font-semibold text-brand-800 transition-colors hover:bg-brand-100 disabled:opacity-50"
        >
          {busy === 'conditional_select' ? (
            <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-brand-700 border-t-transparent" />
          ) : (
            <Send className="h-4 w-4" strokeWidth={2} />
          )}
          Send conditional selection
        </button>
      )}
      {app.status === 'SELECTED_CONDITIONAL' && (
        <p className="rounded-md border border-brand-200 bg-brand-50 px-3 py-2 text-xs text-brand-800">
          Conditional confirmation sent. HR will issue the formal offer next.
        </p>
      )}
      <div>
        <label className="mb-1.5 block text-sm font-medium text-gray-900">
          Your rating
        </label>
        <StarRating
          value={rating}
          onChange={onRatingChange}
          disabled={busy !== null}
        />
      </div>

      <div>
        <label
          htmlFor="recruiter-note"
          className="mb-1.5 block text-sm font-medium text-gray-900"
        >
          Note
        </label>
        <textarea
          id="recruiter-note"
          value={note}
          onChange={(e) => onNoteChange(e.target.value)}
          rows={3}
          placeholder="Why shortlist or reject?"
          disabled={busy !== null}
          className="min-h-[60px] w-full resize-y rounded-md border border-gray-300 p-2.5 text-sm focus:border-primary-700 focus:outline-none focus:ring-1 focus:ring-primary-700 disabled:bg-gray-50"
        />
      </div>

      <button
        type="button"
        onClick={onShortlist}
        disabled={!canShortlist || busy !== null}
        className="inline-flex w-full items-center justify-center gap-2 rounded-md bg-accent px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-accent-dark disabled:opacity-50"
      >
        {busy === 'shortlist' ? (
          <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white border-t-transparent" />
        ) : (
          <Check className="h-4 w-4" strokeWidth={2} />
        )}
        {app.status === 'SHORTLISTED' ? 'Shortlisted' : 'Shortlist'}
      </button>

      <button
        type="button"
        onClick={onRejectClick}
        disabled={!canReject || busy !== null}
        className="inline-flex w-full items-center justify-center gap-2 rounded-md border border-red-200 bg-white px-4 py-2 text-sm font-medium text-red-600 transition-colors hover:bg-red-50 disabled:opacity-50"
      >
        {busy === 'reject' ? (
          <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-red-400 border-t-transparent" />
        ) : (
          <X className="h-4 w-4" strokeWidth={2} />
        )}
        {app.status === 'REJECTED' ? 'Rejected' : 'Reject'}
      </button>

      {!canShortlist &&
        app.status !== 'REJECTED' &&
        app.status !== 'WITHDRAWN' && (
          <p className="text-xs text-gray-500">
            {app.status === 'SCREENING_SENT'
              ? 'Waiting on the candidate to complete the screening.'
              : 'This applicant is already past the shortlist stage.'}
          </p>
        )}
    </aside>
  );
}

// ── Scorecard panel (phase 2.2) ─────────────────────────────────────────────

const RECOMMENDATION_LABEL: Record<InterviewRecommendation, string> = {
  STRONG_HIRE: 'Strong Hire',
  HIRE: 'Hire',
  NO_HIRE: 'No Hire',
  STRONG_NO_HIRE: 'Strong No Hire',
};

const RECOMMENDATION_COLOR: Record<InterviewRecommendation, string> = {
  STRONG_HIRE: 'bg-green-100 text-green-800',
  HIRE: 'bg-brand-100 text-brand-800',
  NO_HIRE: 'bg-amber-100 text-amber-800',
  STRONG_NO_HIRE: 'bg-red-100 text-red-700',
};

/**
 * Surfaces the latest interview scorecard so the recruiter can advance/reject
 * with the recommendation + dimension scores visible. Renders nothing when no
 * interview on the application has feedback yet — the empty state is the
 * absence of the panel, which keeps the review screen tight.
 */
function ScorecardPanel({
  scorecard,
}: {
  scorecard: InterviewScorecardSummary | null;
}) {
  if (!scorecard) return null;
  const rec = scorecard.recommendation;
  const dims: { label: string; value?: number }[] = [
    { label: 'Tech', value: scorecard.technicalRating },
    { label: 'Comm', value: scorecard.communicationRating },
    { label: 'Problem', value: scorecard.problemSolvingRating },
  ];
  return (
    <section className="rounded-md border border-gray-200 bg-white p-4">
      <header className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <Star className="h-4 w-4 text-gray-500" strokeWidth={2} />
          <h3 className="text-sm font-medium text-gray-900">
            Interview scorecard
          </h3>
        </div>
        {rec && (
          <span
            className={
              'rounded-full px-2.5 py-0.5 text-xs font-semibold ' +
              RECOMMENDATION_COLOR[rec]
            }
          >
            {RECOMMENDATION_LABEL[rec]}
          </span>
        )}
      </header>
      <div className="mb-3 flex flex-wrap gap-2">
        {dims
          .filter((d) => typeof d.value === 'number')
          .map((d) => (
            <span
              key={d.label}
              className="rounded-md border border-gray-200 bg-gray-50 px-2.5 py-1 text-xs font-medium text-gray-700"
            >
              {d.label}: {d.value}/5
            </span>
          ))}
        {typeof scorecard.overallRating === 'number' && (
          <span className="rounded-md border border-accent/30 bg-accent/5 px-2.5 py-1 text-xs font-semibold text-accent-dark">
            Overall: {scorecard.overallRating}/5
          </span>
        )}
      </div>
      {scorecard.comments && (
        <p className="mb-2 whitespace-pre-wrap rounded-md border border-gray-200 bg-gray-50 p-2 text-xs text-gray-700">
          {scorecard.comments}
        </p>
      )}
      <p className="text-[11px] text-gray-500">
        Submitted by {scorecard.submittedByName ?? '—'}
        {scorecard.submittedAt
          ? ' · ' + formatRelative(scorecard.submittedAt)
          : ''}
      </p>
    </section>
  );
}

// ── Screening panel ─────────────────────────────────────────────────────────

function ScreeningPanel({
  screening,
  checked,
}: {
  screening: ScreeningStaffResponse | null;
  checked: boolean;
}) {
  // Render nothing until the GET resolves so we don't flash "no screening yet"
  // on first paint while the request is in flight.
  if (!checked) return null;
  if (!screening) return null;

  return (
    <section className="rounded-md border border-gray-200 bg-white p-4">
      <header className="mb-3 flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <ClipboardList className="h-4 w-4 text-gray-500" strokeWidth={2} />
          <h3 className="text-sm font-medium text-gray-900">Screening</h3>
        </div>
        {screening.status === 'COMPLETED' &&
        typeof screening.score === 'number' &&
        typeof screening.maxScore === 'number' ? (
          <span className="rounded-full bg-green-100 px-2.5 py-0.5 text-xs font-semibold text-green-800">
            Score: {screening.score} / {screening.maxScore}
          </span>
        ) : (
          <span className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-medium text-slate-700">
            Pending
          </span>
        )}
      </header>

      {screening.status === 'SENT' && (
        <p className="text-xs text-gray-500">
          The candidate hasn&apos;t completed the screening yet.
        </p>
      )}

      {screening.status === 'COMPLETED' && (
        <ol className="space-y-3">
          {screening.answers.map((a) => (
            <li
              key={a.questionId}
              className="rounded-md border border-gray-200 bg-gray-50 p-3 text-xs"
            >
              <div className="mb-1 font-medium text-gray-900">
                {a.orderIndex}. {a.prompt}
              </div>
              {a.type === 'SINGLE_CHOICE' && (
                <ul className="space-y-1">
                  {(a.choices ?? []).map((c, ci) => {
                    const isCandidate = a.choiceIndex === ci;
                    const isCorrect = a.correctChoiceIndex === ci;
                    const cls = isCandidate && isCorrect
                      ? 'border-green-300 bg-green-50 text-green-800'
                      : isCandidate
                        ? 'border-red-300 bg-red-50 text-red-800'
                        : isCorrect
                          ? 'border-green-200 bg-white text-green-700'
                          : 'border-gray-200 bg-white text-gray-600';
                    return (
                      <li
                        key={ci}
                        className={'flex items-center gap-2 rounded border px-2 py-1 ' + cls}
                      >
                        <span className="text-[10px] font-semibold uppercase tracking-wide">
                          {isCandidate ? 'Picked' : isCorrect ? 'Correct' : ''}
                        </span>
                        <span>{c}</span>
                      </li>
                    );
                  })}
                </ul>
              )}
              {a.type === 'FREE_TEXT' && (
                <p className="whitespace-pre-wrap rounded border border-gray-200 bg-white px-2 py-1.5 text-gray-700">
                  {a.freeText ?? <span className="italic text-gray-400">No answer</span>}
                </p>
              )}
              {a.type === 'SINGLE_CHOICE' && (
                <p className="mt-1 text-[11px] text-gray-500">
                  {a.awardedPoints ?? 0} / {a.points ?? 0} points
                </p>
              )}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

function StarRating({
  value,
  onChange,
  disabled,
}: {
  value: number;
  onChange: (v: number) => void;
  disabled?: boolean;
}) {
  return (
    <div
      className="flex items-center gap-1"
      role="radiogroup"
      aria-label="Rating"
    >
      {[1, 2, 3, 4, 5].map((n) => {
        const filled = n <= value;
        return (
          <button
            key={n}
            type="button"
            // Clicking the current rating again clears it back to 0 (unrated).
            onClick={() => onChange(n === value ? 0 : n)}
            disabled={disabled}
            role="radio"
            aria-checked={value === n}
            aria-label={`${n} star${n === 1 ? '' : 's'}`}
            className={
              'rounded p-0.5 transition-colors ' +
              (disabled ? 'cursor-not-allowed opacity-60' : 'hover:bg-amber-50')
            }
          >
            <Star
              className={
                'h-6 w-6 ' +
                (filled ? 'fill-amber-400 text-amber-400' : 'text-gray-300')
              }
              strokeWidth={1.5}
            />
          </button>
        );
      })}
    </div>
  );
}

// ── Skeleton ────────────────────────────────────────────────────────────────

function ReviewSkeleton() {
  return (
    <div className="space-y-4">
      <div className="flex gap-2">
        <div className="h-8 w-28 animate-pulse rounded-full bg-gray-200" />
        <div className="h-8 w-32 animate-pulse rounded-full bg-gray-200" />
        <div className="h-8 w-32 animate-pulse rounded-full bg-gray-200" />
      </div>
      <div className="space-y-3 rounded-lg border border-gray-200 bg-white p-5">
        <div className="flex items-start gap-3">
          <div className="h-11 w-11 animate-pulse rounded-full bg-gray-200" />
          <div className="space-y-2">
            <div className="h-4 w-48 animate-pulse rounded bg-gray-200" />
            <div className="h-3 w-32 animate-pulse rounded bg-gray-200" />
          </div>
        </div>
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1.4fr_1fr]">
          <div className="h-[480px] animate-pulse rounded-md bg-gray-100" />
          <div className="h-64 animate-pulse rounded-md bg-gray-100" />
        </div>
      </div>
    </div>
  );
}
