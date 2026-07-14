'use client';

import { Component, useCallback, useEffect, useState, type ErrorInfo, type ReactNode } from 'react';
import { useParams, useRouter, useSearchParams } from 'next/navigation';
import Link from 'next/link';
import api from '@/lib/api';
import StateBadge from '@/components/trainer/StateBadge';
import ProjectSlotIndicator from '@/components/trainer/ProjectSlotIndicator';
import ReportingStructureBadge from '@/components/trainer/ReportingStructureBadge';
import WebexHostStartCard from '@/components/meeting/WebexHostStartCard';
import WeeklyTrackerGrid from '@/components/trainer/weeklyTracker/WeeklyTrackerGrid';
import type {
  ActiveInternDetail,
  RecentMeetingRow,
  RecentProjectRow,
  RecentSubmissionRow,
  RecentTimesheetRow,
} from '@/components/trainer/types';

const POLL_MS = 60_000;

export default function ActiveInternDetailPage() {
  // useParams (next/navigation) is the standard client-component way to read
  // dynamic-route params on Next 14. The previous `use(props.params)` form
  // expects `params` to be a thenable (Next 15 behavior); on Next 14.2 it's a
  // plain object and React's `use()` throws minified error #438 because the
  // value is neither a Promise nor a Context.
  const params = useParams<{ lifecycleId: string }>();
  const lifecycleId = params?.lifecycleId ?? '';
  const sp = useSearchParams();
  const router = useRouter();
  const [d, setD] = useState<ActiveInternDetail | null>(null);
  const [err, setErr] = useState<string | null>(null);
  // One-shot: capture ?kt={projectId} on first render so even if Next
  // strips it later (or React re-renders before we consume it) the
  // child RecentProjectItem still gets the auto-open signal. After
  // we've read it, strip the param from the URL so a refresh or
  // back-navigation doesn't re-open the modal unintentionally.
  const [autoOpenKtProjectId] = useState<string | null>(
    () => sp?.get('kt') ?? null,
  );
  useEffect(() => {
    if (autoOpenKtProjectId == null) return;
    const remaining = new URLSearchParams(sp?.toString() ?? '');
    remaining.delete('kt');
    const qs = remaining.toString();
    if (typeof window !== 'undefined') {
      router.replace(
        window.location.pathname + (qs ? '?' + qs : ''),
        { scroll: false },
      );
    }
    // Run once on mount; sp/router refs are stable enough for this
    // one-shot cleanup. eslint-react-hooks would otherwise insist on
    // adding them and re-firing on every render, which is wrong here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const load = useCallback(async () => {
    if (!lifecycleId) return;
    try {
      const res = await api.get<ActiveInternDetail>(
        `/api/v1/trainer/active-interns/${lifecycleId}`,
      );
      setD(res.data);
      setErr(null);
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Failed to load intern detail');
    }
  }, [lifecycleId]);

  useEffect(() => {
    void load();
    const id = setInterval(() => {
      void load();
    }, POLL_MS);
    return () => clearInterval(id);
  }, [load]);

  if (err && !d) {
    return (
      <div className="mx-auto max-w-6xl p-6">
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      </div>
    );
  }
  if (!d) {
    return (
      <div className="mx-auto max-w-6xl space-y-3 p-6">
        <div className="h-8 w-72 animate-pulse rounded bg-slate-100" />
        <div className="h-48 animate-pulse rounded-lg bg-slate-100" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs text-slate-500">
            <Link href="/careers/trainer/active-interns" className="hover:text-slate-700">
              ← Active interns
            </Link>
          </p>
          <h1 className="mt-1 text-xl font-semibold text-slate-900">
            {d.intern.fullName ?? '(unknown)'}
          </h1>
          <p className="text-xs text-slate-500">
            {d.intern.employeeId ?? '—'} ·{' '}
            <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-700">
              {d.intern.technologyTitle ?? '—'}
            </span>
            {d.i983StatusBadge && (
              <span className="ml-2 rounded-full bg-amber-100 px-2 py-0.5 text-[11px] text-amber-800">
                {d.i983StatusBadge}
              </span>
            )}
          </p>
        </div>
        <div className="text-xs text-slate-500">
          <p>{d.intern.email ?? '—'}</p>
          <p>{d.intern.phone ?? '—'}</p>
        </div>
      </header>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card title="Profile">
          <dl className="space-y-1 text-xs">
            <Row k="Email" v={d.intern.email} />
            <Row k="Phone" v={d.intern.phone} />
            <Row k="Technology" v={d.intern.technologyTitle} />
            <Row k="Start date" v={d.intern.startDate} />
            <Row k="Employee ID" v={d.intern.employeeId} />
          </dl>
          <div className="mt-3 border-t border-slate-100 pt-3">
            <h4 className="mb-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              Reporting structure
            </h4>
            <ReportingStructureBadge reporting={d.summary.reportingStructure} />
          </div>
        </Card>

        <Card title="Signed offer" subtitle="Per ANVI policy (Trainer view)">
          <dl className="space-y-1 text-xs">
            <Row k="Role" v={d.signedOffer.roleTitle} />
            <Row k="Compensation" v={d.signedOffer.compensationSummary} />
            <Row k="Tentative start" v={d.signedOffer.tentativeStartDate} />
            <Row
              k="Signed at"
              v={
                d.signedOffer.signedAt
                  ? new Date(d.signedOffer.signedAt).toLocaleString()
                  : null
              }
            />
          </dl>
        </Card>

        <Card title="Current month state">
          <div className="mb-2 flex items-center justify-between">
            <span className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              Projects
            </span>
            <ProjectSlotIndicator
              project1={d.summary.currentMonthProjects.project1}
              project2={d.summary.currentMonthProjects.project2}
              monthYear={d.summary.currentMonthProjects.monthYear}
            />
          </div>
          <div className="flex flex-wrap items-center gap-2 text-xs text-slate-700">
            <span>Meeting:</span>{' '}
            <StateBadge state={d.summary.weeklyMeeting.state} />
          </div>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-slate-700">
            <span>Evaluation:</span>{' '}
            <StateBadge state={d.summary.evaluation.state} />
          </div>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-slate-700">
            <span>Timesheet:</span>{' '}
            <StateBadge state={d.summary.timesheet.state} />
          </div>
        </Card>
      </div>

      {/* Weekly-sessions tracker for this intern — month grid with
          Schedule / Mark-done actions per week. Sits above the
          2-column card grid so the strip has full horizontal room. */}
      <WeeklyTrackerGrid internLifecycleId={lifecycleId} compact />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <CardWithCta
          title="Recent projects"
          cta="Assign new project"
          ctaDisabled={false}
          ctaHref={`/careers/trainer/assign-project?internId=${lifecycleId}&month=${d.summary.currentMonthProjects.monthYear ?? ''}`}
        >
          {!d.recentProjects || d.recentProjects.length === 0 ? (
            <Empty />
          ) : (
            <ul className="space-y-2">
              {d.recentProjects.map((p) => (
                <RowErrorBoundary key={p.id} label={p.title ?? '(untitled project)'}>
                  <RecentProjectItem
                    p={p}
                    internUserId={d.intern?.userId ?? null}
                    onChanged={() => void load()}
                    autoOpenKt={autoOpenKtProjectId === p.id}
                  />
                </RowErrorBoundary>
              ))}
            </ul>
          )}
        </CardWithCta>

        <CardWithCta
          title="Recent weekly meetings"
          cta="Schedule new"
          ctaDisabled={false}
          ctaHref={`/careers/trainer/weekly-meetings?internId=${lifecycleId}&action=new`}
        >
          {d.recentMeetings.length === 0 ? (
            <Empty />
          ) : (
            <ul className="space-y-2">
              {d.recentMeetings.map((m) => (
                <RecentMeetingItem key={m.id} m={m} />
              ))}
            </ul>
          )}
        </CardWithCta>

        <CardWithCta
          title="Recent submissions"
          cta="Review pending"
          ctaDisabled={false}
          ctaHref={`/careers/trainer/pending-reviews?intern=${lifecycleId}`}
        >
          {d.recentSubmissions.length === 0 ? (
            <Empty />
          ) : (
            <ul className="space-y-2">
              {d.recentSubmissions.map((s) => (
                <RecentSubmissionItem key={s.id} s={s} />
              ))}
            </ul>
          )}
        </CardWithCta>

        <Card title="Recent timesheets" subtitle="Read-only (Evaluator/Manager owns approval)">
          {d.recentTimesheets.length === 0 ? (
            <Empty />
          ) : (
            <ul className="space-y-2">
              {d.recentTimesheets.map((t) => (
                <RecentTimesheetItem key={t.id} t={t} />
              ))}
            </ul>
          )}
        </Card>
      </div>

      <Card title="Recent activity" subtitle="Trainer-visible audit events">
        {d.recentActivity.length === 0 ? (
          <Empty />
        ) : (
          <ul className="space-y-1.5 text-xs">
            {d.recentActivity.slice(0, 20).map((a, i) => (
              <li
                key={i}
                className="flex items-center justify-between border-b border-slate-100 pb-1 last:border-0"
              >
                <span className="truncate">
                  <span className="font-medium text-slate-900">
                    {a.actorName ?? 'system'}
                  </span>{' '}
                  <span className="text-slate-600">
                    {a.action ?? '—'} on {a.entityType ?? '—'}
                  </span>
                </span>
                <span className="text-[10px] tabular-nums text-slate-500">
                  {a.at ? new Date(a.at).toLocaleString() : '—'}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}

function Card({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <header className="mb-2">
        <h3 className="text-sm font-semibold text-slate-900">{title}</h3>
        {subtitle && <p className="text-[11px] text-slate-500">{subtitle}</p>}
      </header>
      {children}
    </section>
  );
}

function CardWithCta({
  title,
  cta,
  ctaDisabled,
  ctaTip,
  ctaHref,
  children,
}: {
  title: string;
  cta: string;
  ctaDisabled: boolean;
  ctaTip?: string;
  ctaHref?: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <header className="mb-2 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-900">{title}</h3>
        {!ctaDisabled && ctaHref ? (
          <Link
            href={ctaHref}
            title={ctaTip}
            className="rounded-md border border-brand-300 bg-brand-50 px-2 py-0.5 text-[11px] font-semibold text-brand-800 hover:bg-brand-100"
          >
            {cta}
          </Link>
        ) : (
          <button
            type="button"
            disabled={ctaDisabled}
            title={ctaTip}
            className="rounded-md border border-slate-200 px-2 py-0.5 text-[11px] font-medium text-slate-500 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {cta}
          </button>
        )}
      </header>
      {children}
    </section>
  );
}

function Row({ k, v }: { k: string; v: string | null | undefined }) {
  return (
    <div className="flex justify-between border-b border-slate-100 pb-0.5">
      <span className="text-slate-500">{k}</span>
      <span className="text-slate-800">{v ?? '—'}</span>
    </div>
  );
}

function Empty() {
  return <p className="text-xs text-slate-500">Nothing yet.</p>;
}

function RecentProjectItem({
  p, internUserId, onChanged, autoOpenKt,
}: {
  p: RecentProjectRow;
  internUserId: string | null;
  onChanged: () => void;
  /** Set true when the parent received ?kt={p.id} on the URL — the
   *  Mark-KT-done modal opens on mount so the trainer arrives in one
   *  click from the active-interns table KT button. One-shot: the
   *  parent strips the query param after the first read so a refresh
   *  doesn't re-open the modal. */
  autoOpenKt?: boolean;
}) {
  const [ktOpen, setKtOpen] = useState(autoOpenKt ?? false);
  const [ktScheduleOpen, setKtScheduleOpen] = useState(false);
  const ktDone = p.ktStatus === 'DONE';
  const hasScheduledSession = !!p.ktZoomMeetingId;
  return (
    <li className="rounded-md border border-slate-100 p-2 text-xs">
      <div className="flex items-baseline justify-between">
        <span className="font-medium text-slate-900">{p.title ?? '(untitled)'}</span>
        <span className="text-[10px] text-slate-500">
          {p.monthYear ?? '—'}
          {p.projectNumber ? ` · #${p.projectNumber}` : ''}
        </span>
      </div>
      <div className="mt-0.5 text-slate-600">
        Status: <strong>{p.status}</strong>
        {p.dueDate ? ` · due ${p.dueDate}` : ''}
        {p.reviewedAt ? ' · reviewed' : ''}
      </div>
      <div className="mt-1 flex flex-wrap items-center gap-2">
        <span className={
          'rounded-full px-2 py-0.5 text-[10px] font-medium '
          + (ktDone
              ? 'bg-green-100 text-green-800'
              : 'bg-slate-100 text-slate-700')
        }>
          KT: {ktDone ? 'Done' : 'Not done'}
          {ktDone && p.ktCompletedAt
            ? ' · ' + new Date(p.ktCompletedAt).toLocaleDateString()
            : ''}
        </span>
        {ktDone && p.ktMeetingLink && (
          <a
            href={p.ktMeetingLink}
            target="_blank"
            rel="noreferrer"
            className="text-[10px] text-brand-700 hover:underline"
          >
            meeting link
          </a>
        )}
        <div className="ml-auto flex flex-wrap items-center gap-1.5">
          <RepoAccessControls
            projectId={p.id}
            internUserId={internUserId}
            onChanged={onChanged}
          />
          <button
            type="button"
            onClick={() => setKtScheduleOpen(true)}
            className="rounded-md border border-slate-200 px-2 py-0.5 text-[10px] font-medium text-slate-700 hover:bg-slate-50"
            title={hasScheduledSession
              ? 'Reschedule the KT Zoom session'
              : 'Schedule a live KT Zoom session — intern is notified, '
                + 'trainer gets the host link'}
          >
            {hasScheduledSession ? 'Reschedule KT' : 'Schedule KT session'}
          </button>
          <button
            type="button"
            onClick={() => setKtOpen(true)}
            className="rounded-md border border-slate-200 px-2 py-0.5 text-[10px] font-medium text-slate-700 hover:bg-slate-50"
          >
            {ktDone ? 'Update KT' : 'Mark KT done'}
          </button>
        </div>
      </div>

      {/* Scheduled KT session block — appears when a Zoom meeting exists
          for this project AND KT isn't done yet. Mirrors the
          WeeklyMeeting host card pattern: show the date + meeting id,
          then the WebexHostStartCard which fetches a fresh start_url on
          demand (~2h zak expiry). Once the trainer marks KT done the
          host-link surface disappears (status is captured by the
          "KT: Done · {date}" pill above + the symmetric intern-side
          compact pill); the underlying ktZoom* fields stay on the row
          for audit but are never re-exposed as a live link. */}
      {hasScheduledSession && !ktDone && (
        <div className="mt-2 rounded-md border border-slate-200 bg-slate-50 p-2">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <span className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              KT session scheduled
            </span>
            {p.ktScheduledFor && (
              <span className="text-[10px] text-slate-700">
                {new Date(p.ktScheduledFor).toLocaleString()}
                {p.ktTimezone ? ` (${p.ktTimezone})` : ''}
                {p.ktDurationMinutes ? ` · ${p.ktDurationMinutes} min` : ''}
              </span>
            )}
          </div>
          <div className="mt-1.5">
            <WebexHostStartCard
              providerMeetingId={p.ktZoomMeetingId ?? null}
              startUrl={p.ktZoomStartUrl ?? null}
            />
          </div>
        </div>
      )}

      {ktOpen && (
        <KtMarkModal
          projectId={p.id}
          initialLink={p.ktMeetingLink}
          alreadyDone={ktDone}
          onClose={() => setKtOpen(false)}
          onSaved={() => { setKtOpen(false); onChanged(); }}
        />
      )}
      {ktScheduleOpen && (
        <KtScheduleModal
          projectId={p.id}
          projectTitle={p.title}
          existing={hasScheduledSession ? {
            scheduledFor: p.ktScheduledFor ?? null,
            durationMinutes: p.ktDurationMinutes ?? null,
            timezone: p.ktTimezone ?? null,
          } : null}
          onClose={() => setKtScheduleOpen(false)}
          onSaved={() => { setKtScheduleOpen(false); onChanged(); }}
        />
      )}
    </li>
  );
}

interface AssignmentBrief {
  id: string;
  status: string;
  accessGranted: boolean | null;
  intern: { id: string; githubUsername: string | null } | null;
  project: {
    id: string;
    repository: { repositoryName: string | null; repositoryUrl: string | null } | null;
  } | null;
}

/**
 * Composite "Link repository → Grant repo access → Granted" affordance.
 * The trainer's recent-projects feed gives us project ids but not
 * assignment ids — we look the (assignment + repo) up on mount and drive
 * a single button that advances to the next outstanding step:
 *
 *  no repo link        → "Link repository" (opens {@link LinkRepoModal})
 *  link but no grant   → "Grant repo access" (with "Edit repo" alongside)
 *  granted             → "Repo access ✓" pill (title shows the linked URL,
 *                        + an Edit affordance for updating the URL later)
 *
 * The Grant call rejects without a repo link (backend gates it), so
 * sequencing here matches that gate and avoids the 400.
 */
function RepoAccessControls({
  projectId, internUserId, onChanged,
}: { projectId: string; internUserId: string | null; onChanged: () => void }) {
  // CRITICAL: every hook must be called UNCONDITIONALLY in the same order
  // every render — React error #438 ("Should have a queue. You are likely
  // calling Hooks conditionally") fires when the hook sequence differs
  // between renders. Inline useEffect (matching the old working
  // GrantRepoAccessButton) instead of a useCallback+useEffect duo so the
  // dep-array surface is as small as possible. Refresh on demand via
  // bumping `refreshKey`.
  const [assignment, setAssignment] = useState<AssignmentBrief | null>(null);
  const [resolving, setResolving] = useState(true);
  const [granting, setGranting] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [justGranted, setJustGranted] = useState(false);
  const [linkOpen, setLinkOpen] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    if (!internUserId) {
      setResolving(false);
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const res = await api.get<AssignmentBrief[]>(
          `/api/v1/project-assignments/by-project/${projectId}`,
        );
        if (cancelled) return;
        const list = Array.isArray(res?.data) ? res.data : [];
        const mine = list.find((a) => a?.intern?.id === internUserId);
        setAssignment(mine ?? null);
        setErr(null);
      } catch (e) {
        if (cancelled) return;
        const ax = e as { response?: { data?: { error?: string } } };
        setErr(ax.response?.data?.error ?? 'Lookup failed');
        setAssignment(null);
      } finally {
        if (!cancelled) setResolving(false);
      }
    })();
    return () => { cancelled = true; };
  }, [projectId, internUserId, refreshKey]);

  async function grant() {
    if (!assignment) return;
    setGranting(true);
    setErr(null);
    try {
      await api.post(`/api/v1/project-assignments/${assignment.id}/access-granted`);
      setJustGranted(true);
      onChanged();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } } };
      setErr(ax.response?.data?.error ?? 'Grant failed');
    } finally {
      setGranting(false);
    }
  }

  function refresh() {
    setRefreshKey((k) => k + 1);
  }

  // Hide while the lookup is in flight.
  if (resolving) return null;
  // No assignment row (catalog-only project or legacy data without one).
  if (!assignment) return null;

  const repo = assignment.project?.repository ?? null;
  const hasRepo = !!repo?.repositoryUrl;
  const accessGranted = assignment.accessGranted === true || justGranted;

  // Modal wrapper used for both create + update.
  const modal = linkOpen && (
    <LinkRepoModal
      projectId={projectId}
      existing={repo}
      onClose={() => setLinkOpen(false)}
      onSaved={() => {
        setLinkOpen(false);
        refresh();
        onChanged();
      }}
    />
  );

  if (!hasRepo) {
    return (
      <span className="inline-flex flex-col items-end gap-0.5">
        <button
          type="button"
          onClick={() => setLinkOpen(true)}
          title="Link the GitHub repository for this project so access can be granted."
          className="rounded-md border border-brand-300 bg-brand-50 px-2 py-0.5 text-[10px] font-semibold text-brand-800 hover:bg-brand-100"
        >
          Link repository
        </button>
        {err && <span className="text-[10px] text-red-700">{err}</span>}
        {modal}
      </span>
    );
  }

  // Past this point `hasRepo === true` so the runtime guarantees repo +
  // repo.repositoryUrl are non-null. Belt-and-suspenders optional chaining
  // (?? '') protects against any future change to that invariant.
  const repoUrl = repo?.repositoryUrl ?? '';

  if (accessGranted) {
    return (
      <span className="inline-flex items-center gap-1">
        <span
          className="inline-flex items-center gap-1 rounded-md border border-green-200 bg-green-50 px-2 py-0.5 text-[10px] font-medium text-green-800"
          title={repoUrl || 'Repo access already granted'}
        >
          Repo access ✓
        </span>
        <button
          type="button"
          onClick={() => setLinkOpen(true)}
          title="Update the linked repository URL"
          className="rounded-md border border-slate-200 px-2 py-0.5 text-[10px] font-medium text-slate-600 hover:bg-slate-50"
        >
          Edit repo
        </button>
        {modal}
      </span>
    );
  }

  return (
    <span className="inline-flex flex-col items-end gap-0.5">
      <span className="inline-flex items-center gap-1">
        <button
          type="button"
          onClick={grant}
          disabled={granting}
          title={repoUrl ? `Grant the intern access to ${repoUrl}` : 'Grant repo access'}
          className="rounded-md border border-brand-300 bg-brand-50 px-2 py-0.5 text-[10px] font-semibold text-brand-800 hover:bg-brand-100 disabled:opacity-60"
        >
          {granting ? 'Granting…' : 'Grant repo access'}
        </button>
        <button
          type="button"
          onClick={() => setLinkOpen(true)}
          title="Update the linked repository URL"
          className="rounded-md border border-slate-200 px-2 py-0.5 text-[10px] font-medium text-slate-600 hover:bg-slate-50"
        >
          Edit repo
        </button>
      </span>
      {err && <span className="text-[10px] text-red-700">{err}</span>}
      {modal}
    </span>
  );
}

