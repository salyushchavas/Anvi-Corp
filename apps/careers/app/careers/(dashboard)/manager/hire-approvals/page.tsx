'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import api from '@/lib/api';
import type {
  HireApprovalListPage,
  HireApprovalRow,
} from '@/components/manager/hire-approval-types';

export default function HireApprovalsPage() {
  const [data, setData] = useState<HireApprovalListPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<HireApprovalListPage>(
        `/api/v1/manager/hire-approvals?page=${page}&pageSize=25`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally {
      setLoading(false);
    }
  }, [page]);

  useEffect(() => { void load(); }, [load]);

  return (
    <div className="mx-auto max-w-6xl space-y-5 p-6">
      <header className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <p className="text-[10px] font-semibold uppercase tracking-wide text-brand-700">
          Manager
        </p>
        <h1 className="mt-1 text-2xl font-semibold text-slate-900">
          Hire Approvals
        </h1>
        <p className="mt-1 text-sm text-slate-600">
          Interviewed candidates awaiting your hire / no-hire decision.
          ERM has submitted the scorecard; clicking a name opens the
          full review with approve / reject actions.
        </p>
      </header>

      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <div className="mb-3 flex items-center justify-between">
        <p className="text-xs text-slate-500">
          {data ? `${data.totalElements} awaiting your decision` : ''}
        </p>
      </div>

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-48 animate-pulse" />
        ) : !data || data.items.length === 0 ? (
          <p className="p-10 text-center text-sm text-slate-500">
            Nothing waiting on a hire decision.
          </p>
        ) : (
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-3 py-2">Candidate</th>
                <th className="px-3 py-2">Position</th>
                <th className="px-3 py-2">Scores (T/C/F)</th>
                <th className="px-3 py-2">ERM recommendation</th>
                <th className="px-3 py-2">Waiting</th>
                <th className="px-3 py-2"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {data.items.map((r) => (
                <Row key={r.interviewId} r={r} />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {data && data.totalPages > 1 && (
        <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
          <span>Page {data.page + 1} of {data.totalPages}</span>
          <div className="flex gap-1">
            <button
              type="button"
              disabled={page === 0}
              onClick={() => setPage((x) => Math.max(0, x - 1))}
              className="rounded-md border border-slate-200 px-2 py-1 disabled:opacity-50"
            >
              Prev
            </button>
            <button
              type="button"
              disabled={data.page + 1 >= data.totalPages}
              onClick={() => setPage((x) => x + 1)}
              className="rounded-md border border-slate-200 px-2 py-1 disabled:opacity-50"
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function Row({ r }: { r: HireApprovalRow }) {
  const href = `/careers/manager/hire-approvals/${r.interviewId}`;
  const scores = [r.technicalScore, r.communicationScore, r.culturalFitScore]
    .map((s) => (s == null ? '—' : String(s)))
    .join(' / ');
  return (
    <tr className="hover:bg-slate-50">
      <td className="px-3 py-2">
        <Link href={href} className="text-sm font-medium text-brand-800 hover:underline">
          {r.candidateName ?? '—'}
        </Link>
        {r.managerHireDecision === 'HOLD' && (
          <span className="ml-2 rounded-full bg-amber-100 px-1.5 py-0.5 text-[10px] font-semibold text-amber-800">
            ON HOLD
          </span>
        )}
        {r.candidateEmail && (
          <p className="text-[11px] text-slate-500">{r.candidateEmail}</p>
        )}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {r.jobTitle ?? '—'}
        {r.technology && (
          <span className="ml-2 inline-block rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-700">
            {r.technology}
          </span>
        )}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">{scores}</td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {r.overallRecommendation
          ? r.overallRecommendation.replace(/_/g, ' ')
          : '—'}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {r.scorecardSubmittedAt
          ? `${Math.round(r.hoursWaiting)}h`
          : '—'}
      </td>
      <td className="px-3 py-2">
        <Link
          href={href}
          className="rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
        >
          Review
        </Link>
      </td>
    </tr>
  );
}
