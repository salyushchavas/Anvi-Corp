'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import {
  BadgeCheck,
  ClipboardList,
  FileBarChart2,
  Gavel,
  GraduationCap,
  Hourglass,
  ShieldAlert,
  UserMinus,
  Users,
  Video,
} from 'lucide-react';
import api from '@/lib/careers/api';
import type { OverviewResponse } from '@/components/manager/types';
import DashboardRefreshButton from '@/components/ui/DashboardRefreshButton';

export default function ManagerHomePage() {
  const [data, setData] = useState<OverviewResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const res = await api.get<OverviewResponse>('/api/v1/manager/overview');
      setData(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load overview');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  return (
    <div className="mx-auto max-w-6xl space-y-5 p-6">
      <header className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-[10px] font-semibold uppercase tracking-wide text-brand-700">
              Executive Overview
            </p>
            <h1 className="mt-1 text-2xl font-semibold text-slate-900">
              Manager Dashboard
            </h1>
          </div>
          <DashboardRefreshButton onRefresh={load} />
        </div>
        <p className="mt-1 text-sm text-slate-600">
          Portfolio-wide read of the applicant funnel and intern lifecycle.
          {data?.caller && (
            <span className="ml-2 text-slate-500">
              Signed in as {data.caller.fullName}
              {data.caller.superAdmin && ' · SUPER_ADMIN'}
            </span>
          )}
        </p>
      </header>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}
      {loading && !data && (
        <div className="h-32 animate-pulse rounded-lg bg-slate-100" />
      )}

      {data && (
        <>
          <section>
            <h2 className="mb-3 text-sm font-semibold text-slate-900">Action required</h2>
            {/* All 4 pending queues surfaced up-front. The audit flagged
                that only Hire Approvals was visible on this section, so
                Timesheet / Weekly Report / Recording queues went
                unnoticed until the manager clicked the sidebar. Each
                tile self-fetches its pending count on mount — one
                slow queue can't block the others (independent
                loaders + graceful "—" on error). */}
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <HireApprovalsKpi count={data.pendingHireApprovals} />
              <PendingQueueTile
                href="/careers/manager/timesheet-approvals"
                label="Timesheet Approvals"
                icon={<ClipboardList className="h-3.5 w-3.5" />}
                fetchCount={fetchPendingTimesheetCount}
                blurbUnit="timesheet"
              />
              <PendingQueueTile
                href="/careers/manager/weekly-reports"
                label="Weekly Reports"
                icon={<FileBarChart2 className="h-3.5 w-3.5" />}
                fetchCount={fetchPendingWeeklyReportCount}
                blurbUnit="report"
              />
              <PendingQueueTile
                href="/careers/manager/recording-approvals"
                label="Recording Approvals"
                icon={<Video className="h-3.5 w-3.5" />}
                fetchCount={fetchPendingRecordingCount}
                blurbUnit="recording"
              />
            </div>
          </section>

          <section>
            <h2 className="mb-3 text-sm font-semibold text-slate-900">Funnel</h2>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
              <CountCard
                href="/careers/manager/applicant-pipeline"
                icon={<Users className="h-4 w-4" />}
                label="Applicants in pipeline"
                value={data.buckets.applicantsInPipeline}
                hint={`${data.buckets.totalApplications} total applications`}
              />
              <CountCard
                href="/careers/manager/applicant-pipeline?stage=OFFERED"
                icon={<Hourglass className="h-4 w-4" />}
                label="Offers awaiting signature"
                value={data.buckets.offersAwaitingSignature}
                tone={data.buckets.offersAwaitingSignature > 0 ? 'amber' : 'slate'}
              />
              <CountCard
                href="/careers/manager/onboarding-health"
                icon={<BadgeCheck className="h-4 w-4" />}
                label="Prospective new hires"
                value={data.buckets.prospectiveNewHires}
                hint="Signed offers through activation"
              />
              <CountCard
                href="/careers/manager/active-interns"
                icon={<GraduationCap className="h-4 w-4" />}
                label="Active interns"
                value={data.buckets.activeInterns}
                tone="emerald"
              />
              <CountCard
                href="/careers/manager/inactive-interns"
                icon={<UserMinus className="h-4 w-4" />}
                label="Inactive interns"
                value={data.buckets.inactiveInterns}
                hint="Completed / resigned / terminated"
              />
              <CountCard
                href="/careers/manager/risk-center"
                icon={<ShieldAlert className="h-4 w-4" />}
                label="Offers pending > 7d"
                value={data.kpis.offersPendingOver7Days}
                tone={data.kpis.offersPendingOver7Days > 0 ? 'rose' : 'slate'}
              />
            </div>
          </section>

          <section>
            <h2 className="mb-3 text-sm font-semibold text-slate-900">
              Conversion KPIs
            </h2>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              <KpiCard
                label="Shortlist conversion"
                pct={data.kpis.shortlistConversionPct}
                hint="Applications reaching interview stage or later"
              />
              <KpiCard
                label="Interview completion rate"
                pct={data.kpis.interviewCompletionPct}
                hint="Scheduled interviews actually conducted"
              />
              <KpiCard
                label="Offer signature rate"
                pct={data.kpis.offerSignaturePct}
                hint="Offers sent that resulted in ACCEPTED"
              />
            </div>
          </section>

          <section>
            <h2 className="mb-3 text-sm font-semibold text-slate-900">Sections</h2>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <SectionCard
                href="/careers/manager/applicant-pipeline"
                icon={<Users className="h-4 w-4" />}
                title="Applicant Pipeline"
                body="Filterable list of post-shortlist records with interview state, ERM owner, and expected start date."
              />
              <SectionCard
                href="/careers/manager/onboarding-health"
                icon={<BadgeCheck className="h-4 w-4" />}
                title="Onboarding Health"
                body="Signed offers, document verification, start-date countdowns, activation status."
              />
              <SectionCard
                href="/careers/manager/active-interns"
                icon={<GraduationCap className="h-4 w-4" />}
                title="Active Interns"
                body="Project assignment, weekly meetings, evaluation cadence, project progress."
              />
              <SectionCard
                href="/careers/manager/timesheet-approvals"
                icon={<ClipboardList className="h-4 w-4" />}
                title="Timesheet Approvals"
                body="Submitted / approved / rejected hours across all interns in your span of control."
              />
              <SectionCard
                href="/careers/manager/risk-center"
                icon={<ShieldAlert className="h-4 w-4" />}
                title="Risk Center"
                body="Overdue evaluations, missed meetings, work-auth expirations."
              />
              <SectionCard
                href="/careers/manager/reports"
                icon={<FileBarChart2 className="h-4 w-4" />}
                title="Reports"
                body="Monthly roll-ups + CSV exports for HR / leadership reviews."
              />
            </div>
          </section>

        </>
      )}
    </div>
  );
}