/**
 * Tiny error boundary used per row in the recent-projects list. If one row's
 * lazy fetch / render throws (e.g. a malformed AssignmentBrief payload, a
 * null where we expected an object), it falls back to an inline "couldn't
 * load this row" message so the rest of the page still renders — no more
 * single-row crashes turning the whole detail page into a 500.
 */
class RowErrorBoundary extends Component<
  { label: string; children: ReactNode },
  { hasError: boolean; message: string | null }
> {
  state = { hasError: false, message: null as string | null };

  static getDerivedStateFromError(error: Error) {
    return { hasError: true, message: error?.message ?? 'render failed' };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // eslint-disable-next-line no-console
    console.error('[ActiveInternDetail] row render failed:', error, info);
  }

  render() {
    if (this.state.hasError) {
      return (
        <li className="rounded-md border border-red-200 bg-red-50 p-2 text-[11px] text-red-800">
          Couldn&apos;t render row for <strong>{this.props.label}</strong>
          {this.state.message ? ' — ' + this.state.message : ''}.
        </li>
      );
    }
    return this.props.children;
  }
}

function LinkRepoModal({
  projectId, existing, onClose, onSaved,
}: {
  projectId: string;
  existing: { repositoryName: string | null; repositoryUrl: string | null } | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const isEdit = !!existing?.repositoryUrl;
  const [name, setName] = useState(existing?.repositoryName ?? '');
  const [url, setUrl] = useState(existing?.repositoryUrl ?? '');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const trimmedUrl = url.trim();
  const urlValid = trimmedUrl === '' ? false : isGithubRepoUrl(trimmedUrl);
  const urlError = trimmedUrl !== '' && !urlValid
    ? 'Must be https://github.com/owner/repo'
    : null;

  // Default the display name to "owner/repo" parsed from the URL when the
  // trainer hasn't typed one.
  const effectiveName = (() => {
    if (name.trim()) return name.trim();
    const parsed = parseOwnerRepo(trimmedUrl);
    return parsed ? `${parsed.owner}/${parsed.repo}` : '';
  })();

  async function save() {
    if (!urlValid || !effectiveName) return;
    setBusy(true);
    setErr(null);
    try {
      const body = {
        repositoryName: effectiveName,
        repositoryUrl: trimmedUrl,
      };
      if (isEdit) {
        await api.put(`/api/v1/projects/catalog/${projectId}/repository`, body);
      } else {
        await api.post(`/api/v1/projects/catalog/${projectId}/repository`, body);
      }
      onSaved();
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Failed to save repository link');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="w-full max-w-md rounded-lg bg-white p-5 shadow-xl">
        <h3 className="text-base font-semibold text-slate-900">
          {isEdit ? 'Update linked repository' : 'Link repository'}
        </h3>
        <p className="mt-1 text-xs text-slate-500">
          {isEdit
            ? 'Update the GitHub repository linked to this project. Existing intern access stays granted.'
            : 'Attach the GitHub repository for this project. Required before the "Grant repo access" button enables.'}
        </p>
        <div className="mt-4 space-y-3">
          <div>
            <label className="text-xs font-medium text-slate-800">
              Repository URL <span className="text-red-500">*</span>
            </label>
            <input
              type="url"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder="https://github.com/owner/repo"
              className={
                'mt-1 w-full rounded-md border px-3 py-2 text-sm '
                + (urlError ? 'border-red-400' : 'border-slate-200')
              }
            />
            {urlError && (
              <p className="mt-1 text-xs text-red-700">{urlError}</p>
            )}
          </div>
          <div>
            <label className="text-xs font-medium text-slate-800">
              Display name{' '}
              <span className="text-slate-500">(optional — defaults to owner/repo)</span>
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={effectiveName || 'owner/repo'}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </div>
          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              {err}
            </p>
          )}
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={save}
            disabled={busy || !urlValid || !effectiveName}
            className="rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {busy ? 'Saving…' : (isEdit ? 'Save' : 'Link repository')}
          </button>
        </div>
      </div>
    </div>
  );
}

