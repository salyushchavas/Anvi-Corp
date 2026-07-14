'use client';

import { ReactNode } from 'react';
import Link from 'next/link';
import { CalendarClock, Github, User as UserIcon } from 'lucide-react';
import { formatDateOnly } from '@/lib/format-date';

export interface AssignmentCardData {
  id: string;
  internName: string | null;
  internEmail?: string | null;
  githubUsername?: string | null;
  status: string;
  statusLabel?: string;
  assignedAt?: string | null;
  dueDate?: string | null;
  daysRemaining?: number | null;
  lastActivityAt?: string | null;
  projectTitle?: string | null;
}

interface Props {
  data: AssignmentCardData;
  /** Role-aware footer action slot. */
  primaryAction?: ReactNode;
  href?: string;
}

const STATUS_PILL: Record<string, string> = {
  ASSIGNED: 'bg-slate-100 text-slate-700',
  ACCESS_GRANTED: 'bg-slate-100 text-slate-700',
  ACCESS_ACCEPTED: 'bg-slate-100 text-slate-700',
  IN_PROGRESS: 'bg-slate-100 text-slate-700',
  SUBMITTED: 'bg-amber-100 text-amber-800',
  RETURNED: 'bg-amber-100 text-amber-800',
  TECH_APPROVED: 'bg-slate-100 text-slate-700',
  PENDING_VIVA: 'bg-amber-100 text-amber-800',
  COMPLETED: 'bg-green-100 text-green-800',
};

function initials(name?: string | null): string {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

export default function AssignmentCard({ data, primaryAction, href }: Props) {
  const inner = (
    <article className="flex h-full flex-col gap-3 rounded-xl border border-slate-200 bg-white p-4 transition hover:border-accent/40 hover:shadow-md">
      <header className="flex items-start gap-3">
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-accent/15 text-xs font-semibold text-accent-dark">
          {initials(data.internName)}
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold text-slate-900">
            {data.internName ?? '—'}
          </p>
          {data.githubUsername ? (
            <p className="flex items-center gap-1 truncate text-xs text-slate-500">
              <Github className="h-3 w-3" strokeWidth={2} />
              {data.githubUsername}
            </p>
          ) : (
            <p className="flex items-center gap-1 text-xs text-red-700">
              <Github className="h-3 w-3" strokeWidth={2} />
              GitHub username missing
            </p>
          )}
        </div>
        <span
          className={
            'rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide ' +
            (STATUS_PILL[data.status] ?? 'bg-slate-100 text-slate-700')
          }
        >
          {data.statusLabel ?? data.status.replaceAll('_', ' ')}
        </span>
      </header>

      {data.projectTitle && (
        <p className="line-clamp-2 text-xs text-slate-600">{data.projectTitle}</p>
      )}

      <dl className="grid grid-cols-2 gap-2 text-[11px] text-slate-500">
        {data.assignedAt && (
          <div>
            <dt className="font-semibold uppercase tracking-wide text-slate-400">Assigned</dt>
            <dd className="mt-0.5 text-slate-700">{formatDateOnly(data.assignedAt)}</dd>
          </div>
        )}
        {data.dueDate && (
          <div>
            <dt className="font-semibold uppercase tracking-wide text-slate-400">Due</dt>
            <dd className="mt-0.5 flex items-center gap-1 text-slate-700">
              <CalendarClock className="h-3 w-3" strokeWidth={2} />
              {formatDateOnly(data.dueDate)}
              {typeof data.daysRemaining === 'number' && (
                <span className={data.daysRemaining < 0 ? 'text-red-700' : 'text-slate-500'}>
                  ({data.daysRemaining < 0
                    ? `${Math.abs(data.daysRemaining)}d overdue`
                    : `${data.daysRemaining}d left`})
                </span>
              )}
            </dd>
          </div>
        )}
        {data.lastActivityAt && (
          <div className="col-span-2">
            <dt className="font-semibold uppercase tracking-wide text-slate-400">
              Last activity
            </dt>
            <dd className="mt-0.5 text-slate-700">{formatDateOnly(data.lastActivityAt)}</dd>
          </div>
        )}
      </dl>

      {primaryAction && (
        <footer className="mt-auto flex items-center justify-end pt-1">{primaryAction}</footer>
      )}
    </article>
  );

  if (href) {
    return <Link href={href}>{inner}</Link>;
  }
  return inner;
}
