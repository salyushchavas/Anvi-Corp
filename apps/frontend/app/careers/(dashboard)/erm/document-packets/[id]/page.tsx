'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import Link from 'next/link';
import { ChevronLeft, ExternalLink, Plus } from 'lucide-react';
import api from '@/lib/careers/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import ReasonDialog from '@/components/ReasonDialog';
import AssignAdditionalDocumentModal from '@/components/erm/documents/AssignAdditionalDocumentModal';
import type {
  DocumentPacketDetail,
  TaskSummary,
  TaskStatus,
  PacketStatus,
} from '@/components/erm/documents/types';

export default function DocumentPacketDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const router = useRouter();
  const [p, setP] = useState<DocumentPacketDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [actionErr, setActionErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [waiveOpen, setWaiveOpen] = useState(false);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<DocumentPacketDetail>(
        `/api/v1/erm/document-packets/${id}`,
      );
      setP(res.data);
      setErr(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setErr(ax.response?.data?.error ?? ax.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  // Destructive actions now go through the themed ReasonDialog —
  // replaces the previous window.prompt() → alert() loop that was
  // unstyled, easy to bypass, and left the app-shell backgrounded
  // during the browser-native prompt. Both actions require a ≥10-char
  // rationale for the audit trail.
  async function runCancel(reason: string) {
    if (!id) return;
    try {
      await api.post(`/api/v1/erm/document-packets/${id}/cancel`, { reason });
      setCancelOpen(false);
      await load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setActionErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    }
  }

  async function runWaivePending(reason: string) {
    if (!id) return;
    try {
      await api.post(`/api/v1/erm/document-packets/${id}/waive-pending`, { reason });
      setWaiveOpen(false);
      await load();
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setActionErr(ax.response?.data?.error ?? ax.message ?? 'Failed');
    }
  }

  if (loading && !p) {
    return (
      <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
        <DashboardLayout>
          <PageHeader title="Document packet" />
          <div className="h-48 animate-pulse rounded-lg bg-slate-100" />
        </DashboardLayout>
      </ProtectedRoute>
    );
  }
  if (err || !p) {
    return (
      <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
        <DashboardLayout>
          <PageHeader title="Document packet" />
          <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
            {err ?? 'Not found'}
          </p>
        </DashboardLayout>
      </ProtectedRoute>
    );
  }

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <Link
          href="/careers/erm/document-packets"
          className="mb-3 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
        >
          <ChevronLeft className="h-4 w-4" /> Back to packets
        </Link>
        <PageHeader
          title={p.internName ?? 'Intern'}
          subtitle={`${p.internEmployeeId ?? ''} · ${p.internEmail ?? ''}`}
        />

        <div className="mb-4 flex items-center gap-3">
          <PacketBadge status={p.status} />
          {p.assignedAt && (
            <span className="text-xs text-slate-500">
              Assigned {new Date(p.assignedAt).toLocaleString()}
            </span>
          )}
          {p.completedAt && (
            <span className="text-xs text-green-700">
              Completed {new Date(p.completedAt).toLocaleString()}
            </span>
          )}
        </div>

        {p.customInstructions && (
          <section className="mb-4 rounded-lg border border-slate-200 bg-slate-50 p-4">
            <h3 className="text-xs font-semibold uppercase text-slate-500">
              Custom instructions to intern
            </h3>
            <p className="mt-2 whitespace-pre-wrap text-sm text-slate-700">
              {p.customInstructions}
            </p>
          </section>
        )}

        {actionErr && (
          <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            {actionErr}
          </p>
        )}

        <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr className="text-left text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                <th className="px-3 py-2">Document</th>
                <th className="px-3 py-2">Status</th>
                <th className="px-3 py-2">Submitted</th>
                <th className="px-3 py-2">Reviewed</th>
                <th className="px-3 py-2"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {p.tasks.map((t) => (
                <TaskRow key={t.taskId} t={t} onOpen={() => router.push(`/careers/erm/document-review/${p.internLifecycleId}?focus=${t.taskId}`)} />
              ))}
            </tbody>
          </table>
        </div>

        <div className="mt-4 flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => setAddOpen(true)}
            disabled={p.status === 'CANCELLED'}
            className="inline-flex items-center gap-1 rounded-md border border-brand-300 bg-white px-3 py-1.5 text-xs font-medium text-brand-800 hover:bg-brand-50 disabled:opacity-50"
          >
            <Plus className="h-3 w-3" /> Assign additional document
          </button>
          <button
            type="button"
            onClick={() => setWaiveOpen(true)}
            disabled={p.status === 'COMPLETED' || p.status === 'CANCELLED'}
            className="rounded-md border border-amber-300 bg-white px-3 py-1.5 text-xs font-medium text-amber-800 hover:bg-amber-50 disabled:opacity-50"
          >
            Waive all pending (SUPER_ADMIN)
          </button>
          <button
            type="button"
            onClick={() => setCancelOpen(true)}
            disabled={p.status === 'COMPLETED' || p.status === 'CANCELLED'}
            className="rounded-md border border-red-300 bg-white px-3 py-1.5 text-xs font-medium text-red-800 hover:bg-red-50 disabled:opacity-50"
          >
            Cancel packet (SUPER_ADMIN)
          </button>
        </div>

        <ReasonDialog
          open={cancelOpen}
          onClose={() => setCancelOpen(false)}
          onConfirm={runCancel}
          title="Cancel this document packet?"
          description="The packet's pending tasks will be closed and the intern will be notified. This can't be undone."
          reasonLabel="Reason for cancelling"
          placeholder="Why is this packet being cancelled? (audit trail)"
          confirmLabel="Cancel packet"
          variant="danger"
        />
        <ReasonDialog
          open={waiveOpen}
          onClose={() => setWaiveOpen(false)}
          onConfirm={runWaivePending}
          title="Waive all pending tasks?"
          description="Every task still in-flight will be marked WAIVED. Only use for exceptional cases with a documented reason."
          reasonLabel="Reason to waive"
          placeholder="Why are these tasks being waived? (audit trail)"
          confirmLabel="Waive all pending"
          variant="danger"
        />

        <AssignAdditionalDocumentModal
          open={addOpen}
          packetId={p.packetId}
          internName={p.internName}
          existingTasks={p.tasks}
          onClose={() => setAddOpen(false)}
          onAssigned={() => {
            setAddOpen(false);
            void load();
          }}
        />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function TaskRow({ t, onOpen }: { t: TaskSummary; onOpen: () => void }) {
  return (
    <tr>
      <td className="px-3 py-2">
        <p className="text-sm font-medium text-slate-900">{t.templateTitle}</p>
        <p className="text-[10px] text-slate-500">attempt #{t.version ?? 1}</p>
      </td>
      <td className="px-3 py-2"><TaskBadge status={t.status} /></td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {t.submittedAt ? new Date(t.submittedAt).toLocaleString() : '—'}
        {t.uploadedFileName && (
          <span className="block text-[10px] text-slate-500">{t.uploadedFileName}</span>
        )}
      </td>
      <td className="px-3 py-2 text-xs text-slate-700">
        {t.reviewedAt ? new Date(t.reviewedAt).toLocaleString() : '—'}
        {t.reviewComments && (
          <span className="block text-[10px] text-slate-500">{t.reviewComments}</span>
        )}
      </td>
      <td className="px-3 py-2">
        {(t.status === 'SUBMITTED' || t.status === 'UNDER_REVIEW') && (
          <button
            type="button"
            onClick={onOpen}
            className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-2 py-1 text-[11px] font-semibold text-white hover:bg-brand-800"
          >
            Review <ExternalLink className="h-3 w-3" />
          </button>
        )}
      </td>
    </tr>
  );
}

function PacketBadge({ status }: { status: PacketStatus }) {
  const styles: Record<PacketStatus, string> = {
    DRAFT: 'bg-slate-100 text-slate-700',
    ASSIGNED: 'bg-slate-100 text-slate-700',
    IN_PROGRESS: 'bg-amber-100 text-amber-800',
    ALL_SUBMITTED: 'bg-amber-100 text-amber-800',
    COMPLETED: 'bg-green-100 text-green-800',
    CANCELLED: 'bg-red-100 text-red-800',
  };
  return (
    <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${styles[status]}`}>
      {status.replace('_', ' ')}
    </span>
  );
}

function TaskBadge({ status }: { status: TaskStatus }) {
  const styles: Record<TaskStatus, string> = {
    PENDING: 'bg-slate-100 text-slate-700',
    SUBMITTED: 'bg-slate-100 text-slate-700',
    UNDER_REVIEW: 'bg-amber-100 text-amber-800',
    ACCEPTED: 'bg-green-100 text-green-800',
    REJECTED: 'bg-red-100 text-red-800',
    RESEND_REQUESTED: 'bg-amber-100 text-amber-800',
    WAIVED: 'bg-slate-200 text-slate-700',
  };
  return (
    <span className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${styles[status]}`}>
      {status.replace('_', ' ')}
    </span>
  );
}