/** GitHub repo URL: https://github.com/owner/repo (optional .git / trailing /). */
function isGithubRepoUrl(s: string): boolean {
  return /^https?:\/\/github\.com\/[\w.-]+\/[\w.-]+?(?:\.git)?\/?$/i.test(s.trim());
}

function parseOwnerRepo(s: string): { owner: string; repo: string } | null {
  const m = /^https?:\/\/github\.com\/([\w.-]+)\/([\w.-]+?)(?:\.git)?\/?$/i.exec(s.trim());
  if (!m) return null;
  return { owner: m[1], repo: m[2] };
}


function KtMarkModal({
  projectId, initialLink, alreadyDone, onClose, onSaved,
}: {
  projectId: string;
  initialLink: string | null;
  alreadyDone: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [link, setLink] = useState(initialLink ?? '');
  const [notes, setNotes] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const trimmedLink = link.trim();
  const linkError = trimmedLink === '' ? null : validateUrl(trimmedLink);

  async function submit() {
    if (linkError) return;
    setBusy(true);
    setErr(null);
    try {
      await api.post(`/api/v1/projects/${projectId}/kt-done`, {
        meetingLink: trimmedLink || null,
        notes: notes.trim() || null,
      });
      onSaved();
    } catch (e: any) {
      setErr(e?.response?.data?.error ?? 'Mark KT failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="w-full max-w-md rounded-lg bg-white p-5 shadow-xl">
        <h3 className="text-base font-semibold text-slate-900">
          {alreadyDone ? 'Update KT details' : 'Mark KT done'}
        </h3>
        <p className="mt-1 text-xs text-slate-500">
          {alreadyDone
            ? 'Updates the meeting link / notes for this project. The original completion timestamp is preserved.'
            : 'Records the Knowledge Transfer session as complete and notifies the intern. Meeting link + notes are optional.'}
        </p>
        <div className="mt-4 space-y-3">
          <div>
            <label className="text-xs font-medium text-slate-800">
              Meeting link <span className="text-slate-500">(optional)</span>
            </label>
            <input
              type="url"
              value={link}
              onChange={(e) => setLink(e.target.value)}
              placeholder="https://zoom.us/j/… or a recording URL"
              className={
                'mt-1 w-full rounded-md border px-3 py-2 text-sm '
                + (linkError ? 'border-red-400' : 'border-slate-200')
              }
            />
            {linkError && (
              <p className="mt-1 text-xs text-red-700">{linkError}</p>
            )}
          </div>
          <div>
            <label className="text-xs font-medium text-slate-800">
              Notes <span className="text-slate-500">(optional)</span>
            </label>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={3}
              maxLength={5000}
              placeholder="Topics covered, recording link, follow-ups…"
              className="mt-1 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </div>
          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              {err}
            </p>
          )}
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={busy || Boolean(linkError)}
            className="rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {busy ? 'Saving…' : (alreadyDone ? 'Save' : 'Mark KT done')}
          </button>
        </div>
      </div>
    </div>
  );
}

function validateUrl(s: string): string | null {
  try {
    const u = new URL(s);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') {
      return 'Must be http:// or https://';
    }
    if (!u.hostname) return 'URL is missing a hostname';
    return null;
  } catch {
    return 'Not a valid URL';
  }
}

