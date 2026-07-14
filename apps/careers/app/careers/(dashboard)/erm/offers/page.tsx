'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Plus, Send } from 'lucide-react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import OfferStatusPill from '@/components/erm/offers/OfferStatusPill';
import type {
  OfferListPage,
  OfferRow,
} from '@/components/erm/offers/types';

// Phase 8.6 — "Awaiting Offer" becomes the default tab so ERM lands on the
// queue of applicants in INTERVIEWED+SELECTED state with no active offer.
// Other tabs filter the existing sent-offers list by status.
type TabKey = 'AWAITING' | '' | 'SENT' | 'SIGNED' | 'VOIDED' | 'EXPIRED' | 'DECLINED';

const TABS: { key: TabKey; label: string }[] = [
  { key: 'AWAITING', label: 'Awaiting Offer' },
  { key: '', label: 'All Sent' },
  { key: 'SENT', label: 'Sent' },
  { key: 'SIGNED', label: 'Signed' },
  { key: 'VOIDED', label: 'Voided' },
  { key: 'EXPIRED', label: 'Expired' },
  { key: 'DECLINED', label: 'Declined' },
];

interface AwaitingOfferRow {
  applicationId: string;
  interviewId: string;
  applicantName: string | null;
  applicantId: string | null;
  applicantEmail: string | null;
  jobTitle: string | null;
  jobType: string | null;
  interviewCompletedAt: string | null;
  overallRecommendation: string | null;
  technicalScore: number | null;
  communicationScore: number | null;
}

