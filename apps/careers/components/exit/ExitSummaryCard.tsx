'use client';

import Link from 'next/link';
import { Award, Briefcase, Clock, Star } from 'lucide-react';
import type { InternExitSummary } from '@/components/intern/InternDashboardContext';
import ExitTypePill from './ExitTypePill';

interface Props {
  summary: InternExitSummary;
}

/** Phase 8 — Home + /exit/summary stats block. */
export default function ExitSummaryCard({ summary }: Props) {
  const stats: Array<{ label: string; value: string; icon: React.ReactNode }> = [
    {
      label: 'Duration',
      value: `${summary.durationDays} days`,
      icon: <Clock className="h-4 w-4 text-slate-500" />,
    },
    {
      label: 'Projects completed',
      value: String(summary.projectsCompleted),
      icon: <Briefcase className="h-4 w-4 text-slate-500" />,
    },
    {
      label: 'Evaluations',
      value: String(summary.evaluationsCount),
      icon: <Award className="h-4 w-4 text-slate-500" />,
    },
    {
      label: 'Average score',
      value: summary.averageScore != null ? summary.averageScore.toFixed(1) : '—',
      icon: <Star className="h-4 w-4 text-slate-500" />,
    },
    {
      label: 'Approved hours',
      value: String(summary.totalApprovedHours ?? '0'),
      icon: <Clock className="h-4 w-4 text-slate-500" />,
    },
  ];

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold text-slate-900">Internship summary</h3>
          <ExitTypePill exitType={summary.exitType} />
        </div>
        <span className="text-xs text-slate-500">
          Ended {summary.exitDate}
        </span>
      </div>
      {summary.internVisibleSummary && (
        <p className="mt-3 text-sm text-slate-700">{summary.internVisibleSummary}</p>
      )}
      <dl className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3">
        {stats.map((s) => (
          <div
            key={s.label}
            className="rounded-md border border-slate-100 bg-slate-50 p-3"
          >
            <dt className="flex items-center gap-1.5 text-[11px] uppercase tracking-wide text-slate-500">
              {s.icon}
              {s.label}
            </dt>
            <dd className="mt-1 text-base font-semibold text-slate-900">{s.value}</dd>
          </div>
        ))}
      </dl>
      <div className="mt-4 flex flex-wrap gap-2">
        <Link
          href="/careers/intern/exit/summary"
          className="rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
        >
          Open exit summary
        </Link>
        {!summary.feedbackSubmitted && (
          <Link
            href="/careers/intern/exit/feedback"
            className="rounded-md bg-brand-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-brand-800"
          >
            Submit feedback
          </Link>
        )}
      </div>
    </section>
  );
}
