'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { ArrowUpRight, Users } from 'lucide-react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import DataTable, { type Column } from '@/components/ui/DataTable';
import Modal from '@/components/ui/Modal';
import InternJourneyStepper from '@/components/intern/InternJourneyStepper';
import { useErmDashboard } from '@/components/erm/ErmDashboardContext';
import ExceptionSummary from '@/components/erm/ExceptionSummary';
import RecentActivityCard from '@/components/erm/RecentActivityCard';
import ScopeToggle from '@/components/erm/ScopeToggle';
import ErmRightSidePanel from '@/components/erm/ErmRightSidePanel';
import { HorizontalBars } from '@/components/erm/reports/Bars';
import DashboardRefreshButton from '@/components/ui/DashboardRefreshButton';
import { formatRelative } from '@/lib/format-date';
import type {
  ActiveInternDetail,
  ActiveInternListPage,
  ActiveInternRow,
} from '@/components/trainer/types';
import type { PipelineFunnelData } from '@/components/erm/reports/types';

const ROSTER_POLL_MS = 90_000;
const PIPELINE_POLL_MS = 180_000;
const PIPELINE_RANGE_DAYS = 90;

export default function ErmHomePage() {
  const { data, loading, error, scope, setScope, refresh } = useErmDashboard();
  const [drawerLifecycleId, setDrawerLifecycleId] = useState<string | null>(null);

  const callerName = data
    ? [data.caller.firstName, data.caller.lastName].filter(Boolean).join(' ')
    : '';
  const subtitle = data
    ? `Operational tower · ${scope === 'mine' ? 'My interns' : 'All interns'}${callerName ? ' · ' + callerName : ''}`
    : 'Loading dashboard…';
  const asOfText = data?.asOf ? new Date(data.asOf).toLocaleString() : '';

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0 flex-1">
            <PageHeader title="ERM Control Center" subtitle={subtitle} />
          </div>
          <div className="flex items-center gap-2 pt-1">
            <DashboardRefreshButton onRefresh={refresh} />
            <ScopeToggle scope={scope} onChange={setScope} />
          </div>
        </div>

        <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_280px]">
          <main className="min-w-0 space-y-6">
            {error && (
              <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
                {error}
              </p>
            )}

            <KpiRow loading={loading} />

            <section>
              <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
                Needs your action
              </h2>
              <ExceptionSummary
                counts={data?.exceptions.counts ?? null}
                topUrgent={data?.exceptions.topUrgent ?? []}
              />
            </section>

            <PipelineCard scope={scope} />

            <ActiveInternsTable
              onRowClick={(row) => setDrawerLifecycleId(row.internLifecycleId)}
            />

            <section>
              <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
                Recent activity
              </h2>
              <RecentActivityCard entries={data?.recentActivity ?? []} />
            </section>

            <footer className="border-t border-slate-200 pt-3 text-[11px] text-slate-500">
              Last updated {asOfText || '—'} · Scope: {scope}
            </footer>
          </main>

          <ErmRightSidePanel />
        </div>

        <InternDrawer
          lifecycleId={drawerLifecycleId}
          onClose={() => setDrawerLifecycleId(null)}
        />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

// ─── KPI row ─────────────────────────────────────────────────────────────

