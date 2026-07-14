'use client';

import type { ErmKpiKey, KpiSnapshot } from './ErmDashboardContext';
import KPICard, { KPICardSkeleton } from './KPICard';

interface Props {
  kpis: Record<ErmKpiKey, KpiSnapshot> | null;
  loading: boolean;
}

// ERM Phase 8.2 — 9 KPI cards. AWAITING_DOCUMENT_PACKET surfaces the
// new "ready for docs, no packet yet" intern bucket; placed right after
// OFFERS_PENDING_SIGNATURE since it's the next stage in the funnel.
const ORDER: ErmKpiKey[] = [
  'APPLICATIONS_PENDING_REVIEW',
  'INTERVIEWS_TODAY',
  'OFFERS_PENDING_SIGNATURE',
  'AWAITING_DOCUMENT_PACKET',
  'ONBOARDING_OVERDUE',
  'I9_EVERIFY_DUE',
  'ACTIVE_INTERNS_WITHOUT_PROJECT',
  'EVALUATIONS_OVERDUE',
  'TIMESHEETS_PENDING_APPROVAL',
];

export default function KPIGrid({ kpis, loading }: Props) {
  if (loading && !kpis) {
    return (
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {ORDER.map((k) => <KPICardSkeleton key={k} />)}
      </div>
    );
  }
  if (!kpis) return null;
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      {ORDER.map((k) =>
        kpis[k] ? <KPICard key={k} snapshot={kpis[k]} /> : null,
      )}
    </div>
  );
}
