'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import { ChevronLeft } from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import InfoRequestedBanner from '@/components/intern/applications/InfoRequestedBanner';
import type { ApplicationResponse } from '@/types';

const STAGE_STYLE: Record<string, string> = {
  APPLIED: 'bg-slate-100 text-slate-700',
  SHORTLISTED: 'bg-amber-100 text-amber-800',
  INTERVIEW_SCHEDULED: 'bg-amber-100 text-amber-800',
  INTERVIEWED: 'bg-slate-100 text-slate-700',
  OFFERED: 'bg-green-100 text-green-800',
  HIRED: 'bg-green-100 text-green-800',
  REJECTED: 'bg-red-100 text-red-800',
  WITHDRAWN: 'bg-slate-100 text-slate-600',
};

export default function InternApplicationDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id;
  const [app, setApp] = useState<ApplicationResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const res = await api.get<ApplicationResponse>(`/api/v1/applications/${id}`);
      setApp(res.data);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Could not load this application');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { void load(); }, [load]);

  if (loading) {
    return (
      <InternPageShell title="Application">
        <div className="h-48 animate-pulse rounded-lg bg-slate-50" aria-hidden />
      </InternPageShell>
    );
  }
  if (err || !app) {
    return (
      <InternPageShell title="Application">
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {err ?? 'Application not found'}
        </p>
      </InternPageShell>
    );
  }

  const showFeedback = app.applicantVisibleFeedback
    && (app.status === 'INTERVIEWED' || app.status === 'REJECTED');

  return (
    <InternPageShell
      title={app.jobPostingTitle ?? 'Application'}
      subtitle={
        <span className={'inline-flex rounded-full px-2 py-0.5 text-xs font-medium ' + (STAGE_STYLE[app.status] ?? 'bg-slate-100 text-slate-700')}>
          {app.status.replaceAll('_', ' ')}
        </span>
      }
    >
      <Link
        href="/careers/intern/applications"
        className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700"
      >
        <ChevronLeft className="h-4 w-4" strokeWidth={2} /> All applications
      </Link>

      {app.status === 'INFO_REQUESTED' && (
        <InfoRequestedBanner
          applicationId={app.id}
          infoRequestedFieldsCsv={app.infoRequestedFieldsCsv ?? null}
          onProvided={() => void load()}
        />
      )}

      {showFeedback && (
        <section className="mb-6 rounded-lg border border-green-200 bg-green-50 p-5">
          <h2 className="text-sm font-semibold text-green-900">From the team</h2>
          <p className="mt-2 whitespace-pre-wrap text-sm text-green-900">
            {app.applicantVisibleFeedback}
          </p>
        </section>
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-900">Job</h2>
          <dl className="mt-3 space-y-2 text-sm">
            <DetailRow label="Title" value={app.jobPostingTitle} />
            <DetailRow label="Job ID" value={app.jobPostingId} />
          </dl>
        </section>

        <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-900">Your application</h2>
          <dl className="mt-3 space-y-2 text-sm">
            <DetailRow label="Submitted" value={new Date(app.appliedAt).toLocaleString()} />
            {app.statusUpdatedAt && (
              <DetailRow label="Last update" value={new Date(app.statusUpdatedAt).toLocaleString()} />
            )}
            {app.resumeFileName && (
              <DetailRow label="Resume" value={app.resumeFileName} />
            )}
          </dl>
          {app.statementOfInterest && (
            <>
              <h3 className="mt-4 text-xs font-semibold uppercase tracking-wide text-slate-500">
                Your statement
              </h3>
              <p className="mt-2 whitespace-pre-wrap text-sm text-slate-700">
                {app.statementOfInterest}
              </p>
            </>
          )}
        </section>
      </div>
    </InternPageShell>
  );
}

function DetailRow({ label, value }: { label: string; value: React.ReactNode }) {
  if (!value) return null;
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="text-xs uppercase tracking-wide text-slate-400">{label}</dt>
      <dd className="text-right text-sm text-slate-700">{value}</dd>
    </div>
  );
}
