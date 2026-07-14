'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import { ArrowRight, CheckCircle2, RefreshCw, Send } from 'lucide-react';

interface AwaitingOfferRow {
  applicationId: string;
  interviewId: string;
  applicantName: string | null;
  applicantId: string | null;
  applicantEmail: string | null;
  jobTitle: string | null;
  jobType: string | null;
  technologyArea: string | null;
  interviewCompletedAt: string | null;
  overallRecommendation: string | null;
  technicalScore: number | null;
  communicationScore: number | null;
  applicantVisibleNotes: string | null;
}

interface AwaitingOfferPage {
  items: AwaitingOfferRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export default function ErmDecisionCenterPage() {
  const router = useRouter();
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<AwaitingOfferPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (search.trim()) params.set('search', search.trim());
      params.set('page', String(page));
      params.set('pageSize', '25');
      const res = await api.get<AwaitingOfferPage>(
        `/api/v1/erm/offers/awaiting?${params.toString()}`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load decision center');
    } finally {
      setLoading(false);
    }
  }, [search, page]);

  useEffect(() => { void load(); }, [load]);

  function goToOffer(applicationId: string) {
    router.push(`/careers/erm/offers/new?applicationId=${applicationId}`);
  }

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <PageHeader
          title="Decision Center"
          subtitle="Applicants selected at interview and waiting for an offer. Click Send Offer to start the offer letter."
        />

        <div className="mb-4 flex flex-wrap items-center gap-2">
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { setPage(0); void load(); } }}
            placeholder="Search applicant or applicant ID"
            className="w-72 rounded-md border border-slate-200 px-3 py-1.5 text-sm"
          />
          <button
            type="button"
            onClick={() => { setPage(0); void load(); }}
            className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          >
            <RefreshCw className="h-3.5 w-3.5" />
            Refresh
          </button>
          <span className="ml-auto text-xs text-slate-500">
            {data?.totalElements ?? 0} awaiting offer
          </span>
        </div>

        {err && (
          <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            {err}
          </p>
        )}

        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          {loading && !data ? (
            <div className="h-40 animate-pulse" />
          ) : !data || data.items.length === 0 ? (
            <EmptyState />
          ) : (
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50">
                <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                  <th className="px-3 py-2">Applicant</th>
                  <th className="px-3 py-2">Role</th>
                  <th className="px-3 py-2">Interview</th>
                  <th className="px-3 py-2">Scores</th>
                  <th className="px-3 py-2">Recommendation</th>
                  <th className="px-3 py-2"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data.items.map((r) => (
                  <Row key={r.applicationId} row={r} onSend={() => goToOffer(r.applicationId)} />
                ))}
              </tbody>
            </table>
          )}
        </div>

        {data && data.totalPages > 1 && (
          <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
            <span>
              Page {data.page + 1} of {data.totalPages} ({data.totalElements} total)
            </span>
            <div className="flex gap-1">
              <button
                type="button"
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                className="rounded-md border border-slate-200 px-2 py-1 disabled:opacity-50"
              >
                Prev
              </button>
              <button
                type="button"
                disabled={data.page + 1 >= data.totalPages}
                onClick={() => setPage((p) => p + 1)}
                className="rounded-md border border-slate-200 px-2 py-1 disabled:opacity-50"
              >
                Next
              </button>
            </div>
          </div>
        )}
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Row({ row, onSend }: { row: AwaitingOfferRow; onSend: () => void }) {
  return (
    <tr>
      <td className="px-3 py-2">
        <p className="text-sm font-medium text-slate-900">{row.applicantName ?? '—'}</p>
        <p className="text-[11px] text-slate-500">
          {row.applicantId ?? '—'}
          {row.applicantEmail && <span className="ml-2">{row.applicantEmail}</span>}
        </p>
      </td>
      <td className="px-3 py-2">
        <p className="text-sm text-slate-800">{row.jobTitle ?? '—'}</p>
        {row.jobType && (
          <p className="text-[11px] text-slate-500">{row.jobType}</p>
        )}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {row.interviewCompletedAt
          ? new Date(row.interviewCompletedAt).toLocaleDateString()
          : '—'}
        {row.interviewId && (
          <Link
            href={`/careers/erm/interviews/${row.interviewId}`}
            className="ml-2 inline-flex items-center gap-0.5 text-[11px] text-brand-700 hover:underline"
          >
            view
            <ArrowRight className="h-3 w-3" />
          </Link>
        )}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        T:{row.technicalScore ?? '—'} · C:{row.communicationScore ?? '—'}
      </td>
      <td className="px-3 py-2 text-xs">
        {row.overallRecommendation ? (
          <span className="rounded-full bg-green-50 px-2 py-0.5 font-semibold text-green-700">
            {row.overallRecommendation.replaceAll('_', ' ')}
          </span>
        ) : (
          <span className="text-slate-400">—</span>
        )}
      </td>
      <td className="px-3 py-2 text-right">
        <button
          type="button"
          onClick={onSend}
          className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
        >
          <Send className="h-3 w-3" />
          Send Offer
        </button>
      </td>
    </tr>
  );
}

function EmptyState() {
  return (
    <div className="flex flex-col items-center gap-3 p-12 text-center">
      <CheckCircle2 className="h-8 w-8 text-green-500" />
      <p className="text-sm font-medium text-slate-800">No applicants waiting for an offer.</p>
      <p className="max-w-sm text-xs text-slate-500">
        When you complete an interview with the <strong>SELECTED</strong> decision, the
        applicant lands here for next-step action.
      </p>
      <Link
        href="/careers/erm/interviews"
        className="mt-1 inline-flex items-center gap-1 text-xs font-medium text-brand-700 hover:underline"
      >
        Open Interviews
        <ArrowRight className="h-3 w-3" />
      </Link>
    </div>
  );
}