function HireApprovalsKpi({ count }: { count: number }) {
  const hasWork = count > 0;
  const containerCls = hasWork
    ? 'border-amber-300 bg-amber-50 hover:border-amber-400 hover:shadow'
    : 'border-slate-200 bg-white hover:border-brand-300 hover:shadow';
  const numberCls = hasWork ? 'text-amber-800' : 'text-slate-400';
  const iconCls = hasWork ? 'text-amber-700' : 'text-slate-500';
  return (
    <Link
      href="/careers/manager/hire-approvals"
      className={`block rounded-lg border p-4 shadow-sm ${containerCls}`}
    >
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <p className={`inline-flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide ${iconCls}`}>
            <Gavel className="h-3.5 w-3.5" />
            Hire Approvals
            {hasWork && (
              <span className="ml-1 rounded-full bg-amber-200 px-1.5 py-0.5 text-[10px] font-semibold text-amber-900">
                Awaiting you
              </span>
            )}
          </p>
          <p className={`mt-2 text-3xl font-semibold tabular-nums ${numberCls}`}>
            {count}
          </p>
          <p className="mt-1 text-xs text-slate-600">
            {hasWork
              ? `${count} interviewed candidate${count === 1 ? '' : 's'} awaiting your hire / no-hire decision.`
              : 'Nothing waiting on a hire decision right now.'}
          </p>
        </div>
        <span className={`text-xs font-semibold ${hasWork ? 'text-amber-800' : 'text-slate-400'}`}>
          Review →
        </span>
      </div>
    </Link>
  );
}

function CountCard({
  href, icon, label, value, hint, tone = 'slate',
}: {
  href: string;
  icon: React.ReactNode;
  label: string;
  value: number;
  hint?: string;
  tone?: 'slate' | 'emerald' | 'amber' | 'rose';
}) {
  const cls = tone === 'emerald' ? 'text-green-700'
    : tone === 'amber' ? 'text-amber-700'
    : tone === 'rose' ? 'text-red-700'
    : 'text-slate-900';
  return (
    <Link
      href={href}
      className="block rounded-lg border border-slate-200 bg-white p-3 shadow-sm hover:border-brand-300 hover:shadow"
    >
      <p className="inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
        {icon}
        {label}
      </p>
      <p className={`mt-1 text-2xl font-semibold tabular-nums ${cls}`}>{value}</p>
      {hint && <p className="text-[10px] text-slate-500">{hint}</p>}
    </Link>
  );
}

function KpiCard({
  label, pct, hint,
}: {
  label: string;
  pct: number | null;
  hint: string;
}) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
        {label}
      </p>
      <p className="mt-1 text-2xl font-semibold tabular-nums text-slate-900">
        {pct == null ? '—' : `${pct.toFixed(1)}%`}
      </p>
      <p className="mt-1 text-[11px] text-slate-500">{hint}</p>
      {pct != null && (
        <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-slate-100">
          <div
            className="h-full bg-brand-600"
            style={{ width: `${Math.min(100, pct)}%` }}
          />
        </div>
      )}
    </div>
  );
}

