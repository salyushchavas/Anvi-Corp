'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import type {
  InternReviewQueuePage,
  InternReviewQueueRow,
} from '@/components/erm/documents/types';

export default function DocumentReviewQueuePage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <PageHeader
          title="Document review queue"
          subtitle="Interns with documents awaiting an accept / reject / resend decision. Click a name to review their packet."
        />
        <Suspense fallback={<div className="h-48 animate-pulse rounded-lg bg-slate-100" />}>
          <Queue />
        </Suspense>
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Queue() {
  const [data, setData] = useState<InternReviewQueuePage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<InternReviewQueuePage>(
        `/api/v1/erm/document-review/queue/by-intern?page=${page}&pageSize=25`,
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
    <>
      {err && (
        <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <div className="mb-3 flex items-center justify-between">
        <p className="text-xs text-slate-500">
          {data ? `${data.totalElements} ${data.totalElements === 1 ? 'intern' : 'interns'} awaiting review` : ''}
        </p>
      </div>

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-48 animate-pulse" />
        ) : !data || data.items.length === 0 ? (
          <p className="p-10 text-center text-sm text-slate-500">
            The queue is empty.
          </p>
        ) : (
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-3 py-2">Intern</th>
                <th className="px-3 py-2">Documents</th>
                <th className="px-3 py-2">Oldest waiting</th>
                <th className="px-3 py-2"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {data.items.map((r) => (
                <InternRow key={r.internLifecycleId} r={r} />
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
    </>
  );
}

function InternRow({ r }: { r: InternReviewQueueRow }) {
  const href = `/careers/erm/document-review/${r.internLifecycleId}`;
  const docsLabel = r.pendingCount === 1
    ? '1 document pending review'
    : `${r.pendingCount} documents pending review`;
  return (
    <tr className="hover:bg-slate-50">
      <td className="px-3 py-2">
        <Link href={href} className="text-sm font-medium text-brand-800 hover:underline">
          {r.internName ?? '—'}
        </Link>
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">{docsLabel}</td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {r.oldestSubmittedAt
          ? `${Math.round(r.oldestHoursWaiting)}h (since ${new Date(r.oldestSubmittedAt).toLocaleString()})`
          : '—'}
      </td>
      <td className="px-3 py-2">
        <Link
          href={href}
          className="rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
        >
          Review documents
        </Link>
      </td>
    </tr>
  );
}
