'use client';

import { useState } from 'react';
import Link from 'next/link';
import { ChevronRight } from 'lucide-react';
import type {
  ExceptionRow,
  ErmExceptionSeverity,
  ErmExceptionType,
} from './ErmDashboardContext';

interface Props {
  counts: Record<ErmExceptionType, number> | null;
  topUrgent: ExceptionRow[];
}

const TYPE_LABEL: Record<ErmExceptionType, string> = {
  UNSIGNED_OFFER_OVERDUE: 'Offer overdue',
  ONBOARDING_DOC_REJECTED: 'Doc rejected',
  I9_EVERIFY_TIMING_RISK: 'I-9/E-Verify risk',
  NO_PROJECT_ASSIGNED: 'No project',
  TRAINER_MEETING_MISSING: 'Trainer meeting',
  EVALUATION_OVERDUE: 'Evaluation overdue',
  TIMESHEET_MISSING: 'Timesheet missing',
  EXIT_CHECKLIST_PENDING: 'Exit checklist',
};

const TYPE_SEVERITY: Record<ErmExceptionType, ErmExceptionSeverity> = {
  UNSIGNED_OFFER_OVERDUE: 'URGENT',
  ONBOARDING_DOC_REJECTED: 'WARN',
  I9_EVERIFY_TIMING_RISK: 'URGENT',
  NO_PROJECT_ASSIGNED: 'WARN',
  TRAINER_MEETING_MISSING: 'WARN',
  EVALUATION_OVERDUE: 'WARN',
  TIMESHEET_MISSING: 'INFO',
  EXIT_CHECKLIST_PENDING: 'WARN',
};

const SEVERITY_CLS: Record<ErmExceptionSeverity, string> = {
  URGENT: 'bg-red-100 text-red-800',
  WARN: 'bg-amber-100 text-amber-800',
  INFO: 'bg-slate-100 text-slate-700',
};

const TYPE_ORDER: ErmExceptionType[] = [
  'UNSIGNED_OFFER_OVERDUE',
  'I9_EVERIFY_TIMING_RISK',
  'ONBOARDING_DOC_REJECTED',
  'EXIT_CHECKLIST_PENDING',
  'NO_PROJECT_ASSIGNED',
  'TRAINER_MEETING_MISSING',
  'EVALUATION_OVERDUE',
  'TIMESHEET_MISSING',
];

export default function ExceptionSummary({ counts, topUrgent }: Props) {
  const [filter, setFilter] = useState<ErmExceptionType | null>(null);
  const visible = filter ? topUrgent.filter((r) => r.type === filter) : topUrgent;
  const allClear =
    !counts || Object.values(counts).every((v) => !v || v === 0);

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-900">Attention required</h3>
        {filter && (
          <button
            type="button"
            onClick={() => setFilter(null)}
            className="text-xs text-slate-500 hover:text-slate-700"
          >
            Clear filter
          </button>
        )}
      </div>

      <div className="mt-3 flex flex-wrap gap-2">
        {TYPE_ORDER.map((t) => {
          const count = counts?.[t] ?? 0;
          const sev = TYPE_SEVERITY[t];
          const active = filter === t;
          return (
            <button
              key={t}
              type="button"
              onClick={() => setFilter(active ? null : t)}
              className={
                'inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-medium transition-colors ' +
                (active
                  ? SEVERITY_CLS[sev] + ' ring-1 ring-slate-400'
                  : count > 0
                    ? SEVERITY_CLS[sev]
                    : 'bg-slate-100 text-slate-500')
              }
            >
              {TYPE_LABEL[t]}
              <span className="rounded-full bg-white/60 px-1.5 text-[10px] tabular-nums">
                {count}
              </span>
            </button>
          );
        })}
      </div>

      <div className="mt-4 border-t border-slate-100 pt-3">
        {allClear ? (
          <p className="py-4 text-center text-sm text-slate-500">
            All clear — no urgent exceptions.
          </p>
        ) : visible.length === 0 ? (
          <p className="py-4 text-center text-sm text-slate-500">
            No matching urgent rows.
          </p>
        ) : (
          <ul className="space-y-2">
            {visible.map((r, i) => (
              <li
                key={(r.subjectResourceId ?? r.internId ?? '') + i}
                className="flex items-center gap-3 rounded-md border border-slate-100 bg-slate-50/60 p-3"
              >
                <span className="flex h-8 w-8 items-center justify-center rounded-full bg-brand-100 text-xs font-semibold text-brand-800">
                  {initials(r.internName)}
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="truncate text-sm font-medium text-slate-900">
                      {r.internName ?? '(unknown)'}
                    </span>
                    <span
                      className={
                        'rounded-full px-2 py-0.5 text-[10px] font-semibold ' +
                        SEVERITY_CLS[r.severity]
                      }
                    >
                      {TYPE_LABEL[r.type]}
                    </span>
                  </div>
                  <p className="text-[11px] text-slate-500">
                    {r.daysOverdue > 0
                      ? `${r.daysOverdue} day${r.daysOverdue === 1 ? '' : 's'} overdue`
                      : 'Flagged today'}
                  </p>
                </div>
                <Link
                  href={r.actionUrl}
                  className="rounded-md p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
                  aria-label="Open"
                >
                  <ChevronRight className="h-4 w-4" strokeWidth={2} />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
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
