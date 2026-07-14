'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import {
  ArrowLeft,
  BadgeCheck,
  ClipboardList,
  ExternalLink,
  FileText,
  ShieldCheck,
  Users,
} from 'lucide-react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import { formatDateOnly, formatFull, formatRelative } from '@/lib/format-date';
import type { Uuid, UserRole } from '@/types';

/**
 * SUPER_ADMIN L3 — per-user supervision view. Profile + audit timeline +
 * role-contextual data. Status-only on compliance — deep-links go to the
 * existing gated detail pages for raw PII.
 *
 * Backed by GET /api/v1/admin/users/{id}/supervision. Backend writes a
 * SUPER_ADMIN_VIEWED_USER audit row on every successful load.
 */

interface ProfileBlock {
  id: Uuid;
  name: string | null;
  email: string;
  roles: UserRole[];
  active: boolean;
  applicantId: string | null;
  createdAt: string | null;
}

interface ActivityRow {
  timestamp: string | null;
  action: string;
  entityType: string | null;
  entityId: Uuid | null;
  details: string | null;
}

interface ActionCount {
  action: string;
  count: number;
}

interface ApplicationSummary {
  id: Uuid;
  position: string | null;
  entityName: string | null;
  status: string | null;
  appliedAt: string | null;
  href: string;
}

interface EngagementSummary {
  id: Uuid;
  status: string | null;
  plannedStartDate: string | null;
  actualStartDate: string | null;
  supervisorName: string | null;
  entityName: string | null;
  /** Populated when the backend ships it; falls back to null on older builds. */
  reportingManagerName?: string | null;
  reportingManagerId?: Uuid | null;
}

interface ComplianceStatus {
  i9Status: string | null;
  i9FirstDayOfEmployment: string | null;
  i9WorkAuthExpirationDate: string | null;
  i9DetailHref: string | null;
  i983Status: string | null;
  i983OptEndDate: string | null;
  i983DetailHref: string | null;
  everifyStatus: string | null;
  everifyDetailHref: string | null;
}

interface ReportSummary {
  id: Uuid;
  weekStart: string;
  status: string | null;
  submittedAt: string | null;
  reviewedAt: string | null;
}

interface TimesheetSummary {
  id: Uuid;
  weekStart: string;
  status: string | null;
  hours: string | null;
}

interface CandidateContext {
  candidateId: Uuid;
  applications: ApplicationSummary[];
  engagement: EngagementSummary | null;
  compliance: ComplianceStatus | null;
  weeklyReports: ReportSummary[];
  timesheets: TimesheetSummary[];
  materialsAcknowledgedCount: number;
}

interface InternMini {
  candidateId: Uuid;
  name: string | null;
  position: string | null;
  engagementStatus: string | null;
  reviewHref: string;
}

interface SupervisorContext {
  assignedInterns: InternMini[];
  activeInternsCount: number;
  reportsAwaitingReview: number;
  timesheetsAwaitingApproval: number;
  upcomingEvaluations: number;
  materialsPublished: number;
}

interface UserSupervisionResponse {
  profile: ProfileBlock;
  activity: ActivityRow[];
  topActions: ActionCount[];
  candidateContext: CandidateContext | null;
  supervisorContext: SupervisorContext | null;
}

const ROLE_LABEL: Record<UserRole, string> = {
  INTERN: 'Intern',
  TRAINER: 'Trainer',
  EVALUATOR: 'Evaluator',
  REPORTING_MANAGER: 'Reporting Manager',
  MANAGER: 'Manager',
  ERM: 'ERM',
  SUPER_ADMIN: 'Super admin',
};

// role-badge palette deferred to M2 role-consolidation
// (TRAINER/RM/MANAGER all share amber today — fold or split lands with M2).
// INTERN uses light slate, SUPER_ADMIN uses dark slate, to differentiate.
const ROLE_COLOR: Record<UserRole, string> = {
  INTERN: 'bg-slate-100 text-slate-700',
  TRAINER: 'bg-amber-100 text-amber-800',
  EVALUATOR: 'bg-brand-100 text-brand-800',
  REPORTING_MANAGER: 'bg-amber-100 text-amber-800',
  MANAGER: 'bg-amber-100 text-amber-800',
  ERM: 'bg-green-100 text-green-800',
  SUPER_ADMIN: 'bg-slate-200 text-slate-900',
};

