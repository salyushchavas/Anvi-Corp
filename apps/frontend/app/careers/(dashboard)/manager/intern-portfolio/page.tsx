'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { ArrowRight, Search } from 'lucide-react';
import api from '@/lib/careers/api';

type Stage = 'ALL' | 'ACCOUNT_CREATED' | 'ONBOARDING' | 'ACTIVE' | 'EXITED';

interface PortfolioRow {
  userId: string;
  lifecycleId: string | null;
  fullName: string | null;
  email: string | null;
  employeeId: string | null;
  applicantId: string | null;
  stage: Stage;
  stageLabel: string;
  trainerName: string | null;
  evaluatorName: string | null;
  ermName: string | null;
  joinedAt: string | null;
  hiredAt: string | null;
  startedAt: string | null;
  endedAt: string | null;
}

interface StageCounts {
  accountCreated: number;
  onboarding: number;
  active: number;
  exited: number;
  total: number;
}

interface PortfolioListPage {
  items: PortfolioRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  stageCounts: StageCounts;
}

const STAGE_TABS: { key: Stage; label: string; count: (c: StageCounts) => number }[] = [
  { key: 'ALL',             label: 'All',             count: (c) => c.total },
  { key: 'ACCOUNT_CREATED', label: 'Account created', count: (c) => c.accountCreated },
  { key: 'ONBOARDING',      label: 'Onboarding',      count: (c) => c.onboarding },
  { key: 'ACTIVE',          label: 'Active',          count: (c) => c.active },
  { key: 'EXITED',          label: 'Exited',          count: (c) => c.exited },
];

const STAGE_PILL: Record<Stage, string> = {
  ALL:              'bg-slate-100 text-slate-700',
  ACCOUNT_CREATED:  'bg-slate-100 text-slate-700',
  ONBOARDING:       'bg-amber-100 text-amber-900',
  ACTIVE:           'bg-emerald-100 text-emerald-800',
  EXITED:           'bg-slate-100 text-slate-500',
};

