'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { Briefcase, ChevronRight, Clock } from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import Modal from '@/components/ui/Modal';
import { toast } from '@/components/ui/Toast';
import { useInternDashboard } from '@/components/intern/InternDashboardContext';
import type { ApplicationResponse, ApplicationStatus } from '@/types';

const STAGE_STYLE: Record<string, string> = {
  APPLIED:              'bg-slate-100 text-slate-700',
  SCREENING_SENT:       'bg-slate-100 text-slate-700',
  SCREENING_COMPLETED:  'bg-slate-100 text-slate-700',
  SHORTLISTED:          'bg-amber-100 text-amber-800',
  INTERVIEW_SCHEDULED:  'bg-amber-100 text-amber-800',
  INTERVIEWED:          'bg-slate-100 text-slate-700',
  SELECTED_CONDITIONAL: 'bg-green-100 text-green-800',
  OFFERED:              'bg-green-100 text-green-800',
  ACCEPTED:             'bg-green-100 text-green-800',
  ONBOARDING:           'bg-brand-100 text-brand-800',
  ACTIVE:               'bg-brand-100 text-brand-800',
  HIRED:                'bg-green-100 text-green-800',
  COMPLETED:            'bg-slate-100 text-slate-700',
  REJECTED:             'bg-red-100 text-red-800',
  WITHDRAWN:            'bg-slate-100 text-slate-600',
  LAPSED:               'bg-slate-100 text-slate-600',
  NO_SHOW:              'bg-slate-100 text-slate-600',
};

const STAGE_LABEL: Record<string, string> = {
  APPLIED: 'Submitted',
  SHORTLISTED: 'Shortlisted',
  INTERVIEW_SCHEDULED: 'Interview Scheduled',
  INTERVIEWED: 'Interview Completed',
  OFFERED: 'Offered',
  HIRED: 'Hired',
  REJECTED: 'Rejected',
  WITHDRAWN: 'Withdrawn',
};

function humanizeStage(s: ApplicationStatus): string {
  return STAGE_LABEL[s] ?? s.replaceAll('_', ' ').toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

export default function InternApplicationsPage() {
  const [items, setItems] = useState<ApplicationResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [pendingWithdraw, setPendingWithdraw] = useState<ApplicationResponse | null>(null);
  const [withdrawing, setWithdrawing] = useState(false);
  const { refresh } = useInternDashboard();

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const res = await api.get<ApplicationResponse[]>('/api/v1/applications/mine');
      const sorted = [...(res.data ?? [])].sort((a, b) =>
        (b.appliedAt ?? '').localeCompare(a.appliedAt ?? ''));
      setItems(sorted);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load your applications');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  async function confirmWithdraw() {
    if (!pendingWithdraw) return;
    setWithdrawing(true);
    try {
      await api.patch(`/api/v1/applications/${pendingWithdraw.id}/withdraw`);
      setPendingWithdraw(null);
      toast.success('Application withdrawn.');
      await load();
      await refresh();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Could not withdraw');
    } finally {
      setWithdrawing(false);
    }
  }

  return (
    <InternPageShell title="My Applications" subtitle="Track every role you've applied to.">
      {loading && (
        <ul className="space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <li key={i} className="h-20 animate-pulse rounded-lg bg-slate-50" aria-hidden />
          ))}
        </ul>
      )}
      {err && (
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">{err}</p>
      )}
      {!loading && !err && items.length === 0 && (
        <p className="rounded-md border border-dashed border-slate-300 bg-white p-10 text-center text-sm text-slate-500">
          No applications yet. Browse <Link href="/careers/intern/jobs" className="text-brand-700 hover:underline">job postings</Link> to get started.
        </p>
      )}
      {!loading && items.length > 0 && (
        <ul className="space-y-3">
          {items.map((a) => (
            <li
              key={a.id}
              className="flex items-center gap-4 rounded-lg border border-slate-200 bg-white p-4 shadow-sm transition-shadow hover:shadow-md"
            >
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-brand-50 text-brand-700">
                <Briefcase className="h-5 w-5" strokeWidth={2} />
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <h3 className="truncate text-sm font-semibold text-slate-900">
                    {a.jobPostingTitle ?? 'Job posting'}
                  </h3>
                  <span className={'inline-flex rounded-full px-2 py-0.5 text-xs font-medium ' + (STAGE_STYLE[a.status] ?? 'bg-slate-100 text-slate-700')}>
                    {humanizeStage(a.status)}
                  </span>
                </div>
                <div className="mt-1 flex items-center gap-1 text-xs text-slate-500">
                  <Clock className="h-3 w-3" strokeWidth={2} />
                  Applied {new Date(a.appliedAt).toLocaleDateString()}
                </div>
              </div>
              <div className="flex items-center gap-2">
                {(a.status === 'APPLIED' || a.status === 'SHORTLISTED') && (
                  <button
                    type="button"
                    onClick={() => setPendingWithdraw(a)}
                    className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50"
                  >
                    Withdraw
                  </button>
                )}
                <Link
                  href={`/careers/intern/applications/${a.id}`}
                  className="inline-flex items-center gap-1 rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
                >
                  View details
                  <ChevronRight className="h-3 w-3" strokeWidth={2.5} />
                </Link>
              </div>
            </li>
          ))}
        </ul>
      )}

      <Modal
        open={pendingWithdraw != null}
        onOpenChange={(o) => !withdrawing && !o && setPendingWithdraw(null)}
        title="Withdraw this application?"
        size="sm"
        footer={
          <>
            <button
              type="button"
              onClick={() => setPendingWithdraw(null)}
              disabled={withdrawing}
              className="rounded-md border border-slate-200 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={confirmWithdraw}
              disabled={withdrawing}
              className="rounded-md bg-red-600 px-4 py-1.5 text-xs font-semibold text-white hover:bg-red-700 disabled:opacity-60"
            >
              {withdrawing ? 'Withdrawing…' : 'Yes, withdraw'}
            </button>
          </>
        }
      >
        <p className="text-sm text-slate-700">
          You&apos;re about to withdraw your application for{' '}
          <strong>{pendingWithdraw?.jobPostingTitle ?? 'this role'}</strong>.
          This cannot be undone.
        </p>
      </Modal>
    </InternPageShell>
  );
}
