'use client';

import Link from 'next/link';
import { ChevronLeft } from 'lucide-react';

/**
 * Manager Phase 0 — uniform placeholder for the 9 deep sidebar destinations.
 * Each renders title + description + "Coming in Manager Phase N" badge so
 * the doc-spec'd 10-item navigation is reachable end-to-end without any
 * business logic shipping yet.
 */
export default function ManagerStubPage({
  title,
  description,
  phase,
}: {
  title: string;
  description: string;
  phase: number;
}) {
  return (
    <div className="mx-auto max-w-3xl p-6">
      <p className="text-xs text-slate-500">
        <Link
          href="/careers/manager"
          className="inline-flex items-center gap-1 hover:text-slate-700"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          Manager home
        </Link>
      </p>
      <div className="mt-3 rounded-lg border border-dashed border-slate-300 bg-white p-8">
        <div className="mb-3 flex items-baseline justify-between">
          <h1 className="text-xl font-semibold text-slate-900">{title}</h1>
          <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-semibold text-amber-800">
            Coming in Manager Phase {phase}
          </span>
        </div>
        <p className="text-sm text-slate-600">{description}</p>
        <p className="mt-4 text-[11px] text-slate-400">
          Manager Phase 0 ships the 10-item navigation + role gating only.
          Aggregations, KPIs, approvals, and exports land in Phases 1–5.
        </p>
      </div>
    </div>
  );
}
