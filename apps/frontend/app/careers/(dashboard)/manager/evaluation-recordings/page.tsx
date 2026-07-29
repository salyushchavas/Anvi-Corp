'use client';

import Link from 'next/link';
import EvaluationRecordingsGallery from '@/components/dashboard/EvaluationRecordingsGallery';

/**
 * Manager view of the evaluator session-recording gallery. Read-only —
 * the upload path lives on the evaluator compose page. Same shared
 * component as {@code /careers/erm/evaluation-recordings}.
 */
export default function ManagerEvaluationRecordingsPage() {
  return (
    <div className="mx-auto max-w-6xl space-y-4 p-6">
      <header>
        <p className="text-xs text-slate-500">
          <Link href="/careers/manager" className="hover:text-slate-700">
            ← Manager dashboard
          </Link>
        </p>
        <h1 className="mt-1 text-2xl font-semibold text-slate-900">
          Recording Sessions
        </h1>
        <p className="mt-1 text-sm text-slate-600">
          Video recordings the evaluator attached to each monthly / final /
          I-983 evaluation session. Grouped by month → intern → project.
        </p>
      </header>
      <EvaluationRecordingsGallery />
    </div>
  );
}
