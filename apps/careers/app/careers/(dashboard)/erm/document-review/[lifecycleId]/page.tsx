'use client';

import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useParams, useSearchParams } from 'next/navigation';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import ReviewTaskModal from '@/components/erm/documents/ReviewTaskModal';
import type {
  DocumentTaskListPage,
  DocumentTaskRow,
} from '@/components/erm/documents/types';

export default function PerInternReviewPage() {
  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <Suspense fallback={<div className="h-48 animate-pulse rounded-lg bg-slate-100" />}>
          <InternQueue />
        </Suspense>
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function InternQueue() {
  const params = useParams<{ lifecycleId: string }>();
  const lifecycleId = params?.lifecycleId;
  const sp = useSearchParams();
  const focus = sp?.get('focus') ?? null;

  const [data, setData] = useState<DocumentTaskListPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [openTaskId, setOpenTaskId] = useState<string | null>(focus);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulking, setBulking] = useState(false);

  const load = useCallback(async () => {
    if (!lifecycleId) return;
    setLoading(true);
    try {
      const res = await api.get<DocumentTaskListPage>(
        `/api/v1/erm/document-review/queue?internLifecycleId=${lifecycleId}&page=0&pageSize=100`,
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally {
      setLoading(false);
    }
  }, [lifecycleId]);

  useEffect(() => { void load(); }, [load]);

  function toggle(id: string) {
    setSelected((cur) => {
      const next = new Set(cur);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  async function bulkAccept() {
    if (selected.size === 0) return;
    if (!confirm(`Accept ${selected.size} submissions?`)) return;
    setBulking(true);
    try {
      await api.post('/api/v1/erm/document-review/tasks/bulk-review', {
        taskIds: Array.from(selected),
        decision: 'ACCEPT',
      });
      setSelected(new Set());
      await load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      alert(ax.response?.data?.error ?? ax.message ?? 'Failed');
    } finally {
      setBulking(false);
    }
  }

  const internName = data?.items[0]?.internName ?? '—';

  return (
    <>
      <PageHeader
        title={`Documents for ${internName}`}
        subtitle="Accept / reject / request resend per document. Bulk-accept is scoped to this intern."
      />
      <p className="mb-3 text-xs">
        <Link href="/careers/erm/document-review" className="text-brand-700 hover:underline">
          ← Back to all interns
        </Link>
      </p>

      {err && (
        <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
          {err}
        </p>
      )}

      <div className="mb-3 flex items-center justify-between">
        <p className="text-xs text-slate-500">
          {data ? `${data.totalElements} awaiting review` : ''}
        </p>
        <button
          type="button"
          onClick={bulkAccept}
          disabled={selected.size === 0 || bulking}
          className="rounded-md bg-green-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-green-800 disabled:bg-slate-300"
        >
          Bulk-accept {selected.size} selected
        </button>
      </div>

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        {loading && !data ? (
          <div className="h-48 animate-pulse" />
        ) : !data || data.items.length === 0 ? (
          <p className="p-10 text-center text-sm text-slate-500">
            Nothing left to review for this intern.
          </p>
        ) : (
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-2 py-2"></th>
                <th className="px-3 py-2">Document</th>
                <th className="px-3 py-2">Submitted</th>
                <th className="px-3 py-2">Waiting</th>
                <th className="px-3 py-2"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {data.items.map((r) => (
                <TaskRow
                  key={r.taskId}
                  r={r}
                  checked={selected.has(r.taskId)}
                  onToggle={() => toggle(r.taskId)}
                  onOpen={() => setOpenTaskId(r.taskId)}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>

      {openTaskId && (
        <ReviewTaskModal
          taskId={openTaskId}
          onClose={() => setOpenTaskId(null)}
          onReviewed={() => { setOpenTaskId(null); void load(); }}
        />
      )}
    </>
  );
}

function TaskRow({
  r, checked, onToggle, onOpen,
}: {
  r: DocumentTaskRow;
  checked: boolean;
  onToggle: () => void;
  onOpen: () => void;
}) {
  return (
    <tr>
      <td className="px-2 py-2">
        <input type="checkbox" checked={checked} onChange={onToggle} />
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {r.templateTitle}
        {r.category && (
          <span className="ml-2 inline-block rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-700">
            {r.category}
          </span>
        )}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {r.submittedAt ? new Date(r.submittedAt).toLocaleString() : '—'}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {Math.round(r.hoursWaiting)}h
      </td>
      <td className="px-3 py-2">
        <button
          type="button"
          onClick={onOpen}
          className="rounded-md bg-brand-700 px-3 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
        >
          Review
        </button>
      </td>
    </tr>
  );
}
