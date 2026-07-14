'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import {
  AlertTriangle, BadgeCheck, ChevronLeft, FileText, Filter,
  Hourglass, Search, ShieldAlert,
} from 'lucide-react';
import api from '@/lib/api';
import type {
  OnboardingFilterOptions,
  OnboardingResponse,
  OnboardingRow,
  OnboardingSummary,
} from '@/components/manager/types';

export default function OnboardingHealthPage() {
  return (
    <Suspense fallback={<div className="p-6 text-sm text-slate-500">Loading…</div>}>
      <OnboardingInner />
    </Suspense>
  );
}

function OnboardingInner() {
  const sp = useSearchParams();
  const initialStage = sp?.get('stage') ?? 'ALL';

  const [stage, setStage] = useState<string>(initialStage);
  const [workAuthType, setWorkAuthType] = useState<string>('');
  const [ermOwner, setErmOwner] = useState<string>('');
  const [needsAttention, setNeedsAttention] = useState<boolean>(false);
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);

  const [data, setData] = useState<OnboardingResponse | null>(null);
  const [filters, setFilters] = useState<OnboardingFilterOptions | null>(null);
  const [summary, setSummary] = useState<OnboardingSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        const [f, s] = await Promise.all([
          api.get<OnboardingFilterOptions>('/api/v1/manager/onboarding/filters'),
          api.get<OnboardingSummary>('/api/v1/manager/onboarding/summary'),
        ]);
        setFilters(f.data);
        setSummary(s.data);
      } catch {
        // non-fatal — list will still render
      }
    })();
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const params = new URLSearchParams();
      if (stage && stage !== 'ALL') params.set('stage', stage);
      if (workAuthType) params.set('workAuthType', workAuthType);
      if (ermOwner) params.set('ermOwner', ermOwner);
      if (needsAttention) params.set('needsAttention', 'true');
      if (search.trim()) params.set('search', search.trim());
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<OnboardingResponse>(
        `/api/v1/manager/onboarding?${params.toString()}`,
      );
      setData(res.data);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load onboarding');
    } finally {
      setLoading(false);
    }
  }, [stage, workAuthType, ermOwner, needsAttention, search, page]);
  useEffect(() => { void load(); }, [load]);

  return (
    <div className="mx-auto max-w-7xl space-y-4 p-6">
      <p className="text-xs text-slate-500">
        <Link
          href="/careers/manager"
          className="inline-flex items-center gap-1 hover:text-slate-700"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          Manager home
        </Link>
      </p>

      <header className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h1 className="text-xl font-semibold text-slate-900">Onboarding Health</h1>
        <p className="text-xs text-slate-500">
          New hires from offer-signed through onboarding-accepted. Read-only —
          document status &amp; compliance flags only, never form contents.
          Start-date risk window: 7 days.
        </p>
      </header>

      {summary && (
        <section className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          <SummaryCard
            icon={<Hourglass className="h-4 w-4" />}
            label="Offers awaiting signature"
            value={summary.offersAwaitingSignature}
          />
          <SummaryCard
            icon={<FileText className="h-4 w-4" />}
            label="New hires onboarding"
            value={summary.newHiresOnboarding}
          />
          <SummaryCard
            icon={<BadgeCheck className="h-4 w-4" />}
            label="Onboarding accepted"
            value={summary.onboardingAccepted}
            tone="emerald"
          />
          <SummaryCard
            icon={<ShieldAlert className="h-4 w-4" />}
            label="I-9 overdue"
            value={summary.i9Overdue}
            tone={summary.i9Overdue > 0 ? 'rose' : 'slate'}
          />
          <SummaryCard
            icon={<ShieldAlert className="h-4 w-4" />}
            label="E-Verify overdue"
            value={summary.everifyOverdue}
            tone={summary.everifyOverdue > 0 ? 'rose' : 'slate'}
          />
          <SummaryCard
            icon={<AlertTriangle className="h-4 w-4" />}
            label="Start date at risk"
            value={summary.startDateAtRisk}
            tone={summary.startDateAtRisk > 0 ? 'amber' : 'slate'}
          />
        </section>
      )}

      <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <div className="grid grid-cols-1 gap-3 md:grid-cols-5">
          <label className="md:col-span-2 block">
            <span className="mb-1 inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              <Search className="h-3 w-3" />
              Search name / email / employee ID
            </span>
            <input
              type="text"
              value={search}
              onChange={(e) => { setSearch(e.target.value); setPage(0); }}
              placeholder="Search…"
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            />
          </label>
          <label className="block">
            <span className="mb-1 inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              <Filter className="h-3 w-3" />
              Stage
            </span>
            <select
              value={stage}
              onChange={(e) => { setStage(e.target.value); setPage(0); }}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="ALL">All onboarding stages</option>
              {filters?.lifecycleStages.map((s) => (
                <option key={s} value={s}>{s.replaceAll('_', ' ')}</option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="mb-1 inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              <Filter className="h-3 w-3" />
              Work auth
            </span>
            <select
              value={workAuthType}
              onChange={(e) => { setWorkAuthType(e.target.value); setPage(0); }}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="">All</option>
              {filters?.workAuthTypes.map((w) => (
                <option key={w} value={w}>{w.replaceAll('_', ' ')}</option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="mb-1 inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
              <Filter className="h-3 w-3" />
              ERM owner
            </span>
            <select
              value={ermOwner}
              onChange={(e) => { setErmOwner(e.target.value); setPage(0); }}
              className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm"
            >
              <option value="">All</option>
              {filters?.ermOwners.map((o) => (
                <option key={o.userId} value={o.userId}>{o.fullName}</option>
              ))}
            </select>
          </label>
        </div>
        <label className="mt-3 inline-flex cursor-pointer items-center gap-2 text-sm text-slate-700">
          <input
            type="checkbox"
            checked={needsAttention}
            onChange={(e) => { setNeedsAttention(e.target.checked); setPage(0); }}
            className="h-4 w-4 accent-teal-700"
          />
          Needs attention only (rejected docs / overdue I-9 or E-Verify / start at risk)
        </label>
      </section>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <section className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full text-sm">
          <thead className="bg-slate-50 text-[11px] uppercase tracking-wide text-slate-600">
            <tr>
              <th className="px-3 py-2 text-left">New hire</th>
              <th className="px-3 py-2 text-left">Stage</th>
              <th className="px-3 py-2 text-left">Documents</th>
              <th className="px-3 py-2 text-left">I-9</th>
              <th className="px-3 py-2 text-left">E-Verify</th>
              <th className="px-3 py-2 text-left">Work auth</th>
              <th className="px-3 py-2 text-left">Start date</th>
              <th className="px-3 py-2 text-left">ERM</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading && !data && (
              <tr><td colSpan={8} className="p-6 text-center text-slate-500">Loading…</td></tr>
            )}
            {data && data.items.length === 0 && !loading && (
              <tr><td colSpan={8} className="p-6 text-center text-slate-500">
                No new hires match these filters.
              </td></tr>
            )}
            {data?.items.map((r) => <Row key={r.internLifecycleId} row={r} />)}
          </tbody>
        </table>
      </section>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between rounded-md border border-slate-200 bg-white px-3 py-2 text-xs text-slate-600">
          <p>
            Page {data.page + 1} of {data.totalPages} · {data.totalElements} total
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              className="rounded-md border border-slate-200 bg-white px-3 py-1 hover:bg-slate-50 disabled:opacity-50"
            >
              Prev
            </button>
            <button
              type="button"
              disabled={page + 1 >= data.totalPages}
              onClick={() => setPage((p) => p + 1)}
              className="rounded-md border border-slate-200 bg-white px-3 py-1 hover:bg-slate-50 disabled:opacity-50"
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function Row({ row }: { row: OnboardingRow }) {
  return (
    <tr className="align-top hover:bg-slate-50">
      <td className="px-3 py-2">
        <p className="font-medium text-slate-900">{row.internName ?? '—'}</p>
        <p className="text-[11px] text-slate-500">
          {row.employeeId ?? ''}{row.internEmail ? ` · ${row.internEmail}` : ''}
        </p>
      </td>
      <td className="px-3 py-2">
        <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-semibold text-slate-700">
          {row.lifecycleStatus.replaceAll('_', ' ')}
        </span>
      </td>
      <td className="px-3 py-2">
        <DocumentCell docs={row.documents} />
      </td>
      <td className="px-3 py-2">
        <ComplianceCell
          status={row.compliance?.i9Status ?? null}
          overdue={row.compliance?.i9Overdue ?? false}
          dueDate={row.compliance?.i9Section2DueDate ?? null}
          okValues={['COMPLETED']}
        />
      </td>
      <td className="px-3 py-2">
        <ComplianceCell
          status={row.compliance?.everifyStatus ?? null}
          overdue={row.compliance?.everifyOverdue ?? false}
          dueDate={row.compliance?.everifyDueBy ?? null}
          okValues={['EMPLOYMENT_AUTHORIZED', 'CLOSED']}
        />
      </td>
      <td className="px-3 py-2 text-[11px] text-slate-700">
        <p>{row.workAuthType?.replaceAll('_', ' ') ?? '—'}</p>
        {row.compliance?.workAuthValidUntil && (
          <p className={
            'text-[10px] ' +
            (row.compliance.workAuthExpiringSoon ? 'text-red-700' : 'text-slate-500')
          }>
            valid until {row.compliance.workAuthValidUntil}
          </p>
        )}
      </td>
      <td className="px-3 py-2 text-[11px]">
        <p className="text-slate-700">{row.tentativeStartDate ?? '—'}</p>
        {row.daysUntilStart != null && row.tentativeStartDate && (
          <p className={
            row.startDateAtRisk ? 'text-amber-700 font-semibold' : 'text-slate-500'
          }>
            {row.daysUntilStart < 0
              ? `${Math.abs(row.daysUntilStart)}d past`
              : row.daysUntilStart === 0
                ? 'today'
                : `in ${row.daysUntilStart}d`}
            {row.startDateAtRisk && ' · at risk'}
          </p>
        )}
      </td>
      <td className="px-3 py-2 text-[11px] text-slate-700">
        {row.ermOwnerName ?? <span className="text-amber-700">Unassigned</span>}
      </td>
    </tr>
  );
}

function DocumentCell({ docs }: { docs: OnboardingRow['documents'] }) {
  if (!docs) {
    return <span className="text-[11px] text-slate-500">No packet yet</span>;
  }
  return (
    <div className="space-y-1">
      <p className="text-[11px] font-medium text-slate-800">
        <span className="tabular-nums">{docs.acceptedTasks}</span>
        <span className="text-slate-500"> / {docs.totalTasks} accepted</span>
        {docs.hasRejected && (
          <span className="ml-2 inline-flex items-center gap-0.5 rounded-full bg-red-100 px-1.5 py-0.5 text-[10px] font-semibold text-red-700">
            <AlertTriangle className="h-3 w-3" />
            {docs.rejectedTasks} rejected
          </span>
        )}
      </p>
      <div className="flex flex-wrap gap-1">
        {docs.acceptedTasks > 0 && <Pill tone="emerald">Accepted {docs.acceptedTasks}</Pill>}
        {docs.submittedTasks > 0 && <Pill tone="violet">Under review {docs.submittedTasks}</Pill>}
        {docs.pendingTasks > 0 && <Pill tone="slate">Pending {docs.pendingTasks}</Pill>}
        {docs.rejectedTasks > 0 && <Pill tone="rose">Rejected {docs.rejectedTasks}</Pill>}
        {docs.waivedTasks > 0 && <Pill tone="amber">Waived {docs.waivedTasks}</Pill>}
      </div>
      <p className="text-[10px] text-slate-400">
        Packet: {docs.packetStatus?.replaceAll('_', ' ') ?? '—'}
      </p>
    </div>
  );
}

function ComplianceCell({
  status, overdue, dueDate, okValues,
}: {
  status: string | null;
  overdue: boolean;
  dueDate: string | null;
  okValues: string[];
}) {
  if (!status) return <span className="text-[11px] text-slate-500">—</span>;
  const ok = okValues.includes(status);
  const tone = overdue ? 'rose' : ok ? 'emerald' : 'slate';
  return (
    <div className="text-[11px]">
      <Pill tone={tone}>{status.replaceAll('_', ' ')}</Pill>
      {dueDate && !ok && (
        <p className={
          'mt-1 text-[10px] ' + (overdue ? 'text-red-700 font-semibold' : 'text-slate-500')
        }>
          due {dueDate}{overdue && ' · overdue'}
        </p>
      )}
    </div>
  );
}

function Pill({
  tone, children,
}: {
  tone: 'emerald' | 'violet' | 'slate' | 'rose' | 'amber';
  children: React.ReactNode;
}) {
  const map = {
    emerald: 'bg-green-100 text-green-700',
    violet: 'bg-amber-100 text-amber-800',
    slate: 'bg-slate-100 text-slate-700',
    rose: 'bg-red-100 text-red-700',
    amber: 'bg-amber-100 text-amber-800',
  };
  return (
    <span className={
      'rounded-full px-1.5 py-0.5 text-[10px] font-semibold ' + map[tone]
    }>
      {children}
    </span>
  );
}

function SummaryCard({
  icon, label, value, tone = 'slate',
}: {
  icon: React.ReactNode;
  label: string;
  value: number;
  tone?: 'slate' | 'emerald' | 'amber' | 'rose';
}) {
  const cls = tone === 'emerald' ? 'text-green-700'
    : tone === 'amber' ? 'text-amber-700'
    : tone === 'rose' ? 'text-red-700'
    : 'text-slate-900';
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
      <p className="inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">
        {icon}
        {label}
      </p>
      <p className={`mt-1 text-2xl font-semibold tabular-nums ${cls}`}>{value}</p>
    </div>
  );
}
