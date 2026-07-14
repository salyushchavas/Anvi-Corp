'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import ExitSummaryCard from '@/components/exit/ExitSummaryCard';
import type { InternExitSummary } from '@/components/intern/InternDashboardContext';

interface ExitRecordInternView {
  id: string;
  exitType: string;
  exitDate: string;
  exitReason: string | null;
  finalEvaluationId: string | null;
  rehireEligible: boolean | null;
  internVisibleSummary: string | null;
  createdAt: string;
}

export default function ExitSummaryPage() {
  const [summary, setSummary] = useState<InternExitSummary | null>(null);
  const [record, setRecord] = useState<ExitRecordInternView | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [s, r] = await Promise.all([
        api.get<InternExitSummary>('/api/v1/exit/my-summary'),
        api.get<ExitRecordInternView>('/api/v1/exit/my-record'),
      ]);
      setSummary(s.data);
      setRecord(r.data);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load exit summary');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  if (loading) {
    return (
      <InternPageShell title="Internship Summary">
        <div className="h-32 animate-pulse rounded-lg bg-slate-50" aria-hidden />
      </InternPageShell>
    );
  }
  if (err || !summary) {
    return (
      <InternPageShell title="Internship Summary">
        <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {err ?? 'No exit summary available.'}
        </p>
      </InternPageShell>
    );
  }

  return (
    <InternPageShell
      title="Internship Summary"
      subtitle="A record of your engagement and contributions."
    >
      <ExitSummaryCard summary={summary} />

      <section className="mt-6 rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
        <h3 className="text-sm font-semibold text-slate-900">Your records</h3>
        <ul className="mt-3 grid gap-2 text-sm sm:grid-cols-2">
          <li>
            <Link href="/careers/intern/documents" className="text-brand-700 hover:underline">
              Signed forms &amp; documents
            </Link>
          </li>
          <li>
            <Link href="/careers/intern/evaluations" className="text-brand-700 hover:underline">
              Evaluation history
            </Link>
          </li>
          <li>
            <Link href="/careers/intern/projects" className="text-brand-700 hover:underline">
              Project list
            </Link>
          </li>
          <li>
            <Link href="/careers/intern/timesheets" className="text-brand-700 hover:underline">
              Timesheets
            </Link>
          </li>
          {summary.finalEvaluationId && (
            <li>
              <Link
                href={`/careers/intern/evaluations/${summary.finalEvaluationId}`}
                className="text-brand-700 hover:underline"
              >
                Final evaluation
              </Link>
            </li>
          )}
          {summary.feedbackSubmitted ? (
            <li className="text-slate-500">Exit feedback submitted</li>
          ) : (
            <li>
              <Link href="/careers/intern/exit/feedback" className="text-brand-700 hover:underline">
                Share exit feedback
              </Link>
            </li>
          )}
        </ul>
      </section>

      {record?.exitReason && (
        <section className="mt-6 rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
          <h3 className="text-sm font-semibold text-slate-900">Exit notes</h3>
          <p className="mt-2 whitespace-pre-wrap text-sm text-slate-700">
            {record.exitReason}
          </p>
        </section>
      )}
    </InternPageShell>
  );
}
