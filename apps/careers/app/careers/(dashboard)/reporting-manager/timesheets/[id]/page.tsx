'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { CheckCircle2, RotateCcw } from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ProtectedRoute from '@/components/ProtectedRoute';
import { formatRelative } from '@/lib/format-date';
import type { DayOfWeek, TimesheetStatus, TimesheetWeek } from '@/types';

const DAYS: DayOfWeek[] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
];

const DAY_LABEL: Record<DayOfWeek, string> = {
  MONDAY: 'Mon',
  TUESDAY: 'Tue',
  WEDNESDAY: 'Wed',
  THURSDAY: 'Thu',
  FRIDAY: 'Fri',
  SATURDAY: 'Sat',
  SUNDAY: 'Sun',
};

const REASON_MIN = 10;
const REASON_MAX = 2000;

export default function RmTimesheetDetailPage() {
  return (
    <ProtectedRoute requiredRoles={['REPORTING_MANAGER', 'SUPER_ADMIN']}>
      <DashboardLayout title="Timesheet review">
        <Body />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  const id = params?.id;

  const [week, setWeek] = useState<TimesheetWeek | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [busy, setBusy] = useState<'approve' | 'return' | null>(null);
  const [confirmApprove, setConfirmApprove] = useState(false);
  const [returnOpen, setReturnOpen] = useState(false);
  const [reason, setReason] = useState('');

  const load = useCallback(async () => {
    if (!id) return;
    setError(null);
    try {
      const res = await api.get<TimesheetWeek>(`/api/v1/timesheets/${id}`);
      setWeek(res.data);
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't load the timesheet.");
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  async function approve() {
    if (!id || busy !== null) return;
    setBusy('approve');
    try {
      await api.post(`/api/v1/timesheets/${id}/approve`);
      toast.success('Approved.');
      router.push('/careers/reporting-manager/timesheets');
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? "Couldn't approve.");
      setBusy(null);
    }
  }

  async function returnForCorrection() {
    if (!id || busy !== null) return;
    const trimmed = reason.trim();
    if (trimmed.length < REASON_MIN || trimmed.length > REASON_MAX) return;
    setBusy('return');
    try {
      await api.post(`/api/v1/timesheets/${id}/return`, { reason: trimmed });
      toast.success('Returned for correction.');
      router.push('/careers/reporting-manager/timesheets');
    } catch (err: any) {
      toast.error(err?.response?.data?.error ?? "Couldn't return.");
      setBusy(null);
    }
  }

  if (loading) return <Skeleton />;
  if (error)
    return (
      <div className="rounded border border-red-200 bg-red-50 p-4 text-sm text-red-700">
        {error}
      </div>
    );
  if (!week) return null;

  const dayMap = new Map(week.days.map((d) => [d.dayOfWeek, d]));
  const total = week.days.reduce((sum, d) => sum + (Number(d.hours) || 0), 0);
  const pending = week.status === 'SUBMITTED';

  return (
    <section className="mx-auto max-w-3xl space-y-4">
      <header>
        <button
          type="button"
          onClick={() => router.back()}
          className="mb-1 text-xs text-gray-500 hover:text-gray-700"
        >
          ← Back
        </button>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h1 className="truncate text-xl font-semibold text-gray-900">
              {week.internName ?? '—'}
            </h1>
            <p className="mt-0.5 text-xs text-gray-500">
              Week of {week.weekStart}
              {week.createdAt && (
                <> · submitted {formatRelative(week.createdAt)}</>
              )}
            </p>
          </div>
          <StatusBadge status={week.status} />
        </div>
      </header>

      {/* Day grid (read-only) */}
      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-[11px] uppercase tracking-wide text-gray-500">
            <tr>
              <th className="px-3 py-2 text-left font-semibold w-28">Day</th>
              <th className="px-3 py-2 text-left font-semibold w-24">Hours</th>
              <th className="px-3 py-2 text-left font-semibold">Notes</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {DAYS.map((d) => {
              const cell = dayMap.get(d);
              const hours = Number(cell?.hours) || 0;
              return (
                <tr key={d}>
                  <td className="px-3 py-2 text-gray-700">{DAY_LABEL[d]}</td>
                  <td className="px-3 py-2 text-gray-900">
                    {hours.toFixed(1)}
                  </td>
                  <td className="px-3 py-2 text-gray-700">
                    {cell?.notes ?? <span className="text-gray-400">—</span>}
                  </td>
                </tr>
              );
            })}
          </tbody>
          <tfoot>
            <tr className="bg-gray-50">
              <td className="px-3 py-2 text-xs font-semibold uppercase tracking-wide text-gray-700">
                Total
              </td>
              <td className="px-3 py-2 text-sm font-semibold text-gray-900">
                {total.toFixed(1)} hrs
              </td>
              <td />
            </tr>
          </tfoot>
        </table>
      </div>

      {/* Returned reason / approved-by banner */}
      {week.status === 'APPROVED' && (
        <div className="rounded-md border border-green-200 bg-green-50 p-3 text-sm text-green-900">
          Approved
          {week.approvedByName && <> by {week.approvedByName}</>}
          {week.approvedAt && <> · {formatRelative(week.approvedAt)}</>}.
        </div>
      )}
      {week.status === 'REJECTED' && week.reviewNote && (
        <div className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
          <span className="font-medium">Return reason: </span>
          <span className="whitespace-pre-wrap">{week.reviewNote}</span>
        </div>
      )}

      {/* Actions */}
      {pending && (
        <div className="flex flex-wrap justify-end gap-2">
          <button
            type="button"
            onClick={() => setReturnOpen(true)}
            disabled={busy !== null}
            className="inline-flex items-center gap-1.5 rounded-md border border-amber-300 bg-white px-3 py-2 text-sm font-medium text-amber-800 hover:bg-amber-50 disabled:opacity-60"
          >
            <RotateCcw className="h-3.5 w-3.5" strokeWidth={2} />
            Return for correction
          </button>
          <button
            type="button"
            onClick={() => setConfirmApprove(true)}
            disabled={busy !== null}
            className="inline-flex items-center gap-1.5 rounded-md bg-green-600 px-3 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-60"
          >
            <CheckCircle2 className="h-3.5 w-3.5" strokeWidth={2} />
            Approve
          </button>
        </div>
      )}

      {/* Approve modal */}
      {confirmApprove && (
        <Modal title="Approve this timesheet?">
          <p className="text-sm text-gray-600">
            Locks the week for editing and counts the hours toward the intern&apos;s
            approved total.
          </p>
          <div className="mt-5 flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setConfirmApprove(false)}
              disabled={busy !== null}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => {
                setConfirmApprove(false);
                void approve();
              }}
              disabled={busy !== null}
              className="inline-flex items-center gap-1.5 rounded-md bg-green-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-green-700 disabled:opacity-60"
            >
              <CheckCircle2 className="h-3.5 w-3.5" strokeWidth={2} />
              {busy === 'approve' ? 'Approving…' : 'Approve'}
            </button>
          </div>
        </Modal>
      )}

      {/* Return modal */}
      {returnOpen && (
        <Modal title="Return for correction">
          <p className="text-xs text-gray-500">
            The intern sees this reason on their timesheet page.
          </p>
          <textarea
            rows={4}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            maxLength={REASON_MAX}
            placeholder="What needs to change?"
            className="mt-3 w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
          <p className="mt-1 text-[11px] text-gray-500">
            {reason.trim().length} / {REASON_MAX} (min {REASON_MIN})
          </p>
          <div className="mt-4 flex justify-end gap-2">
            <button
              type="button"
              onClick={() => {
                setReturnOpen(false);
                setReason('');
              }}
              disabled={busy !== null}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => void returnForCorrection()}
              disabled={
                busy !== null
                || reason.trim().length < REASON_MIN
                || reason.trim().length > REASON_MAX
              }
              className="inline-flex items-center gap-1.5 rounded-md border border-amber-300 bg-white px-3 py-1.5 text-sm font-medium text-amber-800 hover:bg-amber-50 disabled:opacity-60"
            >
              <RotateCcw className="h-3.5 w-3.5" strokeWidth={2} />
              {busy === 'return' ? 'Returning…' : 'Return'}
            </button>
          </div>
        </Modal>
      )}
    </section>
  );
}

function StatusBadge({ status }: { status: TimesheetStatus }) {
  const palette: Record<TimesheetStatus, string> = {
    DRAFT: 'bg-gray-100 text-gray-700',
    SUBMITTED: 'bg-amber-100 text-amber-800',
    APPROVED: 'bg-green-100 text-green-800',
    REJECTED: 'bg-amber-100 text-amber-800',
  };
  const label: Record<TimesheetStatus, string> = {
    DRAFT: 'Draft',
    SUBMITTED: 'Submitted',
    APPROVED: 'Approved',
    REJECTED: 'Returned',
  };
  return (
    <span
      className={
        'rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ' +
        palette[status]
      }
    >
      {label[status]}
    </span>
  );
}

function Modal({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
        <h2 className="mb-2 text-lg font-semibold text-gray-900">{title}</h2>
        {children}
      </div>
    </div>
  );
}

function Skeleton() {
  return (
    <div className="space-y-3">
      <div className="h-8 w-1/3 animate-pulse rounded bg-gray-100" />
      <div className="h-64 animate-pulse rounded-lg bg-gray-100" />
    </div>
  );
}