/**
 * Schedule a live KT Zoom session for a project. Mirrors the weekly-
 * meeting schedule modal: date/time + duration + timezone + optional
 * topic/agenda. Backend creates the Zoom meeting and notifies the
 * intern (internal mail + in-app) with the join URL. Trainer gets a
 * separate host-link email.
 *
 * Independent of Mark KT done — re-opening this modal after a session
 * is on the calendar pre-fills the existing values and re-creates the
 * Zoom meeting on save (the old one is left orphaned — rare enough we
 * skip a delete/handshake).
 */
function KtScheduleModal({
  projectId, projectTitle, existing, onClose, onSaved,
}: {
  projectId: string;
  projectTitle: string | null;
  existing: {
    scheduledFor: string | null;
    durationMinutes: number | null;
    timezone: string | null;
  } | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  // Format helpers — datetime-local input wants 'YYYY-MM-DDTHH:mm'.
  function toLocalInput(iso: string | null): string {
    if (!iso) return '';
    const d = new Date(iso);
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
      + `T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }
  const browserTz = (() => {
    try { return Intl.DateTimeFormat().resolvedOptions().timeZone; }
    catch { return 'UTC'; }
  })();

  const [localDt, setLocalDt] = useState(toLocalInput(existing?.scheduledFor ?? null));
  const [durationMin, setDurationMin] = useState<number>(
    existing?.durationMinutes ?? 30,
  );
  const [timezone, setTimezone] = useState<string>(
    existing?.timezone ?? browserTz,
  );
  const [topic, setTopic] = useState('');
  const [agenda, setAgenda] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    if (!localDt) { setErr('Pick a date and time'); return; }
    setBusy(true);
    setErr(null);
    try {
      // datetime-local has no tz suffix; treat as local time and
      // convert to an ISO instant. The user-selected `timezone` is
      // sent separately for display formatting; the actual schedule
      // semantics live in the instant.
      const isoInstant = new Date(localDt).toISOString();
      await api.post(`/api/v1/projects/${projectId}/kt-schedule`, {
        scheduledFor: isoInstant,
        durationMinutes: durationMin,
        timezone,
        topic: topic.trim() || null,
        agenda: agenda.trim() || null,
      });
      onSaved();
    } catch (e: any) {
      setErr(e?.response?.data?.error
        ?? e?.message ?? 'Schedule KT failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4">
      <div className="w-full max-w-md rounded-lg bg-white p-5 shadow-xl">
        <h3 className="text-base font-semibold text-slate-900">
          {existing ? 'Reschedule KT session' : 'Schedule KT session'}
        </h3>
        <p className="mt-1 text-xs text-slate-500">
          {projectTitle ? `For project "${projectTitle}". ` : ''}
          Backend creates a Zoom meeting; the intern is notified with the
          join link, and you&rsquo;ll get the host start link in a
          separate email. Independent of Mark&nbsp;KT&nbsp;done.
        </p>
        <div className="mt-4 space-y-3">
          <div>
            <label className="text-xs font-medium text-slate-800">
              Date + time*
            </label>
            <input
              type="datetime-local"
              value={localDt}
              onChange={(e) => setLocalDt(e.target.value)}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-xs font-medium text-slate-800">
                Duration
              </label>
              <select
                value={durationMin}
                onChange={(e) => setDurationMin(Number(e.target.value))}
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
              >
                <option value={15}>15 min</option>
                <option value={30}>30 min</option>
                <option value={45}>45 min</option>
                <option value={60}>60 min</option>
                <option value={90}>90 min</option>
                <option value={120}>120 min</option>
              </select>
            </div>
            <div>
              <label className="text-xs font-medium text-slate-800">
                Timezone
              </label>
              <input
                type="text"
                value={timezone}
                onChange={(e) => setTimezone(e.target.value)}
                placeholder="Asia/Kolkata"
                className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
              />
            </div>
          </div>
          <div>
            <label className="text-xs font-medium text-slate-800">
              Topic <span className="text-slate-500">(optional)</span>
            </label>
            <input
              type="text"
              value={topic}
              onChange={(e) => setTopic(e.target.value)}
              maxLength={200}
              placeholder={`KT Session — ${projectTitle ?? 'your project'}`}
              className="mt-1 w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label className="text-xs font-medium text-slate-800">
              Agenda <span className="text-slate-500">(optional)</span>
            </label>
            <textarea
              value={agenda}
              onChange={(e) => setAgenda(e.target.value)}
              rows={3}
              maxLength={2000}
              placeholder="What you'll walk the intern through…"
              className="mt-1 w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </div>
          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              {err}
            </p>
          )}
        </div>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={busy || !localDt}
            className="rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
          >
            {busy ? 'Scheduling…' : (existing ? 'Reschedule' : 'Schedule')}
          </button>
        </div>
      </div>
    </div>
  );
}

function RecentMeetingItem({ m }: { m: RecentMeetingRow }) {
  return (
    <li className="rounded-md border border-slate-100 p-2 text-xs">
      <div className="flex items-baseline justify-between">
        <span className="font-medium text-slate-900">{m.topic ?? '(no topic)'}</span>
        <span className="text-[10px] text-slate-500">
          {m.scheduledFor ? new Date(m.scheduledFor).toLocaleString() : '—'}
        </span>
      </div>
      <div className="mt-0.5 flex flex-wrap items-center gap-2 text-slate-600">
        <StateBadge state={mapMeetingStatus(m.status)} variant="meeting" />
        {m.notesExcerpt && <span>· {m.notesExcerpt}</span>}
      </div>
    </li>
  );
}

function mapMeetingStatus(s: string): string {
  if (s === 'NO_SHOW') return 'MISSED';
  if (s === 'CANCELLED') return 'RESCHEDULED';
  return s;
}

function RecentSubmissionItem({ s }: { s: RecentSubmissionRow }) {
  return (
    <li className="rounded-md border border-slate-100 p-2 text-xs">
      <div className="flex items-baseline justify-between">
        <span className="font-medium text-slate-900">
          {s.projectTitle ?? '(unknown project)'}
        </span>
        <span className="text-[10px] text-slate-500">
          {s.submittedAt ? new Date(s.submittedAt).toLocaleString() : '—'}
        </span>
      </div>
      <div className="mt-0.5 text-slate-600">
        Tech {s.technicalScore ?? '—'}/5 · Comm {s.communicationScore ?? '—'}/5
        {s.nextAction ? ' · Next: ' + s.nextAction : ''}
      </div>
    </li>
  );
}

function RecentTimesheetItem({ t }: { t: RecentTimesheetRow }) {
  return (
    <li className="flex items-center justify-between rounded-md border border-slate-100 p-2 text-xs">
      <span className="font-medium text-slate-900">{t.weekStart ?? '—'}</span>
      <span className="flex items-center gap-2 text-slate-600">
        <span>{t.hours}h</span>
        <StateBadge state={t.status} variant="timesheet" />
      </span>
    </li>
  );
}
