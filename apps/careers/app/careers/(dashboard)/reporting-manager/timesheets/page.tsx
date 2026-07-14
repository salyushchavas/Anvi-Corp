'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { ClipboardList } from 'lucide-react';
import api from '@/lib/api';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ProtectedRoute from '@/components/ProtectedRoute';
import { formatRelative } from '@/lib/format-date';
import type { TimesheetWeek } from '@/types';

export default function RmTimesheetQueuePage() {
  return (
    <ProtectedRoute requiredRoles={['REPORTING_MANAGER', 'SUPER_ADMIN']}>
      <DashboardLayout title="Pending timesheets">
        <Body />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body() {
  const [rows, setRows] = useState<TimesheetWeek[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const res = await api.get<TimesheetWeek[]>(
        '/api/v1/timesheets/pending-approval',
      );
      setRows(res.data ?? []);
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't load the queue.");
      setRows([]);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <section className="space-y-4">
      <header>
        <h1 className="text-2xl font-semibold text-gray-900">
          Pending timesheets
        </h1>
        <p className="mt-1 text-sm text-gray-600">
          Submitted weeks from interns on engagements you manage. Approve or
          return each one.
        </p>
      </header>

      {error && (
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {rows === null ? (
        <Skeleton />
      ) : rows.length === 0 ? (
        <div className="rounded-lg border border-dashed border-gray-300 bg-white p-8 text-center text-sm text-gray-600">
          No timesheets awaiting your review.
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-[11px] uppercase tracking-wide text-gray-500">
              <tr>
                <th className="px-3 py-2 text-left font-semibold">Intern</th>
                <th className="px-3 py-2 text-left font-semibold">Week start</th>
                <th className="px-3 py-2 text-left font-semibold">Total</th>
                <th className="px-3 py-2 text-left font-semibold">Submitted</th>
                <th className="px-3 py-2 text-right font-semibold"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {rows.map((t) => (
                <tr key={t.id} className="hover:bg-gray-50">
                  <td className="px-3 py-2 text-gray-800">
                    {t.internName ?? '—'}
                  </td>
                  <td className="px-3 py-2 text-gray-700">{t.weekStart}</td>
                  <td className="px-3 py-2 text-gray-900 font-medium">
                    {Number(t.totalHours).toFixed(1)} hrs
                  </td>
                  <td className="px-3 py-2 text-gray-500">
                    {t.createdAt ? formatRelative(t.createdAt) : '—'}
                  </td>
                  <td className="px-3 py-2 text-right">
                    <Link
                      href={`/careers/reporting-manager/timesheets/${t.id}`}
                      className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 py-1 text-xs font-medium text-gray-700 hover:bg-gray-50"
                    >
                      <ClipboardList className="h-3 w-3" strokeWidth={2} />
                      Review
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function Skeleton() {
  return (
    <div className="space-y-2">
      <div className="h-12 animate-pulse rounded bg-gray-100" />
      <div className="h-12 animate-pulse rounded bg-gray-100" />
      <div className="h-12 animate-pulse rounded bg-gray-100" />
    </div>
  );
}
