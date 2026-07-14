'use client';

import Link from 'next/link';
import { formatRelative } from '@/lib/format-date';
import type { ActivityEntry } from './ErmDashboardContext';

interface Props {
  entries: ActivityEntry[];
}

export default function RecentActivityCard({ entries }: Props) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-900">Recent activity</h3>
        <Link
          href="/careers/erm/reports"
          className="text-xs font-medium text-brand-700 hover:underline"
        >
          View all →
        </Link>
      </div>
      {entries.length === 0 ? (
        <p className="mt-4 text-sm text-slate-500">
          No recent activity in this scope.
        </p>
      ) : (
        <ul className="mt-3 divide-y divide-slate-100">
          {entries.map((e, i) => (
            <li key={i} className="flex items-start justify-between gap-3 py-2.5">
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm text-slate-800">
                  <span className="font-medium">{e.actorName ?? 'System'}</span>{' '}
                  <span className="text-slate-500">{humanize(e.action)}</span>{' '}
                  {e.subjectName && (
                    <>
                      <span className="text-slate-500">on</span>{' '}
                      <span className="font-medium">{e.subjectName}</span>
                    </>
                  )}
                </p>
                <p className="text-[11px] text-slate-400">
                  {e.timestamp ? formatRelative(e.timestamp) : ''}
                </p>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function humanize(action: string | null): string {
  if (!action) return '';
  return action
    .toLowerCase()
    .replace(/_/g, ' ');
}
