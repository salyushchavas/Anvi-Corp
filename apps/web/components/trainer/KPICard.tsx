'use client';

import Link from 'next/link';
import { ArrowUpRight } from 'lucide-react';
import type { KpiSnapshot } from './types';

export default function KPICard({ snapshot }: { snapshot: KpiSnapshot }) {
  const isUrgent = snapshot.urgentCount > 0;
  return (
    <Link
      href={snapshot.actionUrl}
      className={
        'group block rounded-lg border bg-white p-4 shadow-sm transition-all ' +
        'hover:-translate-y-0.5 hover:shadow-md ' +
        (isUrgent
          ? 'border-red-200 ring-1 ring-red-100'
          : 'border-slate-200')
      }
    >
      <div className="flex items-start justify-between">
        <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
          {snapshot.label}
        </p>
        <ArrowUpRight
          className="h-4 w-4 text-slate-300 transition-colors group-hover:text-slate-500"
          strokeWidth={2}
        />
      </div>
      <div className="mt-2 flex items-baseline gap-2">
        <span className="text-3xl font-semibold tabular-nums text-slate-900">
          {snapshot.count}
        </span>
        {isUrgent && (
          <span className="rounded-full bg-red-100 px-2 py-0.5 text-[11px] font-semibold text-red-700">
            {snapshot.urgentCount} urgent
          </span>
        )}
      </div>
      {snapshot.helperText && (
        <p className="mt-2 text-xs text-slate-500">{snapshot.helperText}</p>
      )}
    </Link>
  );
}

export function KPICardSkeleton() {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <div className="h-3 w-32 animate-pulse rounded bg-slate-100" />
      <div className="mt-3 h-8 w-16 animate-pulse rounded bg-slate-100" />
      <div className="mt-3 h-3 w-40 animate-pulse rounded bg-slate-100" />
    </div>
  );
}