function KpiRow({ loading }: { loading: boolean }) {
  const { data } = useErmDashboard();
  const [activeCount, setActiveCount] = useState<number | null>(null);
  const [activeLoading, setActiveLoading] = useState(true);

  // "Active interns" isn't in the existing 9 KPIs; the monthly roster
  // summary is the canonical source. Fetch once per page mount; the
  // roster table below polls separately and shares nothing here so the
  // request is independent.
  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const now = new Date();
        const params = new URLSearchParams({
          y: String(now.getFullYear()),
          m: String(now.getMonth() + 1),
          page: '0',
          pageSize: '1',
        });
        const res = await api.get<ActiveInternListPage>(
          `/api/v1/erm/active-interns/roster?${params.toString()}`,
        );
        if (!cancelled) setActiveCount(res.data.summary.totalActive);
      } catch {
        if (!cancelled) setActiveCount(null);
      } finally {
        if (!cancelled) setActiveLoading(false);
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, []);

  const cards: KpiCardData[] = [
    {
      key: 'active-interns',
      label: 'Active interns',
      count: activeCount,
      helper: activeCount == null ? null : `This month`,
      href: '/careers/erm/active-interns',
      loading: activeLoading,
    },
    {
      key: 'open-applications',
      label: 'Open applications',
      count: data?.kpis.APPLICATIONS_PENDING_REVIEW.count ?? null,
      helper: data?.kpis.APPLICATIONS_PENDING_REVIEW.helperText ?? null,
      href: data?.kpis.APPLICATIONS_PENDING_REVIEW.actionUrl ?? '/careers/erm/applications',
      urgent: data?.kpis.APPLICATIONS_PENDING_REVIEW.urgentCount ?? 0,
      loading: loading && !data,
    },
    {
      key: 'offers-pending',
      label: 'Offers pending sign',
      count: data?.kpis.OFFERS_PENDING_SIGNATURE.count ?? null,
      helper: data?.kpis.OFFERS_PENDING_SIGNATURE.helperText ?? null,
      href: data?.kpis.OFFERS_PENDING_SIGNATURE.actionUrl ?? '/careers/erm/offers',
      urgent: data?.kpis.OFFERS_PENDING_SIGNATURE.urgentCount ?? 0,
      loading: loading && !data,
    },
    {
      key: 'onboarding-review',
      label: 'Onboarding in review',
      count: data?.kpis.AWAITING_DOCUMENT_PACKET.count ?? null,
      helper: data?.kpis.AWAITING_DOCUMENT_PACKET.helperText ?? null,
      href: data?.kpis.AWAITING_DOCUMENT_PACKET.actionUrl ?? '/careers/erm/document-packets',
      urgent: data?.kpis.AWAITING_DOCUMENT_PACKET.urgentCount ?? 0,
      loading: loading && !data,
    },
  ];

  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
      {cards.map(({ key, ...rest }) => (
        <KpiStatCard key={key} {...rest} />
      ))}
    </div>
  );
}

interface KpiCardData {
  key: string;
  label: string;
  count: number | null;
  helper?: string | null;
  href: string;
  urgent?: number;
  loading?: boolean;
}

type KpiStatCardProps = Omit<KpiCardData, 'key'>;

function KpiStatCard({ label, count, helper, href, urgent = 0, loading }: KpiStatCardProps) {
  const isUrgent = urgent > 0;
  if (loading) {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-ds-sm">
        <div className="h-3 w-32 animate-pulse rounded bg-slate-100" />
        <div className="mt-3 h-8 w-16 animate-pulse rounded bg-slate-100" />
        <div className="mt-3 h-3 w-40 animate-pulse rounded bg-slate-100" />
      </div>
    );
  }
  return (
    <Link
      href={href}
      className={
        'group block rounded-lg border bg-white p-4 shadow-ds-sm transition-all hover:-translate-y-0.5 hover:shadow-ds-md '
        + (isUrgent ? 'border-red-200 ring-1 ring-red-100' : 'border-slate-200')
      }
    >
      <div className="flex items-start justify-between">
        <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
          {label}
        </p>
        <ArrowUpRight
          className="h-4 w-4 text-slate-300 transition-colors group-hover:text-slate-500"
          strokeWidth={2}
        />
      </div>
      <div className="mt-2 flex items-baseline gap-2">
        <span className="text-3xl font-semibold tabular-nums text-slate-900">
          {count ?? '—'}
        </span>
        {isUrgent && (
          <span className="rounded-full bg-red-100 px-2 py-0.5 text-[11px] font-semibold text-red-700">
            {urgent} urgent
          </span>
        )}
      </div>
      {helper && <p className="mt-2 text-xs text-slate-500">{helper}</p>}
    </Link>
  );
}

// ─── Lifecycle pipeline ──────────────────────────────────────────────────

