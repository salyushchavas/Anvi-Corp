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
} from 'lucide-react';
import api from '@/lib/api';
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
          Numbers reconcile with ERM&apos;s view — same{' '}
          <code className="rounded bg-slate-100 px-1 py-0.5 text-[11px]">
            users.lifecycle_status
          </code>{' '}
          source of truth.
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
                hint="OFFER_SIGNED → ONBOARDING_ACCEPTED"
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
                href="/careers/manager/hire-approvals"
                icon={<Gavel className="h-4 w-4" />}
                title="Hire Approvals"
                body="Interviewed candidates awaiting your hire / no-hire decision. ERM submits the scorecard; you decide."
                phase={1}
              />
              <SectionCard
                href="/careers/manager/applicant-pipeline"
                icon={<Users className="h-4 w-4" />}
                title="Applicant Pipeline"
                body="Filterable list of post-shortlist records with interview state, ERM owner, and expected start date."
                phase={1}
              />
              <SectionCard
                href="/careers/manager/onboarding-health"
                icon={<BadgeCheck className="h-4 w-4" />}
                title="Onboarding Health"
                body="Signed offers, document verification, start-date countdowns, activation status."
                phase={2}
              />
              <SectionCard
                href="/careers/manager/active-interns"
                icon={<GraduationCap className="h-4 w-4" />}
                title="Active Interns"
                body="Project assignment, weekly meetings, evaluation cadence, project progress."
                phase={3}
              />
              <SectionCard
                href="/careers/manager/timesheet-approvals"
                icon={<ClipboardList className="h-4 w-4" />}
                title="Timesheet Approvals"
                body="Submitted / approved / rejected hours across all interns in your span of control."
                phase={3}
              />
              <SectionCard
                href="/careers/manager/risk-center"
                icon={<ShieldAlert className="h-4 w-4" />}
                title="Risk Center"
                body="Cross-cutting exceptions — overdue evaluations, missed meetings, work-auth expirations."
                phase={4}
              />
              <SectionCard
                href="/careers/manager/reports"
                icon={<FileBarChart2 className="h-4 w-4" />}
                title="Reports"
                body="Monthly roll-ups + CSV exports for HR / leadership reviews."
                phase={4}
              />
            </div>
          </section>

          <section className="rounded-lg border border-slate-200 bg-slate-50 p-4 text-[11px] text-slate-600">
            <p className="font-semibold text-slate-700">Note</p>
            <p className="mt-1">
              Operational KPIs (project progress, meeting cadence, evaluation
              SLA, timesheet posture) ship in Phases 3 and 4 alongside their
              underlying data. Phase 1 surfaces the pipeline funnel + lifecycle
              roll-up only; no fake numbers.
            </p>
          </section>
        </>
      )}
    </div>
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
  href, icon, title, body, phase,
}: {
  href: string;
  icon: React.ReactNode;
  title: string;
  body: string;
  phase: number;
}) {
  return (
    <Link
      href={href}
      className="block rounded-lg border border-slate-200 bg-white p-4 shadow-sm hover:border-brand-300 hover:shadow"
    >
      <div className="flex items-center justify-between">
        <p className="inline-flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-brand-700">
          {icon}
          {title}
        </p>
        <span className="text-[10px] text-slate-400">P{phase}</span>
      </div>
      <p className="mt-2 text-xs text-slate-700">{body}</p>
    </Link>
  );
}
