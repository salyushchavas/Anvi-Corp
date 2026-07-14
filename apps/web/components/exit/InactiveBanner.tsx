'use client';

import { Lock } from 'lucide-react';
import type { InternExitSummary } from '@/components/intern/InternDashboardContext';

interface Props {
  exitSummary: InternExitSummary | null;
}

const TYPE_LABEL: Record<string, string> = {
  COMPLETED: 'completed',
  RESIGNED: 'resigned',
  TERMINATED: 'terminated',
  EXTENDED: 'extended',
};

/**
 * Phase 8 — persistent banner across every intern page when the mode is
 * INACTIVE. Renders the exit type + date and a "records are read-only"
 * affordance so the intern knows why their forms are disabled.
 */
export default function InactiveBanner({ exitSummary }: Props) {
  const type = exitSummary?.exitType
    ? TYPE_LABEL[exitSummary.exitType] ?? exitSummary.exitType.toLowerCase()
    : 'concluded';
  const date = exitSummary?.exitDate ?? null;
  return (
    <div className="mb-4 flex items-start gap-2 rounded-md border border-slate-200 bg-slate-100 px-4 py-3 text-sm text-slate-700">
      <Lock className="mt-0.5 h-4 w-4 flex-shrink-0 text-slate-500" strokeWidth={2} />
      <div className="flex-1">
        <span className="font-medium">Internship {type}</span>
        {date && <span> on {date}</span>}
        <span className="text-slate-500">
          {' '}— records below are read-only.
        </span>
      </div>
    </div>
  );
}