function PipelineCard({ scope }: { scope: 'mine' | 'all' }) {
  const [data, setData] = useState<PipelineFunnelData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const range = useMemo(() => {
    const to = new Date();
    const from = new Date();
    from.setDate(from.getDate() - PIPELINE_RANGE_DAYS);
    return {
      from: from.toISOString().slice(0, 10),
      to: to.toISOString().slice(0, 10),
    };
  }, []);

  const load = useCallback(async () => {
    try {
      const params = new URLSearchParams({
        from: range.from,
        to: range.to,
        scope,
      });
      const res = await api.get<PipelineFunnelData>(
        `/api/v1/erm/reports/pipeline-funnel?${params.toString()}`,
      );
      setData(res.data);
      setError(null);
    } catch (e: any) {
      setError(e?.response?.data?.error ?? e?.message ?? 'Failed to load pipeline');
    } finally {
      setLoading(false);
    }
  }, [range.from, range.to, scope]);

  useEffect(() => {
    void load();
    const t = window.setInterval(() => void load(), PIPELINE_POLL_MS);
    return () => window.clearInterval(t);
  }, [load]);

  const rows = (data?.stages ?? []).map((s) => ({
    label: humanizeStage(s.stage),
    value: s.count,
    sub:
      s.conversionFromPrevious != null
        ? `${s.conversionFromPrevious.toFixed(1)}% from previous`
        : 'top of funnel',
  }));

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-ds-sm">
      <header className="mb-3 flex items-baseline justify-between">
        <h2 className="text-sm font-semibold text-slate-900">Lifecycle pipeline</h2>
        <Link
          href="/careers/erm/reports"
          className="text-xs font-medium text-brand-700 hover:underline"
        >
          Full reports →
        </Link>
      </header>
      <p className="mb-3 text-xs text-slate-500">
        Applicants over the last {PIPELINE_RANGE_DAYS} days — counted as ≥ each stage.
        {data?.uniqueApplicants != null && (
          <> Unique applicants: <strong>{data.uniqueApplicants}</strong>.</>
        )}
      </p>
      {loading && !data ? (
        <div className="h-32 animate-pulse rounded-md bg-slate-100" aria-hidden />
      ) : error ? (
        <p className="text-xs text-red-700">{error}</p>
      ) : rows.length === 0 ? (
        <p className="text-xs text-slate-500">
          No pipeline activity in this range.
        </p>
      ) : (
        <HorizontalBars rows={rows} />
      )}
    </section>
  );
}

function humanizeStage(s: string): string {
  // Backend ships UPPER_SNAKE; render a friendlier label.
  return s
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

// ─── Active interns table ────────────────────────────────────────────────

const PROJECT_STATE_LABEL: Record<string, string> = {
  NO_PROJECTS: 'No projects',
  PARTIAL: 'Partial',
  BOTH_ASSIGNED: 'Both assigned',
  OVERDUE: 'Overdue',
  COMPLETE: 'Complete',
};

const PROJECT_STATE_CLS: Record<string, string> = {
  NO_PROJECTS: 'bg-slate-100 text-slate-700',
  PARTIAL: 'bg-amber-100 text-amber-800',
  BOTH_ASSIGNED: 'bg-brand-100 text-brand-800',
  OVERDUE: 'bg-red-100 text-red-700',
  COMPLETE: 'bg-green-100 text-green-700',
};

const TIMESHEET_STATE_CLS: Record<string, string> = {
  APPROVED: 'bg-green-100 text-green-700',
  SUBMITTED: 'bg-brand-100 text-brand-800',
  DRAFT: 'bg-slate-100 text-slate-700',
  REJECTED: 'bg-red-100 text-red-700',
  MISSING: 'bg-amber-100 text-amber-800',
};

function ActiveInternsTable({
  onRowClick,
}: {
  onRowClick: (row: ActiveInternRow) => void;
}) {
  const [page, setPage] = useState<ActiveInternListPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const now = new Date();
      const params = new URLSearchParams({
        y: String(now.getFullYear()),
        m: String(now.getMonth() + 1),
        page: '0',
        pageSize: '12',
      });
      const res = await api.get<ActiveInternListPage>(
        `/api/v1/erm/active-interns/roster?${params.toString()}`,
      );
      setPage(res.data);
      setError(null);
    } catch (e: any) {
      setError(e?.response?.data?.error ?? e?.message ?? 'Failed to load active interns');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    const t = window.setInterval(() => void load(), ROSTER_POLL_MS);
    return () => window.clearInterval(t);
  }, [load]);

  const columns: Column<ActiveInternRow>[] = [
    {
      key: 'intern',
      header: 'Intern',
      sortable: true,
      sortValue: (r) => r.fullName?.toLowerCase() ?? '',
      render: (r) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 items-center justify-center rounded-full bg-brand-100 text-xs font-semibold text-brand-800">
            {initials(r.fullName)}
          </span>
          <div className="min-w-0">
            <p className="truncate font-medium text-slate-900">
              {r.fullName ?? '(unknown)'}
            </p>
            {r.employeeId && (
              <p className="truncate font-mono text-[10px] text-slate-500">
                {r.employeeId}
              </p>
            )}
          </div>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      sortable: true,
      sortValue: (r) => r.currentMonthProjects.overallState,
      render: (r) => {
        const s = r.currentMonthProjects.overallState;
        return (
          <span
            className={
              'inline-flex rounded-full px-2 py-0.5 text-[10px] font-semibold '
              + (PROJECT_STATE_CLS[s] ?? 'bg-slate-100 text-slate-700')
            }
          >
            {PROJECT_STATE_LABEL[s] ?? s}
          </span>
        );
      },
    },
    {
      key: 'tech',
      header: 'Tech',
      sortable: true,
      sortValue: (r) => r.technologyTitle?.toLowerCase() ?? '',
      render: (r) => (
        <span className="text-slate-700">{r.technologyTitle ?? '—'}</span>
      ),
    },
    {
      key: 'this-month',
      header: 'This month',
      render: (r) => {
        const ts = r.timesheet;
        const submitted = ts.submittedCount + ts.approvedCount;
        const cls = TIMESHEET_STATE_CLS[ts.state] ?? 'bg-slate-100 text-slate-700';
        return (
          <div className="flex flex-col gap-0.5">
            <span
              className={
                'inline-flex w-fit rounded-full px-2 py-0.5 text-[10px] font-semibold ' + cls
              }
            >
              {ts.state.toLowerCase()}
            </span>
            <span className="text-[10px] text-slate-500">
              {submitted}/{ts.expectedWeeks} weeks
            </span>
          </div>
        );
      },
    },
    {
      key: 'trainer',
      header: 'Trainer',
      sortable: true,
      sortValue: (r) => r.reportingStructure.trainerName?.toLowerCase() ?? '',
      render: (r) => (
        <span className="truncate text-slate-700">
          {r.reportingStructure.trainerName ?? (
            <span className="text-slate-400">Unassigned</span>
          )}
        </span>
      ),
    },
  ];

  const items = page?.items ?? [];

  return (
    <section>
      <header className="mb-3 flex items-baseline justify-between">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
          Active interns
        </h2>
        <Link
          href="/careers/erm/active-interns"
          className="text-xs font-medium text-brand-700 hover:underline"
        >
          See all{page ? ` (${page.totalElements})` : ''} →
        </Link>
      </header>
      {loading && !page ? (
        <div className="h-48 animate-pulse rounded-lg border border-slate-200 bg-slate-50" />
      ) : error ? (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {error}
        </p>
      ) : (
        <DataTable
          columns={columns}
          data={items}
          rowKey={(r) => r.internLifecycleId}
          onRowClick={onRowClick}
          empty={{
            icon: Users,
            title: 'No active interns this month',
            description: 'Once interns are activated, they will appear here.',
          }}
        />
      )}
    </section>
  );
}

