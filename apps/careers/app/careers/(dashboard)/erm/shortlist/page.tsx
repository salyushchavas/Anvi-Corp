'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import PageHeader from '@/components/ui/PageHeader';
import StagePill from '@/components/erm/applications/StagePill';
import type {
  ApplicationListPage,
  ApplicationRow,
} from '@/components/erm/applications/types';

export default function ShortlistQueuePage() {
  const [data, setData] = useState<ApplicationListPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<ApplicationListPage>(
        '/api/v1/erm/applications?stage=SHORTLISTED&scope=mine&pageSize=50',
      );
      setData(res.data);
      setErr(null);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load shortlist');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  return (
    <ProtectedRoute requiredRoles={['ERM', 'SUPER_ADMIN']}>
      <DashboardLayout>
        <PageHeader
          title="Shortlist Queue"
          subtitle="Applicants moved to shortlisted; awaiting interview scheduling."
        />

        {err && (
          <p className="mb-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
            {err}
          </p>
        )}

        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          {loading && !data ? (
            <div className="h-32 animate-pulse" />
          ) : !data || data.items.length === 0 ? (
            <p className="p-10 text-center text-sm text-slate-500">
              No shortlisted applicants in your scope.
            </p>
          ) : (
            <ul className="divide-y divide-slate-100">
              {data.items.map((r) => (
                <ShortlistRow key={r.applicationId} row={r} />
              ))}
            </ul>
          )}
        </div>
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function ShortlistRow({ row }: { row: ApplicationRow }) {
  return (
    <li className="flex items-center justify-between gap-4 px-4 py-3">
      <div className="min-w-0 flex-1">
        <Link
          href={`/careers/erm/applications/${row.applicationId}`}
          className="flex items-center gap-3 hover:underline"
        >
          <span className="flex h-8 w-8 items-center justify-center rounded-full bg-brand-100 text-[11px] font-semibold text-brand-800">
            {initials(row.applicantName)}
          </span>
          <span>
            <span className="block text-sm font-medium text-slate-900">
              {row.applicantName ?? '(unknown)'}
            </span>
            <span className="block text-[11px] text-slate-500">
              {row.applicantId ?? row.applicantEmail} · {row.jobTitle}
            </span>
          </span>
        </Link>
      </div>
      <StagePill stage={row.stage} />
      <Link
        href={`/careers/erm/interviews/new?applicationId=${row.applicationId}`}
        className="rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
      >
        Schedule interview
      </Link>
    </li>
  );
}

function initials(name: string | null): string {
  if (!name) return '?';
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? '')
    .join('');
}
