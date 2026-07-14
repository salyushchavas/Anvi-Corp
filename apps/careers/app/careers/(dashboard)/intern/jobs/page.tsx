'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Search } from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import { useInternDashboard } from '@/components/intern/InternDashboardContext';
import JobPostingsTable from '@/components/jobs/JobPostingsTable';
import type {
  EmploymentType,
  JobPostingResponse,
  Page,
} from '@/types';

type JobTab = 'INTERNSHIP' | 'FULL_TIME';

export default function InternJobsPage() {
  const { data: dashboard, refresh } = useInternDashboard();
  const [tab, setTab] = useState<JobTab>('INTERNSHIP');
  const [search, setSearch] = useState('');
  const [postings, setPostings] = useState<JobPostingResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.get<Page<JobPostingResponse>>(
        '/api/v1/jobs?page=0&size=50',
      );
      setPostings(res.data.content ?? []);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load job postings');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return postings
      .filter((p) => (p.employmentType as EmploymentType) === tab)
      .filter((p) => {
        if (!q) return true;
        return (
          p.title.toLowerCase().includes(q)
          || (p.description ?? '').toLowerCase().includes(q)
          || (p.location ?? '').toLowerCase().includes(q)
          || (p.jobId ?? '').toLowerCase().includes(q)
        );
      });
  }, [postings, tab, search]);

  const emailVerified = dashboard?.emailVerified ?? false;
  const lifecycle = dashboard?.lifecycleStatus;
  const canApply = emailVerified
    && (lifecycle === 'EMAIL_VERIFIED' || lifecycle === 'APPLICATION_SUBMITTED');

  function handleApplied(jobPostingId: string) {
    // Optimistic local flip; then re-fetch + refresh dashboard for the
    // canonical state (application id, status, count).
    setPostings((prev) =>
      prev.map((row) =>
        row.id === jobPostingId
          ? { ...row, applied: true, applicationStatus: 'APPLIED' }
          : row,
      ),
    );
    void load();
    void refresh();
  }

  return (
    <InternPageShell title="Job Postings" subtitle="Browse open roles and apply.">
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div role="tablist" aria-label="Job type" className="inline-flex rounded-lg border border-slate-200 bg-white p-1">
          <TabButton active={tab === 'INTERNSHIP'} onClick={() => setTab('INTERNSHIP')}>
            Internships
          </TabButton>
          <TabButton active={tab === 'FULL_TIME'} onClick={() => setTab('FULL_TIME')}>
            Full-time
          </TabButton>
        </div>
        <label className="relative flex items-center">
          <Search className="pointer-events-none absolute left-2.5 h-4 w-4 text-slate-400" strokeWidth={2} />
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search title, location, Job ID…"
            className="w-full rounded-md border border-slate-200 bg-white py-2 pl-8 pr-3 text-sm placeholder:text-slate-400 focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500 sm:w-64"
          />
        </label>
      </div>

      {!emailVerified && (
        <div className="mb-4 rounded-md border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
          Verify your email to apply for any role. Open Home for a one-tap resend.
        </div>
      )}
      {emailVerified && !canApply && (
        <div className="mb-4 rounded-md border border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-700">
          Browsing only — your journey has moved past the application phase.
        </div>
      )}

      {loading && (
        <div className="h-48 animate-pulse rounded-lg border border-slate-200 bg-slate-50" />
      )}
      {error && (
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {error}
        </p>
      )}
      {!loading && !error && (
        <JobPostingsTable
          postings={filtered}
          onApplied={handleApplied}
          emptyLabel={`No ${tab === 'INTERNSHIP' ? 'internship' : 'full-time'} postings open right now.`}
        />
      )}
    </InternPageShell>
  );
}

function TabButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      onClick={onClick}
      className={
        'rounded-md px-4 py-1.5 text-sm font-medium transition-colors '
        + (active
          ? 'bg-brand-700 text-white shadow-sm'
          : 'text-slate-600 hover:bg-slate-100')
      }
    >
      {children}
    </button>
  );
}