// ─── Intern drawer ───────────────────────────────────────────────────────

function InternDrawer({
  lifecycleId,
  onClose,
}: {
  lifecycleId: string | null;
  onClose: () => void;
}) {
  const [detail, setDetail] = useState<ActiveInternDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!lifecycleId) {
      setDetail(null);
      setError(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    (async () => {
      try {
        const res = await api.get<ActiveInternDetail>(
          `/api/v1/erm/active-interns/${lifecycleId}`,
        );
        if (!cancelled) setDetail(res.data);
      } catch (e: any) {
        if (!cancelled) {
          setError(e?.response?.data?.error ?? 'Could not load intern detail');
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [lifecycleId]);

  const open = lifecycleId != null;

  return (
    <Modal
      open={open}
      onOpenChange={(o) => !o && onClose()}
      title={detail?.intern.fullName ?? 'Intern detail'}
      description={
        detail
          ? [detail.intern.employeeId, detail.intern.technologyTitle]
              .filter(Boolean)
              .join(' · ') || 'Active intern'
          : 'Loading…'
      }
      docked="right"
      footer={
        lifecycleId && (
          <Link
            href={`/careers/erm/active-interns/${lifecycleId}`}
            className="inline-flex items-center rounded-md bg-brand-700 px-4 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
          >
            Open full detail →
          </Link>
        )
      }
    >
      {loading && !detail && (
        <div className="space-y-3">
          <div className="h-6 w-48 animate-pulse rounded bg-slate-100" />
          <div className="h-32 animate-pulse rounded bg-slate-100" />
          <div className="h-24 animate-pulse rounded bg-slate-100" />
        </div>
      )}
      {error && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {error}
        </p>
      )}
      {detail && (
        <div className="space-y-5">
          <div className="rounded-md border border-slate-200 bg-slate-50 p-3 text-xs text-slate-700">
            <DrawerStat label="Start date" value={fmtDate(detail.intern.startDate)} />
            <DrawerStat
              label="Days active"
              value={String(detail.summary?.daysActive ?? 0)}
            />
            <DrawerStat
              label="Email"
              value={detail.intern.email ?? '—'}
            />
            <DrawerStat label="Phone" value={detail.intern.phone ?? '—'} />
          </div>

          {/* Active-roster rows are all ACTIVE_INTERN by definition. */}
          <InternJourneyStepper status="ACTIVE_INTERN" />

          <DrawerSection title="Current month projects">
            <ProjectsBlock detail={detail} />
          </DrawerSection>

          <DrawerSection title="This week">
            <WeeklyBlock detail={detail} />
          </DrawerSection>

          <DrawerSection title="Recent activity">
            {detail.recentActivity.length === 0 ? (
              <p className="text-xs text-slate-500">No recent activity.</p>
            ) : (
              <ul className="space-y-1.5 text-xs text-slate-700">
                {detail.recentActivity.slice(0, 6).map((a, i) => (
                  <li key={i} className="flex items-baseline justify-between gap-2">
                    <span className="truncate">
                      <span className="font-medium">{a.actorName ?? 'System'}</span>{' '}
                      <span className="text-slate-500">
                        {humanizeAction(a.action)}
                      </span>
                    </span>
                    <span className="shrink-0 text-[10px] text-slate-400">
                      {a.at ? formatRelative(a.at) : ''}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </DrawerSection>
        </div>
      )}
    </Modal>
  );
}

function DrawerStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-0.5">
      <span className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
        {label}
      </span>
      <span className="truncate text-xs text-slate-800">{value}</span>
    </div>
  );
}

function DrawerSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">
        {title}
      </h3>
      {children}
    </div>
  );
}

function ProjectsBlock({ detail }: { detail: ActiveInternDetail }) {
  const p1 = detail.summary?.currentMonthProjects?.project1;
  const p2 = detail.summary?.currentMonthProjects?.project2;
  const items = [p1, p2].filter(Boolean) as NonNullable<typeof p1>[];
  if (items.length === 0) {
    return <p className="text-xs text-slate-500">No projects assigned this month.</p>;
  }
  return (
    <ul className="space-y-2">
      {items.map((p, i) => (
        <li
          key={p.id ?? i}
          className="rounded-md border border-slate-200 bg-white p-2.5"
        >
          <div className="flex items-center justify-between gap-2">
            <p className="truncate text-xs font-medium text-slate-900">
              {p.title ?? '(untitled)'}
            </p>
            <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-700">
              {p.state}
            </span>
          </div>
          {p.dueDate && (
            <p className="mt-0.5 text-[10px] text-slate-500">
              Due {fmtDate(p.dueDate)}
            </p>
          )}
        </li>
      ))}
    </ul>
  );
}

function WeeklyBlock({ detail }: { detail: ActiveInternDetail }) {
  const meeting = detail.summary?.weeklyMeeting;
  const evaluation = detail.summary?.evaluation;
  const timesheet = detail.summary?.timesheet;
  return (
    <ul className="space-y-1.5 text-xs">
      <RowKV
        label="Meeting"
        value={meeting?.state ?? '—'}
        sub={meeting?.nextMeetingAt ? `Next ${fmtDate(meeting.nextMeetingAt)}` : null}
      />
      <RowKV
        label="Evaluation"
        value={evaluation?.state ?? '—'}
        sub={
          evaluation?.nextScheduledAt
            ? `Next ${fmtDate(evaluation.nextScheduledAt)}`
            : evaluation?.lastPublishedAt
              ? `Last ${fmtDate(evaluation.lastPublishedAt)}`
              : null
        }
      />
      <RowKV
        label="Timesheet"
        value={timesheet?.state ?? '—'}
        sub={
          timesheet
            ? `${timesheet.approvedCount}/${timesheet.expectedWeeks} weeks approved`
            : null
        }
      />
    </ul>
  );
}

function RowKV({ label, value, sub }: { label: string; value: string; sub?: string | null }) {
  return (
    <li className="flex items-baseline justify-between gap-3 rounded-md border border-slate-100 bg-white p-2">
      <span className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
        {label}
      </span>
      <span className="flex flex-col items-end">
        <span className="text-xs text-slate-800">{value}</span>
        {sub && <span className="text-[10px] text-slate-400">{sub}</span>}
      </span>
    </li>
  );
}

function humanizeAction(action: string | null): string {
  if (!action) return '';
  return action.toLowerCase().replace(/_/g, ' ');
}

function fmtDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString();
}

function initials(name: string | null): string {
  if (!name) return '?';
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? '')
    .join('');
}