export default function ManagerInternPortfolioPage() {
  const router = useRouter();
  const [search, setSearch] = useState('');
  const [stage, setStage] = useState<Stage>('ALL');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<PortfolioListPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (search.trim()) params.set('search', search.trim());
      if (stage !== 'ALL') params.set('stage', stage);
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<PortfolioListPage>(
        `/api/v1/manager/intern-portfolio?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load portfolio');
    } finally {
      setLoading(false);
    }
  }, [search, stage, page]);

  useEffect(() => { void load(); }, [load]);

  const rows = data?.items ?? [];
  const counts = data?.stageCounts;

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <div>
        <p className="text-xs text-slate-500">
          <Link href="/careers/manager" className="hover:text-slate-700">← Manager home</Link>
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">Intern Portfolio</h1>
        <p className="text-xs text-slate-500">
          Every intern across every stage. Read-only overview — actions live on the role-specific pages.
        </p>
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative">
            <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" />
            <input
              value={search}
              onChange={(e) => { setPage(0); setSearch(e.target.value); }}
              placeholder="Search name, email, or ID"
              className="w-72 rounded-md border border-slate-200 pl-8 pr-3 py-1.5 text-sm"
            />
          </div>
          <span className="ml-auto text-xs text-slate-500">
            {data?.totalElements ?? 0} shown
          </span>
        </div>

        <div className="mt-3 flex flex-wrap gap-1 border-b border-slate-200">
          {STAGE_TABS.map((t) => {
            const active = stage === t.key;
            const c = counts ? t.count(counts) : null;
            return (
              <button
                key={t.key}
                type="button"
                onClick={() => { setPage(0); setStage(t.key); }}
                className={
                  '-mb-px inline-flex items-center gap-2 border-b-2 px-3 py-2 text-sm ' +
                  (active
                    ? 'border-brand-700 font-semibold text-brand-700'
                    : 'border-transparent text-slate-600 hover:text-slate-900')
                }
              >
                {t.label}
                {c != null && (
                  <span className={
                    'rounded-full px-1.5 py-0.5 text-[10px] font-semibold ' +
                    (active ? 'bg-brand-100 text-brand-800' : 'bg-slate-100 text-slate-700')
                  }>{c}</span>
                )}
              </button>
            );
          })}
        </div>
      </div>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-48 animate-pulse" />
        ) : rows.length === 0 ? (
          <EmptyState stage={stage} hasSearch={!!search.trim()} />
        ) : (
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-3 py-2">Intern</th>
                <th className="px-3 py-2">Stage</th>
                <th className="px-3 py-2">Team</th>
                <th className="px-3 py-2">Joined / Started</th>
                <th className="px-3 py-2 text-right"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((r) => (
                <tr key={r.userId} className="cursor-pointer hover:bg-slate-50"
                  onClick={() => router.push(`/careers/manager/intern-portfolio/${r.userId}`)}>
                  <td className="px-3 py-2">
                    <p className="text-sm font-medium text-slate-900">{r.fullName ?? '—'}</p>
                    <p className="text-[11px] text-slate-500">
                      {r.employeeId ?? r.applicantId ?? '—'}
                      {r.email && <span className="ml-2">· {r.email}</span>}
                    </p>
                  </td>
                  <td className="whitespace-nowrap px-3 py-2">
                    <span className={
                      'inline-flex whitespace-nowrap rounded-full px-2 py-0.5 text-[10px] font-semibold '
                      + (STAGE_PILL[r.stage] ?? STAGE_PILL.ALL)
                    }>
                      {r.stageLabel}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-[11px] text-slate-700">
                    {r.trainerName && <p>Trainer: <span className="font-medium text-slate-900">{r.trainerName}</span></p>}
                    {r.evaluatorName && <p>Evaluator: <span className="font-medium text-slate-900">{r.evaluatorName}</span></p>}
                    {r.ermName && <p>ERM: <span className="font-medium text-slate-900">{r.ermName}</span></p>}
                    {!r.trainerName && !r.evaluatorName && !r.ermName && (
                      <span className="italic text-slate-400">Not assigned</span>
                    )}
                  </td>
                  <td className="whitespace-nowrap px-3 py-2 text-[11px] text-slate-700">
                    {r.startedAt
                      ? <>Started {new Date(r.startedAt).toLocaleDateString()}</>
                      : r.joinedAt
                        ? <>Joined {new Date(r.joinedAt).toLocaleDateString()}</>
                        : '—'}
                    {r.endedAt && (
                      <p className="text-slate-500">Ended {new Date(r.endedAt).toLocaleDateString()}</p>
                    )}
                  </td>
                  <td className="whitespace-nowrap px-3 py-2 text-right">
                    <button
                      type="button"
                      onClick={(e) => {
                        e.stopPropagation();
                        router.push(`/careers/manager/intern-portfolio/${r.userId}`);
                      }}
                      className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-50"
                    >
                      Open
                      <ArrowRight className="h-3 w-3" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between text-xs text-slate-600">
          <span>Page {data.page + 1} of {data.totalPages}</span>
          <div className="flex gap-2">
            <button type="button" disabled={data.page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              className="rounded-md border border-slate-200 px-3 py-1 disabled:opacity-50">Prev</button>
            <button type="button" disabled={data.page + 1 >= data.totalPages}
              onClick={() => setPage((p) => p + 1)}
              className="rounded-md border border-slate-200 px-3 py-1 disabled:opacity-50">Next</button>
          </div>
        </div>
      )}
    </div>
  );
}

function EmptyState({ stage, hasSearch }: { stage: Stage; hasSearch: boolean }) {
  const line = hasSearch
    ? 'No interns match this search.'
    : stage === 'ALL'
      ? 'No interns on the portfolio yet.'
      : stage === 'ACCOUNT_CREATED' ? 'No interns at the account-created stage.'
      : stage === 'ONBOARDING'      ? 'No interns currently in onboarding.'
      : stage === 'ACTIVE'          ? 'No interns currently active.'
      :                                'No exited interns yet.';
  return (
    <div className="p-12 text-center text-sm text-slate-500">
      {line}
    </div>
  );
}
