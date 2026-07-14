'use client';

import Link from 'next/link';
import {
  AlertCircle,
  CheckCircle2,
  Circle,
  Clock,
  MinusCircle,
  PauseCircle,
  XCircle,
} from 'lucide-react';

export type TimelineStatus =
  | 'DONE'
  | 'IN_PROGRESS'
  | 'WAITING'
  | 'BLOCKED'
  | 'NOT_STARTED'
  | 'SKIPPED';

export interface TimelineStep {
  key: string;
  label: string;
  status: TimelineStatus;
  completedAt?: string | null;
  waitingFor?: string | null;
  description?: string | null;
  actionRequired?: boolean;
  actionHref?: string | null;
}

interface Props {
  steps: TimelineStep[];
}

function StatusIcon({ status }: { status: TimelineStatus }) {
  switch (status) {
    case 'DONE':
      return <CheckCircle2 className="h-4 w-4 text-green-600" strokeWidth={2.5} />;
    case 'IN_PROGRESS':
      return <Clock className="h-4 w-4 text-slate-500" strokeWidth={2.5} />;
    case 'WAITING':
      return <PauseCircle className="h-4 w-4 text-amber-600" strokeWidth={2.5} />;
    case 'BLOCKED':
      return <XCircle className="h-4 w-4 text-red-600" strokeWidth={2.5} />;
    case 'SKIPPED':
      return <MinusCircle className="h-4 w-4 text-slate-400" strokeWidth={2.5} />;
    case 'NOT_STARTED':
    default:
      return <Circle className="h-4 w-4 text-slate-300" strokeWidth={2} />;
  }
}

function statusAnnotation(step: TimelineStep): string | null {
  if (step.status === 'DONE' && step.completedAt) {
    return `Completed ${formatShortDate(step.completedAt)}`;
  }
  if (step.status === 'WAITING' && step.waitingFor) {
    return `Waiting on ${step.waitingFor}`;
  }
  if (step.status === 'IN_PROGRESS' && step.actionRequired) {
    return 'Action required';
  }
  if (step.status === 'BLOCKED') {
    return 'Blocked';
  }
  if (step.status === 'SKIPPED') {
    return 'Not applicable';
  }
  return null;
}

function formatShortDate(iso: string): string {
  try {
    const d = new Date(iso);
    return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  } catch {
    return iso;
  }
}

export default function JourneyTimeline({ steps }: Props) {
  if (!steps || steps.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-slate-200 p-4 text-sm text-slate-500">
        No timeline available yet.
      </div>
    );
  }
  return (
    <ol className="relative space-y-3 border-l border-slate-200 pl-5">
      {steps.map((step) => {
        const annotation = statusAnnotation(step);
        const dim = step.status === 'SKIPPED' || step.status === 'NOT_STARTED';
        return (
          <li key={step.key} className="relative">
            <span className="absolute -left-[27px] top-0.5 flex h-5 w-5 items-center justify-center rounded-full bg-white ring-2 ring-white">
              <StatusIcon status={step.status} />
            </span>
            <div
              className={
                'flex flex-wrap items-start justify-between gap-2 rounded-md px-2 py-1.5 ' +
                (dim ? 'text-slate-500' : 'text-slate-800')
              }
            >
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium">{step.label}</p>
                {step.description && (
                  <p className="mt-0.5 text-xs text-slate-500">{step.description}</p>
                )}
                {annotation && (
                  <p className="mt-0.5 text-[11px] uppercase tracking-wide text-slate-500">
                    {annotation}
                  </p>
                )}
              </div>
              {step.actionRequired && step.actionHref && (
                <Link
                  href={step.actionHref}
                  className="inline-flex items-center gap-1 rounded-md bg-accent/10 px-2 py-1 text-xs font-medium text-primary-700 hover:bg-accent/20"
                >
                  Open
                </Link>
              )}
              {step.status === 'BLOCKED' && (
                <span className="inline-flex items-center gap-1 text-xs text-red-700">
                  <AlertCircle className="h-3 w-3" strokeWidth={2.5} />
                  Resolve
                </span>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