function SectionCard({
  href, icon, title, body,
}: {
  href: string;
  icon: React.ReactNode;
  title: string;
  body: string;
}) {
  return (
    <Link
      href={href}
      className="block rounded-lg border border-slate-200 bg-white p-4 shadow-sm hover:border-brand-300 hover:shadow"
    >
      <p className="inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-brand-700">
        {icon}
        {title}
      </p>
      <p className="mt-2 text-xs text-slate-700">{body}</p>
    </Link>
  );
}

/**
 * Per-queue Action Required tile — self-fetches its pending count so a
 * single slow / failing endpoint can't gate the whole "Action required"
 * row. Same visual language as HireApprovalsKpi: amber tint when there
 * IS work, muted otherwise; always links through to the queue so the
 * manager can act even when the count fetch fails.
 */
function PendingQueueTile({
  href, label, icon, fetchCount, blurbUnit,
}: {
  href: string;
  label: string;
  icon: React.ReactNode;
  fetchCount: () => Promise<number>;
  blurbUnit: string;
}) {
  const [count, setCount] = useState<number | null>(null);
  const [loaded, setLoaded] = useState(false);
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const n = await fetchCount();
        if (!cancelled) { setCount(n); setLoaded(true); }
      } catch {
        // Silent fail — tile still links + shows "—". Backend outage on
        // one queue must not hide the other queues.
        if (!cancelled) { setCount(null); setLoaded(true); }
      }
    })();
    return () => { cancelled = true; };
  }, [fetchCount]);
  const hasWork = (count ?? 0) > 0;
  const containerCls = hasWork
    ? 'border-amber-300 bg-amber-50 hover:border-amber-400 hover:shadow'
    : 'border-slate-200 bg-white hover:border-brand-300 hover:shadow';
  const numberCls = hasWork ? 'text-amber-800' : 'text-slate-400';
  const iconCls = hasWork ? 'text-amber-700' : 'text-slate-500';
  return (
    <Link href={href} className={`block rounded-lg border p-4 shadow-sm ${containerCls}`}>
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <p className={`inline-flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide ${iconCls}`}>
            {icon}
            {label}
            {hasWork && (
              <span className="ml-1 rounded-full bg-amber-200 px-1.5 py-0.5 text-[10px] font-semibold text-amber-900">
                Awaiting you
              </span>
            )}
          </p>
          <p className={`mt-2 text-3xl font-semibold tabular-nums ${numberCls}`}>
            {loaded ? (count ?? '—') : '…'}
          </p>
          <p className="mt-1 text-xs text-slate-600">
            {!loaded
              ? 'Counting…'
              : count == null
                ? `Open queue to see pending ${blurbUnit}s.`
                : hasWork
                  ? `${count} ${blurbUnit}${count === 1 ? '' : 's'} awaiting your review.`
                  : `Nothing waiting on a ${blurbUnit} review right now.`}
          </p>
        </div>
        <span className={`text-xs font-semibold ${hasWork ? 'text-amber-800' : 'text-slate-400'}`}>
          Review →
        </span>
      </div>
    </Link>
  );
}

// Count fetchers — module-scoped so PendingQueueTile can hold a stable
// reference in its dep array (avoids the tile re-firing on every parent
// render). Each returns 0 on empty + throws on network failure so the
// tile falls back to its "—" placeholder cleanly.

async function fetchPendingWeeklyReportCount(): Promise<number> {
  const res = await api.get<unknown[]>('/api/v1/manager/weekly-reports/pending');
  return Array.isArray(res.data) ? res.data.length : 0;
}

async function fetchPendingRecordingCount(): Promise<number> {
  const res = await api.get<{ items?: unknown[] }>('/api/v1/manager/recording-approvals');
  return Array.isArray(res.data?.items) ? res.data.items.length : 0;
}

async function fetchPendingTimesheetCount(): Promise<number> {
  // The timesheet queue is a monthly rollup — count VERIFIED cells
  // across all interns for the current period. VERIFIED = ready for
  // manager approval; SUBMITTED means ERM hasn't verified yet.
  const now = new Date();
  const y = now.getFullYear();
  const m = now.getMonth() + 1;
  const res = await api.get<{
    interns?: Array<{ cells?: Array<{ status?: string }> }>;
  }>(`/api/v1/timesheets/rollup?y=${y}&m=${m}&scope=manager`);
  const interns = res.data?.interns ?? [];
  let pending = 0;
  for (const intern of interns) {
    for (const cell of intern.cells ?? []) {
      if (cell.status === 'VERIFIED') pending++;
    }
  }
  return pending;
}