export default function SupervisionPage({
  params,
}: {
  params: { id: string };
}) {
  // Next 14 App Router — params is a plain synchronous object. (Async params
  // landed in Next 15; using React.use() on this in Next 14 throws because
  // it's not a Promise.)
  const { id } = params;
  return (
    <ProtectedRoute requiredRoles={['SUPER_ADMIN']}>
      <DashboardLayout title="Supervision">
        <Body userId={id} />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body({ userId }: { userId: string }) {
  const [data, setData] = useState<UserSupervisionResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const res = await api.get<UserSupervisionResponse>(
        `/api/v1/admin/users/${userId}/supervision`,
      );
      setData(res.data ?? null);
    } catch (err: any) {
      const status = err?.response?.status;
      setError(
        status === 404
          ? 'No user found with that id.'
          : err?.response?.data?.error ?? "Couldn't load supervision view.",
      );
      setData(null);
    }
  }, [userId]);

  useEffect(() => {
    void load();
  }, [load]);

  if (error) {
    return (
      <div className="space-y-4">
        <BackLink />
        <div className="rounded border border-red-200 bg-red-50 p-4 text-sm text-red-700">
          <p className="mb-2">{error}</p>
          <button
            type="button"
            onClick={() => void load()}
            className="rounded border border-red-300 px-3 py-1 font-medium hover:bg-red-100"
          >
            Retry
          </button>
        </div>
      </div>
    );
  }
  if (data === null) return <Skeleton />;

  return (
    <section className="space-y-6">
      <BackLink />

      {/* Supervision model has shifted to pure role-based access. Assignment
          via this page is now optional — kept for special-case overrides. */}
      <div className="rounded-md border border-slate-300 bg-slate-100 p-3 text-xs text-slate-700">
        <p className="font-medium">Assignment is optional.</p>
        <p className="mt-0.5">
          By default, any Technical Evaluator sees every hired intern and any
          Reporting Manager sees every RM-eligible item across all engagements.
          Use this page only for special-case overrides — the assignments
          recorded here are informational metadata, not a permission boundary.
        </p>
      </div>

      {/* 1. Profile header */}
      <ProfileCard profile={data.profile} />

      {/* 2. COMPLETE audit log — paginated, filterable. Drives off the new
            /audit endpoint so actor + subject events both surface. */}
      <CompleteAuditLog userId={userId} topActions={data.topActions} />

      {/* 3. Role-contextual work — candidate or supervisor (or neither for
            staff users; the audit log above covers their footprint). */}
      {data.candidateContext && (
        <CandidateContextCard ctx={data.candidateContext} />
      )}
      {data.supervisorContext && (
        <SupervisorContextCard ctx={data.supervisorContext} />
      )}
    </section>
  );
}

// ── COMPLETE per-user audit log (L3 round-2) ────────────────────────────────

