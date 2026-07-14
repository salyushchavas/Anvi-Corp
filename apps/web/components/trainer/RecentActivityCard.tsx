'use client';

import Link from 'next/link';
import type { RecentActivityRow } from './types';

export default function RecentActivityCard({
  rows,
}: {
  rows: RecentActivityRow[];
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
      <header className="border-b border-slate-100 px-4 py-3">
        <h3 className="text-sm font-semibold text-slate-900">Recent activity</h3>
      </header>
      {rows.length === 0 ? (
        <div className="px-4 py-8 text-center text-xs text-slate-500">
          No recent activity scoped to your interns.
        </div>
      ) : (
        <ul className="divide-y divide-slate-100">
          {rows.slice(0, 10).map((r, i) => {
            const when = r.at ? relative(r.at) : '—';
            const body = (
              <li
                key={i}
                className="flex items-start gap-3 px-4 py-3 text-xs"
              >
                <span className="mt-1 inline-block h-1.5 w-1.5 shrink-0 rounded-full bg-slate-300" />
                <div className="min-w-0 flex-1">
                  <p className="text-sm text-slate-900">
                    <span className="font-medium">
                      {r.actorName ?? 'system'}
                    </span>
                    {' · '}
                    <span className="text-slate-600">{r.action ?? '—'}</span>
                    {r.subjectName ? (
                      <>
                        {' '}
                        <span className="text-slate-500">on</span>{' '}
                        <span className="font-medium text-slate-700">
                          {r.subjectName}
                        </span>
                      </>
                    ) : null}
                  </p>
                  <p className="mt-0.5 text-[11px] text-slate-500">
                    {r.entityType ?? '—'} · {when}
                  </p>
                </div>
              </li>
            );
            return r.deepLink ? (
              <Link key={i} href={r.deepLink} className="block hover:bg-slate-50">
                {body}
              </Link>
            ) : (
              body
            );
          })}
        </ul>
      )}
    </section>
  );
}

function relative(iso: string): string {
  const now = Date.now();
  const t = new Date(iso).getTime();
  const diffSec = Math.max(0, Math.round((now - t) / 1000));
  if (diffSec < 60) return diffSec + 's ago';
  if (diffSec < 3600) return Math.round(diffSec / 60) + 'm ago';
  if (diffSec < 86400) return Math.round(diffSec / 3600) + 'h ago';
  return Math.round(diffSec / 86400) + 'd ago';
}
