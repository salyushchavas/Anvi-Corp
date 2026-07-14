'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { LifeBuoy } from 'lucide-react';
import api from '@/lib/api';

/**
 * Trainer-dashboard summary of open Doubt Requests. Lightweight — pulls
 * the open queue and shows the top three; the queue page at
 * /careers/trainer/doubts is the full surface.
 */
interface DoubtSummaryRow {
  id: string;
  internName: string | null;
  projectTitle: string | null;
  text: string;
  status: string;
  createdAt: string;
}

export default function PendingDoubtsCard() {
  const [rows, setRows] = useState<DoubtSummaryRow[] | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.get<DoubtSummaryRow[]>('/api/v1/trainer/doubts?open=true')
      .then((res) => {
        if (!cancelled) setRows(res.data ?? []);
      })
      .catch((e) => {
        if (!cancelled) setErr(e instanceof Error ? e.message : 'load failed');
      });
    return () => { cancelled = true; };
  }, []);

  const count = rows?.length ?? 0;

  return (
    <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
      <header className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
        <div className="flex items-center gap-2">
          <LifeBuoy className="h-4 w-4 text-brand-600" strokeWidth={2} />
          <h3 className="text-sm font-semibold text-slate-900">Doubt Requests</h3>
        </div>
        <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-medium text-slate-600">
          {count}
        </span>
      </header>
      {err && (
        <p className="px-4 py-3 text-xs text-red-700">{err}</p>
      )}
      {!err && rows === null ? (
        <div className="px-4 py-6">
          <div className="h-4 w-full animate-pulse rounded bg-slate-100" />
        </div>
      ) : count === 0 ? (
        <div className="px-4 py-6 text-center text-xs text-slate-500">
          No open doubts.
        </div>
      ) : (
        <ul className="divide-y divide-slate-100">
          {(rows ?? []).slice(0, 3).map((d) => (
            <li key={d.id} className="px-4 py-3">
              <div className="text-sm font-medium text-slate-900">
                {d.internName ?? '(unknown intern)'}
              </div>
              <div className="text-[11px] text-slate-500">
                {d.projectTitle ?? 'General'} · {d.status.toLowerCase().replace('_', ' ')}
              </div>
              <p className="mt-1 line-clamp-2 text-xs text-slate-700">{d.text}</p>
            </li>
          ))}
        </ul>
      )}
      <footer className="border-t border-slate-100 px-4 py-2 text-right">
        <Link
          href="/careers/trainer/doubts"
          className="text-[11px] font-medium text-brand-700 hover:underline"
        >
          View all →
        </Link>
      </footer>
    </section>
  );
}
