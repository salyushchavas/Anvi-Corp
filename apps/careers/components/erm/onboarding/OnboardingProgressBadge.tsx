'use client';

/**
 * Compact onboarding-progress pill used on the ERM new-hire list rows.
 * Renders one of:
 *   - "4/6 · needs mail ID + joining date" (in-flight)
 *   - "Ready to activate" (5/6 + canActivate true)
 *   - "Active" (6/6)
 *   - "—" when the backend didn't supply the fields (older clients)
 *
 * Design: matches the existing list-row chip styles (rounded-full,
 * brand-100/700 + green-100/800 + slate-100/700 from the StructurePills
 * pattern in new-hire/page.tsx). No new dependencies.
 */

import { CheckCircle2, Loader2 } from 'lucide-react';
import type { NewHireRow } from '@/components/erm/offers/types';

export default function OnboardingProgressBadge({ row }: { row: NewHireRow }) {
  const completed = row.stepsCompleted;
  const total = row.stepsTotal;
  if (completed == null || total == null) {
    return <span className="text-[11px] text-slate-400">—</span>;
  }
  // 6/6 — fully active.
  if (completed === total) {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-[11px] font-semibold text-green-800">
        <CheckCircle2 className="h-3 w-3" />
        Active
      </span>
    );
  }
  // 5/6 with canActivate true — gate just opened.
  if (row.canActivate) {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-accent/15 px-2 py-0.5 text-[11px] font-semibold text-accent-dark ring-1 ring-accent/30">
        Ready to activate
      </span>
    );
  }
  // In-flight — show "N/total · needs X".
  return (
    <span
      title={row.nextStepLabel ?? undefined}
      className="inline-flex items-center gap-1 rounded-full bg-brand-50 px-2 py-0.5 text-[11px] font-semibold text-brand-800 ring-1 ring-brand-200"
    >
      <Loader2 className="h-3 w-3" />
      {completed}/{total}
      {row.nextStepLabel && (
        <span className="font-normal text-brand-700 normal-case">
          · {row.nextStepLabel}
        </span>
      )}
    </span>
  );
}
