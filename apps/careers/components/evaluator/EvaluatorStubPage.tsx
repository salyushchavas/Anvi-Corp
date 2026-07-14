'use client';

import Link from 'next/link';
import { ChevronLeft } from 'lucide-react';

/**
 * Evaluator Phase 0 — uniform placeholder for the 9 sidebar destinations.
 * Each renders title + description + "Coming in Evaluator Phase N" badge
 * so the doc-spec'd 9-item navigation is reachable end-to-end without
 * any business logic shipping yet.
 */
export default function EvaluatorStubPage({
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
          href="/careers/evaluator"
          className="inline-flex items-center gap-1 hover:text-slate-700"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          Evaluator home
        </Link>
      </p>
      <div className="mt-3 rounded-lg border border-dashed border-slate-300 bg-white p-8">
        <div className="mb-3 flex items-baseline justify-between">
          <h1 className="text-xl font-semibold text-slate-900">{title}</h1>
          <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-semibold text-amber-800">
            Coming in Evaluator Phase {phase}
          </span>
        </div>
        <p className="text-sm text-slate-600">{description}</p>
        <p className="mt-4 text-[11px] text-slate-400">
          Evaluator Phase 0 ships the 9-item navigation + schema +
          7 communication templates + exception type hooks. Business
          logic lands in Phases 1-4.
        </p>
      </div>
    </div>
  );
}