interface AwaitingOfferPage {
  items: AwaitingOfferRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export default function OfferControlPage() {
  const router = useRouter();
  const [tab, setTab] = useState<TabKey>('AWAITING');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [sentData, setSentData] = useState<OfferListPage | null>(null);
  const [awaitingData, setAwaitingData] = useState<AwaitingOfferPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (search.trim()) params.set('search', search.trim());
      params.set('page', String(page));
      params.set('pageSize', '25');
      if (tab === 'AWAITING') {
        const res = await api.get<AwaitingOfferPage>(
          `/api/v1/erm/offers/awaiting?${params.toString()}`,
        );
        setAwaitingData(res.data);
        setSentData(null);
      } else {
        if (tab) params.set('status', tab);
        const res = await api.get<OfferListPage>(
          `/api/v1/erm/offers?${params.toString()}`,
        );
        setSentData(res.data);
        setAwaitingData(null);
      }
      setErr(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load offers');
    } finally {
      setLoading(false);
    }
  }, [tab, search, page]);

  useEffect(() => { void load(); }, [load]);

  function goToOffer(applicationId: string) {
    router.push(`/careers/erm/offers/new?applicationId=${applicationId}`);
  }

  const totalElements = tab === 'AWAITING'
    ? awaitingData?.totalElements ?? 0
    : sentData?.totalElements ?? 0;
  const totalPages = tab === 'AWAITING'
    ? awaitingData?.totalPages ?? 0
    : sentData?.totalPages ?? 0;
  const currentPage = tab === 'AWAITING'
    ? awaitingData?.page ?? 0
    : sentData?.page ?? 0;

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <PageHeader
            title="Offers / IDMS"
            subtitle="Send new offers to selected applicants, then track, void, and resend the signing email."
          />
          <Link
            href="/careers/erm/decision-center"
            className="inline-flex items-center gap-1 self-center rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800"
          >
            <Plus className="h-4 w-4" />
            New Offer
          </Link>
        </div>

        <div className="mb-4 flex flex-wrap items-center gap-2">
          {TABS.map((c) => (
            <button
              key={c.key || 'all'}
              type="button"
              onClick={() => { setTab(c.key); setPage(0); }}
              className={
                'rounded-full border px-3 py-1 text-xs font-medium ' +
                (tab === c.key
                  ? 'border-brand-700 bg-brand-700 text-white'
                  : 'border-slate-200 text-slate-700 hover:bg-slate-50')
              }
            >
              {c.label}
            </button>
          ))}
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { setPage(0); void load(); } }}
            placeholder="Search applicant"
            className="ml-auto w-56 rounded-md border border-slate-200 px-3 py-1.5 text-sm"
          />
        </div>

        {err && (
          <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            {err}
          </p>
        )}

        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          {loading && !sentData && !awaitingData ? (
            <div className="h-40 animate-pulse" />
          ) : tab === 'AWAITING' ? (
            !awaitingData || awaitingData.items.length === 0 ? (
              <p className="p-10 text-center text-sm text-slate-500">
                No applicants awaiting an offer.
              </p>
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
                  {awaitingData.items.map((r) => (
                    <AwaitingRow key={r.applicationId} row={r} onSend={() => goToOffer(r.applicationId)} />
                  ))}
                </tbody>
              </table>
            )
          ) : (
            !sentData || sentData.items.length === 0 ? (
              <p className="p-10 text-center text-sm text-slate-500">
                No offers match the current filters.
              </p>
            ) : (
              <table className="min-w-full divide-y divide-slate-200 text-sm">
                <thead className="bg-slate-50">
                  <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                    <th className="px-3 py-2">Applicant</th>
                    <th className="px-3 py-2">Role / Comp</th>
                    <th className="px-3 py-2">Start</th>
                    <th className="px-3 py-2">Sent</th>
                    <th className="px-3 py-2">Expires</th>
                    <th className="px-3 py-2">Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {sentData.items.map((r) => <SentRow key={r.offerId} row={r} />)}
                </tbody>
              </table>
            )
          )}
        </div>

        {totalPages > 1 && (
          <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
            <span>
              Page {currentPage + 1} of {totalPages} ({totalElements} total)
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
                disabled={currentPage + 1 >= totalPages}
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

function AwaitingRow({ row, onSend }: { row: AwaitingOfferRow; onSend: () => void }) {
  return (
    <tr>
      <td className="px-3 py-2">
        <p className="text-sm font-medium text-slate-900">{row.applicantName ?? '—'}</p>
        <p className="text-[11px] text-slate-500">{row.applicantId ?? '—'}</p>
      </td>
      <td className="px-3 py-2">
        <p className="text-sm text-slate-800">{row.jobTitle ?? '—'}</p>
        {row.jobType && <p className="text-[11px] text-slate-500">{row.jobType}</p>}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {row.interviewCompletedAt
          ? new Date(row.interviewCompletedAt).toLocaleDateString()
          : '—'}
        {row.interviewId && (
          <Link
            href={`/careers/erm/interviews/${row.interviewId}`}
            className="ml-2 text-[11px] text-brand-700 hover:underline"
          >
            view
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

function SentRow({ row }: { row: OfferRow }) {
  const expiresSoon =
    row.status === 'SENT' &&
    row.expiresAt &&
    new Date(row.expiresAt).getTime() - Date.now() < 24 * 3600 * 1000;
  return (
    <tr>
      <td className="px-3 py-2">
        <Link href={`/careers/erm/offers/${row.offerId}`} className="hover:underline">
          <span className="block text-sm font-medium text-slate-900">
            {row.applicantName ?? '(unknown)'}
          </span>
          <span className="block text-[11px] text-slate-500">{row.applicantId}</span>
        </Link>
      </td>
      <td className="px-3 py-2">
        <span className="block text-sm text-slate-800">{row.roleTitle ?? row.jobTitle}</span>
        <span className="block text-[11px] text-slate-500">{row.compensationSummary}</span>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {row.tentativeStartDate ?? '—'}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {row.sentAt ? new Date(row.sentAt).toLocaleDateString() : '—'}
      </td>
      <td className="px-3 py-2 text-xs">
        {row.expiresAt ? (
          <span className={expiresSoon ? 'rounded-full bg-red-100 px-2 py-0.5 text-[10px] font-semibold text-red-700' : 'text-slate-700'}>
            {new Date(row.expiresAt).toLocaleDateString()}
          </span>
        ) : '—'}
        {row.reminderCount > 0 && (
          <span className="ml-1 text-[10px] text-slate-400">
            ({row.reminderCount} reminder{row.reminderCount === 1 ? '' : 's'})
          </span>
        )}
      </td>
      <td className="px-3 py-2">
        <OfferStatusPill status={row.status} />
        {row.archived && (
          <span className="ml-2 inline-block rounded-full bg-slate-100 px-2 py-0.5 text-[10px] text-slate-500">
            archived
          </span>
        )}
      </td>
    </tr>
  );
}