interface PagedAuditLogResponse {
  content: ActivityRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

const AUDIT_PAGE_SIZE = 25;

function CompleteAuditLog({
  userId,
  topActions,
}: {
  userId: string;
  topActions: ActionCount[];
}) {
  const [audit, setAudit] = useState<PagedAuditLogResponse | null>(null);
  const [actions, setActions] = useState<string[]>([]);
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const [form, setForm] = useState<{ action: string; from: string; to: string }>({
    action: '',
    from: '',
    to: '',
  });
  const [committed, setCommitted] = useState(form);

  const load = useCallback(async () => {
    setError(null);
    try {
      const params: Record<string, string | number> = {
        page,
        size: AUDIT_PAGE_SIZE,
      };
      if (committed.action) params.action = committed.action;
      if (committed.from) params.from = `${committed.from}T00:00:00Z`;
      if (committed.to) params.to = `${committed.to}T23:59:59Z`;
      const res = await api.get<PagedAuditLogResponse>(
        `/api/v1/admin/users/${userId}/audit`,
        { params },
      );
      setAudit(
        res.data ?? {
          content: [],
          page,
          size: AUDIT_PAGE_SIZE,
          totalElements: 0,
          totalPages: 0,
        },
      );
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't load this user's audit log.");
      setAudit(null);
    }
  }, [userId, page, committed]);

  // Source the action dropdown from the global action list — the per-user
  // log is a subset; not worth a dedicated endpoint here.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await api.get<string[]>('/api/v1/admin/audit-log/actions');
        if (!cancelled) setActions(res.data ?? []);
      } catch {
        if (!cancelled) setActions([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const apply = () => {
    setCommitted(form);
    setPage(0);
  };
  const reset = () => {
    const empty = { action: '', from: '', to: '' };
    setForm(empty);
    setCommitted(empty);
    setPage(0);
  };

  const total = audit?.totalElements ?? 0;
  const currentPage = audit?.page ?? 0;
  const totalPages = audit?.totalPages ?? 0;

  return (
    <section className="rounded-lg border border-gray-200 bg-white p-5">
      <div className="mb-3 flex items-baseline justify-between">
        <div className="flex items-center gap-2">
          <ClipboardList className="h-4 w-4 text-accent" strokeWidth={2} />
          <h2 className="text-sm font-semibold text-gray-900">
            Complete audit log
          </h2>
        </div>
        <span className="text-[11px] text-gray-500">
          Actor + subject · {total.toLocaleString()} {total === 1 ? 'event' : 'events'}
        </span>
      </div>

      {/* Filters */}
      <div className="mb-3 grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-4">
        <select
          value={form.action}
          onChange={(e) => setForm((f) => ({ ...f, action: e.target.value }))}
          aria-label="Action"
          className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
        >
          <option value="">All actions</option>
          {actions.map((a) => (
            <option key={a} value={a}>
              {a}
            </option>
          ))}
        </select>
        <input
          type="date"
          value={form.from}
          onChange={(e) => setForm((f) => ({ ...f, from: e.target.value }))}
          aria-label="From"
          className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
        />
        <input
          type="date"
          value={form.to}
          onChange={(e) => setForm((f) => ({ ...f, to: e.target.value }))}
          aria-label="To"
          className="rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
        />
        <div className="flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={reset}
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
          >
            Reset
          </button>
          <button
            type="button"
            onClick={apply}
            className="rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-white hover:bg-accent/90"
          >
            Apply
          </button>
        </div>
      </div>

      {topActions.length > 0 && (
        <div className="mb-3 flex flex-wrap items-center gap-1.5 text-[11px]">
          <span className="text-gray-500">Top actions:</span>
          {topActions.map((t) => (
            <button
              key={t.action}
              type="button"
              onClick={() => {
                setForm((f) => ({ ...f, action: t.action }));
                setCommitted((c) => ({ ...c, action: t.action }));
                setPage(0);
              }}
              className="rounded-full bg-gray-100 px-2 py-0.5 font-medium text-gray-700 hover:bg-gray-200"
              title={`Filter by ${t.action}`}
            >
              {t.action}
              <span className="ml-1 text-gray-500">· {t.count.toLocaleString()}</span>
            </button>
          ))}
        </div>
      )}

      {error && (
        <div className="mb-3 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {audit === null && !error ? (
        <div className="py-8 text-center text-sm text-gray-500">Loading…</div>
      ) : (audit?.content?.length ?? 0) === 0 ? (
        <div className="rounded-md border border-dashed border-gray-300 bg-white p-8 text-center text-sm text-gray-600">
          No audit entries match those filters.
        </div>
      ) : (
        <ul className="space-y-2.5">
          {(audit?.content ?? []).map((r, i) => (
            <li
              key={`${r.timestamp ?? ''}-${i}`}
              className="flex items-start gap-3"
            >
              <span
                aria-hidden="true"
                className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-accent"
              />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2 text-sm">
                  <span className="rounded-full bg-gray-100 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-gray-700">
                    {r.action}
                  </span>
                  {r.entityType && (
                    <span className="text-xs text-gray-500">{r.entityType}</span>
                  )}
                </div>
                {r.details && (
                  <div
                    className="mt-0.5 truncate font-mono text-[11px] text-gray-500"
                    title={r.details}
                  >
                    {r.details}
                  </div>
                )}
                <div className="text-xs text-gray-500">
                  {r.timestamp ? formatFull(r.timestamp) : '—'}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}

      {totalPages > 1 && (
        <div className="mt-4 flex items-center justify-center gap-4 text-sm text-gray-600">
          <button
            type="button"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={currentPage === 0}
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 hover:bg-gray-50 disabled:opacity-50"
          >
            ‹ Prev
          </button>
          <span>
            Page {currentPage + 1} of {totalPages}
          </span>
          <button
            type="button"
            onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
            disabled={currentPage >= totalPages - 1}
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 hover:bg-gray-50 disabled:opacity-50"
          >
            Next ›
          </button>
        </div>
      )}
    </section>
  );
}

function BackLink() {
  return (
    <Link
      href="/careers/admin/users"
      className="inline-flex items-center gap-1.5 text-xs font-medium text-gray-600 hover:text-gray-900"
    >
      <ArrowLeft className="h-3.5 w-3.5" strokeWidth={2} />
      Back to users
    </Link>
  );
}

// ── Profile ─────────────────────────────────────────────────────────────────

function ProfileCard({ profile }: { profile: ProfileBlock }) {
  return (
    <section className="rounded-lg border border-gray-200 bg-white p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">
            {profile.name ?? '—'}
          </h1>
          <p className="mt-1 text-sm text-gray-600">{profile.email}</p>
          <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
            {(profile.roles ?? []).map((r) => (
              <span
                key={r}
                className={
                  'rounded-full px-2.5 py-0.5 font-medium ' + ROLE_COLOR[r]
                }
              >
                {ROLE_LABEL[r]}
              </span>
            ))}
            <span
              className={
                'inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 font-medium ' +
                (profile.active
                  ? 'bg-green-100 text-green-800'
                  : 'bg-gray-200 text-gray-600')
              }
            >
              <span
                className={
                  'h-1.5 w-1.5 rounded-full ' +
                  (profile.active ? 'bg-green-600' : 'bg-gray-500')
                }
              />
              {profile.active ? 'Active' : 'Inactive'}
            </span>
          </div>
        </div>
        <div className="text-right text-xs text-gray-500">
          {profile.applicantId && (
            <div>
              <span className="font-mono text-gray-700">{profile.applicantId}</span>
            </div>
          )}
          {profile.createdAt && (
            <div>Joined {formatDateOnly(profile.createdAt)}</div>
          )}
        </div>
      </div>
    </section>
  );
}

// ── Candidate context ───────────────────────────────────────────────────────

const REPORT_PILL: Record<string, string> = {
  DRAFT: 'bg-gray-100 text-gray-700',
  SUBMITTED: 'bg-slate-100 text-slate-700',
  RETURNED: 'bg-amber-100 text-amber-800',
  APPROVED: 'bg-green-100 text-green-800',
};

const TIMESHEET_PILL: Record<string, string> = {
  DRAFT: 'bg-gray-100 text-gray-700',
  SUBMITTED: 'bg-slate-100 text-slate-700',
  APPROVED: 'bg-green-100 text-green-800',
  REJECTED: 'bg-amber-100 text-amber-800',
};

function CandidateContextCard({ ctx }: { ctx: CandidateContext }) {
  return (
    <section className="rounded-lg border border-gray-200 bg-white p-5">
      <h2 className="mb-4 text-sm font-semibold uppercase tracking-wide text-gray-500">
        Candidate / Intern context
      </h2>

      {ctx.engagement && (
        <div className="mb-4 rounded-md border border-gray-100 bg-gray-50 p-3 text-sm">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-semibold text-gray-900">Engagement</span>
            <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-slate-700">
              {ctx.engagement.status ?? '—'}
            </span>
          </div>
          <div className="mt-1 text-xs text-gray-600">
            {ctx.engagement.entityName ?? '—'}
            {ctx.engagement.supervisorName
              ? ` · supervised by ${ctx.engagement.supervisorName}`
              : ''}
            {ctx.engagement.plannedStartDate
              ? ` · planned start ${formatDateOnly(ctx.engagement.plannedStartDate)}`
              : ''}
            {ctx.engagement.actualStartDate
              ? ` · started ${formatDateOnly(ctx.engagement.actualStartDate)}`
              : ''}
          </div>
          <ReportingManagerAssigner
            engagementId={ctx.engagement.id}
            currentName={ctx.engagement.reportingManagerName ?? null}
            currentId={ctx.engagement.reportingManagerId ?? null}
          />
        </div>
      )}

      {/* Compliance (STATUS only — deep-links to gated detail pages) */}
      {ctx.compliance && (
        <div className="mb-4">
          <div className="mb-2 flex items-center gap-2">
            <ShieldCheck className="h-4 w-4 text-accent" strokeWidth={2} />
            <h3 className="text-xs font-semibold uppercase tracking-wide text-gray-500">
              Compliance status
            </h3>
          </div>
          <div className="grid gap-2 sm:grid-cols-3">
            <ComplianceTile
              icon={<BadgeCheck className="h-3.5 w-3.5" strokeWidth={2} />}
              label="I-9"
              status={ctx.compliance.i9Status}
              sub={
                ctx.compliance.i9WorkAuthExpirationDate
                  ? `Auth exp ${formatDateOnly(ctx.compliance.i9WorkAuthExpirationDate)}`
                  : ctx.compliance.i9FirstDayOfEmployment
                    ? `First day ${formatDateOnly(ctx.compliance.i9FirstDayOfEmployment)}`
                    : null
              }
              href={ctx.compliance.i9DetailHref}
            />
            <ComplianceTile
              icon={<ShieldCheck className="h-3.5 w-3.5" strokeWidth={2} />}
              label="E-Verify"
              status={ctx.compliance.everifyStatus}
              sub={null}
              href={ctx.compliance.everifyDetailHref}
            />
            <ComplianceTile
              icon={<FileText className="h-3.5 w-3.5" strokeWidth={2} />}
              label="I-983"
              status={ctx.compliance.i983Status}
              sub={
                ctx.compliance.i983OptEndDate
                  ? `OPT end ${formatDateOnly(ctx.compliance.i983OptEndDate)}`
                  : null
              }
              href={ctx.compliance.i983DetailHref}
            />
          </div>
          <p className="mt-2 text-[11px] italic text-gray-500">
            Status-only here. Decrypted PII (SSN, document numbers, DOB) lives
            on the gated detail pages.
          </p>
        </div>
      )}

      {/* Applications */}
      <div className="mb-4">
        <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-500">
          Applications
        </h3>
        {ctx.applications.length === 0 ? (
          <p className="text-sm text-gray-500">None.</p>
        ) : (
          <ul className="divide-y divide-gray-100">
            {ctx.applications.map((a) => (
              <li key={a.id} className="flex items-center gap-3 py-2 text-sm">
                <div className="min-w-0 flex-1">
                  <Link
                    href={a.href}
                    className="block truncate text-gray-900 hover:text-accent-dark hover:underline"
                  >
                    {a.position ?? '—'}
                  </Link>
                  <div className="truncate text-xs text-gray-500">
                    {a.entityName ?? '—'}
                    {a.appliedAt ? ` · applied ${formatRelative(a.appliedAt)}` : ''}
                  </div>
                </div>
                {a.status && (
                  <span className="rounded-full bg-gray-100 px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide text-gray-700">
                    {a.status}
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <div>
          <h3 className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-gray-500">
            <FileText className="h-3.5 w-3.5 text-accent" strokeWidth={2} />
            Weekly reports
          </h3>
          {ctx.weeklyReports.length === 0 ? (
            <p className="text-sm text-gray-500">None yet.</p>
          ) : (
            <ul className="space-y-1.5 text-sm">
              {ctx.weeklyReports.map((r) => (
                <li
                  key={r.id}
                  className="flex items-center justify-between gap-2 text-xs"
                >
                  <span className="text-gray-700">
                    Week of {formatDateOnly(r.weekStart)}
                  </span>
                  {r.status && (
                    <span
                      className={
                        'rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ' +
                        (REPORT_PILL[r.status] ?? 'bg-gray-100 text-gray-700')
                      }
                    >
                      {r.status}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>

        <div>
          <h3 className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-gray-500">
            <ClipboardList className="h-3.5 w-3.5 text-accent" strokeWidth={2} />
            Timesheets
          </h3>
          {ctx.timesheets.length === 0 ? (
            <p className="text-sm text-gray-500">None yet.</p>
          ) : (
            <ul className="space-y-1.5 text-sm">
              {ctx.timesheets.map((t) => (
                <li
                  key={t.id}
                  className="flex items-center justify-between gap-2 text-xs"
                >
                  <span className="text-gray-700">
                    Week of {formatDateOnly(t.weekStart)}
                    {t.hours != null && <span className="text-gray-500"> · {t.hours} hrs</span>}
                  </span>
                  {t.status && (
                    <span
                      className={
                        'rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ' +
                        (TIMESHEET_PILL[t.status] ?? 'bg-gray-100 text-gray-700')
                      }
                    >
                      {t.status}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      <p className="mt-4 text-[11px] text-gray-500">
        {ctx.materialsAcknowledgedCount.toLocaleString()} material
        {ctx.materialsAcknowledgedCount === 1 ? '' : 's'} acknowledged.
      </p>
    </section>
  );
}

function ComplianceTile({
  icon,
  label,
  status,
  sub,
  href,
}: {
  icon: React.ReactNode;
  label: string;
  status: string | null;
  sub: string | null;
  href: string | null;
}) {
  const tone =
    status === 'COMPLETED' ||
    status === 'DSO_APPROVED' ||
    status === 'EMPLOYMENT_AUTHORIZED'
      ? 'border-green-200 bg-green-50'
      : status == null
        ? 'border-gray-200 bg-white'
        : 'border-amber-200 bg-amber-50';
  const inner = (
    <div className={'rounded-md border p-3 transition-colors ' + tone}>
      <div className="flex items-center justify-between gap-2 text-xs">
        <span className="inline-flex items-center gap-1 font-semibold uppercase tracking-wide text-gray-700">
          <span className="text-accent">{icon}</span>
          {label}
        </span>
        {href && (
          <ExternalLink className="h-3 w-3 text-gray-400" strokeWidth={2} />
        )}
      </div>
      <div className="mt-1 text-sm font-semibold text-gray-900">
        {status ?? '—'}
      </div>
      {sub && <div className="mt-0.5 text-[11px] text-gray-500">{sub}</div>}
    </div>
  );
  if (href) return <Link href={href}>{inner}</Link>;
  return inner;
}

// ── Supervisor context ──────────────────────────────────────────────────────

function SupervisorContextCard({ ctx }: { ctx: SupervisorContext }) {
  return (
    <section className="rounded-lg border border-gray-200 bg-white p-5">
      <h2 className="mb-4 text-sm font-semibold uppercase tracking-wide text-gray-500">
        Supervisor context
      </h2>

      <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Active interns" value={ctx.activeInternsCount} />
        <Stat label="Reports awaiting review" value={ctx.reportsAwaitingReview} />
        <Stat label="Timesheets pending" value={ctx.timesheetsAwaitingApproval} />
        <Stat label="Upcoming evaluations" value={ctx.upcomingEvaluations} />
      </div>

      <h3 className="mb-2 flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-gray-500">
        <Users className="h-3.5 w-3.5 text-accent" strokeWidth={2} />
        Assigned interns
      </h3>
      {ctx.assignedInterns.length === 0 ? (
        <p className="text-sm text-gray-500">None assigned right now.</p>
      ) : (
        <ul className="divide-y divide-gray-100">
          {ctx.assignedInterns.map((i) => (
            <li key={i.candidateId} className="flex items-center gap-3 py-2 text-sm">
              <div className="min-w-0 flex-1">
                <Link
                  href={i.reviewHref}
                  className="block truncate text-gray-900 hover:text-accent-dark hover:underline"
                >
                  {i.name ?? '—'}
                </Link>
                <div className="truncate text-xs text-gray-500">
                  {i.position ?? '—'}
                </div>
              </div>
              {i.engagementStatus && (
                <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-slate-700">
                  {i.engagementStatus}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}

      <p className="mt-3 text-[11px] text-gray-500">
        {ctx.materialsPublished.toLocaleString()} material
        {ctx.materialsPublished === 1 ? '' : 's'} published.
      </p>
    </section>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-md border border-gray-200 bg-gray-50 p-3">
      <div className="text-2xl font-semibold text-gray-900">
        {value.toLocaleString()}
      </div>
      <div className="mt-0.5 text-[11px] uppercase tracking-wide text-gray-500">
        {label}
      </div>
    </div>
  );
}

// ── Skeleton ────────────────────────────────────────────────────────────────

function Skeleton() {
  return (
    <div className="space-y-6">
      <div className="h-4 w-32 animate-pulse rounded bg-gray-200" />
      <div className="h-24 animate-pulse rounded-lg border border-gray-100 bg-gray-50" />
      <div className="h-40 animate-pulse rounded-lg border border-gray-100 bg-gray-50" />
      <div className="grid gap-6 lg:grid-cols-3">
        <div className="h-56 animate-pulse rounded-lg border border-gray-100 bg-gray-50 lg:col-span-2" />
        <div className="h-56 animate-pulse rounded-lg border border-gray-100 bg-gray-50" />
      </div>
    </div>
  );
}

// ── Reporting Manager assigner ──────────────────────────────────────────────
//
// Inline affordance dropping into the engagement card. Lists active users
// with the REPORTING_MANAGER role and PATCHes
// /api/v1/engagements/{id}/reporting-manager on Save. SUPER_ADMIN-only —
// the supervision page itself is already gated.

interface RmCandidate {
  id: Uuid;
  name: string;
  email: string;
}

function ReportingManagerAssigner({
  engagementId,
  currentName,
  currentId,
}: {
  engagementId: Uuid;
  currentName: string | null;
  currentId: Uuid | null;
}) {
  const [open, setOpen] = useState(false);
  const [candidates, setCandidates] = useState<RmCandidate[]>([]);
  const [selected, setSelected] = useState<string>(currentId ?? '');
  const [saving, setSaving] = useState(false);
  const [savedLabel, setSavedLabel] = useState<string | null>(currentName);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open || candidates.length > 0) return;
    (async () => {
      try {
        const res = await api.get<Array<{
          id: Uuid;
          name: string;
          email: string;
          active?: boolean;
        }>>('/api/v1/admin/users?role=REPORTING_MANAGER');
        setCandidates(
          (res.data ?? [])
            .filter((u) => u.active !== false)
            .map((u) => ({ id: u.id, name: u.name, email: u.email })),
        );
      } catch (err: any) {
        setError(err?.response?.data?.error ?? "Couldn't load Reporting Managers.");
      }
    })();
  }, [open, candidates.length]);

  async function save() {
    setSaving(true);
    setError(null);
    try {
      await api.patch(`/api/v1/engagements/${engagementId}/reporting-manager`, {
        reportingManagerUserId: selected || 'null',
      });
      const match = candidates.find((c) => c.id === selected);
      setSavedLabel(selected ? (match?.name ?? '(assigned)') : null);
      setOpen(false);
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't save.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="mt-2 border-t border-gray-200 pt-2">
      <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
        <span className="text-gray-600">
          <span className="font-medium text-gray-700">Reporting Manager: </span>
          {savedLabel ?? <span className="italic text-gray-500">none</span>}
        </span>
        {!open && (
          <button
            type="button"
            onClick={() => setOpen(true)}
            className="rounded-md border border-gray-300 bg-white px-2.5 py-1 text-[11px] font-medium text-gray-700 hover:bg-gray-50"
          >
            {savedLabel ? 'Change' : 'Assign'}
          </button>
        )}
      </div>
      {open && (
        <div className="mt-2 space-y-2">
          <select
            value={selected}
            onChange={(e) => setSelected(e.target.value)}
            className="w-full rounded-md border border-gray-300 bg-white px-2 py-1 text-xs focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          >
            <option value="">— Unassign —</option>
            {candidates.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} ({c.email})
              </option>
            ))}
          </select>
          {error && <p className="text-xs text-red-700">{error}</p>}
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={() => {
                setOpen(false);
                setSelected(currentId ?? '');
                setError(null);
              }}
              disabled={saving}
              className="rounded-md border border-gray-300 bg-white px-2.5 py-1 text-[11px] font-medium text-gray-700 hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => void save()}
              disabled={saving}
              className="rounded-md bg-brand-700 px-2.5 py-1 text-[11px] font-medium text-white hover:bg-brand-800 disabled:opacity-60"
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

