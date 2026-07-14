'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  BadgeCheck,
  Briefcase,
  GraduationCap,
  Users as UsersIcon,
} from 'lucide-react';
import api from '@/lib/api';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import type { LucideIcon } from 'lucide-react';

interface AdminOverviewResponse {
  totalCandidates: number;
  totalHired: number;
  activeInterns: number;
  openPostings: number;
  applicationsByStatus: Record<string, number> | null;
  compliance: {
    i9Pending: number;
    i983Pending: number;
    everifyPending: number;
  } | null;
}

const STATUS_LABEL: Record<string, string> = {
  APPLIED: 'Applied',
  SHORTLISTED: 'Shortlisted',
  INTERVIEW_SCHEDULED: 'Interview scheduled',
  INTERVIEWED: 'Interviewed',
  OFFERED: 'Offer extended',
  ACCEPTED: 'Offer accepted',
  ONBOARDING: 'Onboarding',
  ACTIVE: 'Active',
  HIRED: 'Hired',
  COMPLETED: 'Completed',
  REJECTED: 'Rejected',
  WITHDRAWN: 'Withdrawn',
  LAPSED: 'Lapsed',
  NO_SHOW: 'No show',
};

// Visual order — match the candidate's funnel; unknown keys (future enum
// values) trail at the end so the dashboard never silently drops them.
const STATUS_ORDER = [
  'APPLIED',
  'SHORTLISTED',
  'INTERVIEW_SCHEDULED',
  'INTERVIEWED',
  'OFFERED',
  'ACCEPTED',
  'ONBOARDING',
  'ACTIVE',
  'HIRED',
  'COMPLETED',
  'REJECTED',
  'WITHDRAWN',
  'LAPSED',
  'NO_SHOW',
];

export default function AdminOverviewPage() {
  return (
    <ProtectedRoute requiredRoles={['SUPER_ADMIN', 'MANAGER']}>
      <DashboardLayout title="Admin Overview">
        <Overview />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Overview() {
  const [data, setData] = useState<AdminOverviewResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const res = await api.get<AdminOverviewResponse>('/api/v1/admin/overview');
      setData(res.data ?? null);
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't load the overview.");
      setData(null);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const orderedStatuses = useMemo(() => {
    const keys = data?.applicationsByStatus ? Object.keys(data.applicationsByStatus) : [];
    const known = STATUS_ORDER.filter((k) => keys.includes(k));
    const extras = keys.filter((k) => !STATUS_ORDER.includes(k));
    return [...known, ...extras];
  }, [data]);

  const maxStatusCount = useMemo(() => {
    if (!data?.applicationsByStatus) return 0;
    return Object.values(data.applicationsByStatus).reduce(
      (acc, n) => Math.max(acc, Number(n) || 0),
      0,
    );
  }, [data]);

  if (error) {
    return (
      <div className="rounded border border-red-200 bg-red-50 p-4 text-sm text-red-700">
        <p className="mb-2">{error}</p>
        <button
          type="button"
          onClick={() => void load()}
          className="rounded border border-red-300 px-3 py-1 font-medium hover:bg-red-100"
        >
          Retry
        </button>
      </div>
    );
  }

  if (data === null) {
    return <div className="py-10 text-center text-sm text-gray-500">Loading…</div>;
  }

  return (
    <section className="space-y-8">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <StatCard label="Candidates" value={data.totalCandidates ?? 0} icon={UsersIcon} />
        <StatCard label="Hires" value={data.totalHired ?? 0} icon={BadgeCheck} />
        <StatCard
          label="Active interns"
          value={data.activeInterns ?? 0}
          icon={GraduationCap}
        />
        <StatCard label="Open postings" value={data.openPostings ?? 0} icon={Briefcase} />
      </div>

      <div className="rounded-lg border border-gray-200 bg-white p-6">
        <h2 className="mb-4 text-lg font-semibold text-gray-900">Applications by status</h2>
        {orderedStatuses.length === 0 ? (
          <p className="text-sm text-gray-500">No applications yet.</p>
        ) : (
          <ul className="space-y-2">
            {orderedStatuses.map((key) => {
              const count = data.applicationsByStatus?.[key] ?? 0;
              const pct = maxStatusCount > 0 ? (count / maxStatusCount) * 100 : 0;
              return (
                <li key={key} className="grid grid-cols-12 items-center gap-3 text-sm">
                  <div className="col-span-4 truncate text-gray-700">
                    {STATUS_LABEL[key] ?? key}
                  </div>
                  <div className="col-span-7 h-2 overflow-hidden rounded-full bg-gray-100">
                    <div
                      className="h-2 rounded-full bg-accent"
                      style={{ width: `${pct}%` }}
                    />
                  </div>
                  <div className="col-span-1 text-right font-medium text-gray-900">
                    {count}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>

      <div className="rounded-lg border border-gray-200 bg-white p-6">
        <h2 className="mb-4 text-lg font-semibold text-gray-900">Compliance health</h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <ComplianceCard label="I-9 pending" value={data.compliance?.i9Pending ?? 0} />
          <ComplianceCard label="I-983 pending" value={data.compliance?.i983Pending ?? 0} />
          <ComplianceCard
            label="E-Verify pending"
            value={data.compliance?.everifyPending ?? 0}
          />
        </div>
      </div>
    </section>
  );
}

function StatCard({
  label,
  value,
  icon: Icon,
}: {
  label: string;
  value: number;
  icon: LucideIcon;
}) {
  return (
    <div className="rounded-lg border border-gray-200 bg-white p-4">
      <div className="mb-1 flex items-center justify-between">
        <span className="text-xs font-medium uppercase tracking-wide text-gray-500">
          {label}
        </span>
        <Icon className="h-4 w-4 text-gray-400" strokeWidth={1.75} />
      </div>
      <div className="text-2xl font-semibold text-gray-900">{value}</div>
    </div>
  );
}

function ComplianceCard({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex items-center justify-between rounded-md border border-gray-200 bg-gray-50 px-4 py-3">
      <span className="text-sm text-gray-700">{label}</span>
      <span className="text-lg font-semibold text-gray-900">{value}</span>
    </div>
  );
}
