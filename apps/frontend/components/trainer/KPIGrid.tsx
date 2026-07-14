'use client';

import KPICard, { KPICardSkeleton } from './KPICard';
import type { KpiSnapshot, TrainerKpiKey } from './types';

const ORDER: TrainerKpiKey[] = [
  'ACTIVE_INTERNS',
  'PROJECTS_DUE_THIS_WEEK',
  'SUBMISSIONS_PENDING_REVIEW',
  'MEETINGS_DUE',
  'OVERDUE_FEEDBACK',
  'REVISION_REQUESTS',
];

export default function KPIGrid({
  kpis,
  loading,
}: {
  kpis: Partial<Record<TrainerKpiKey, KpiSnapshot>>;
  loading: boolean;
}) {
  if (loading) {
    return (
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
        {ORDER.map((k) => (
          <KPICardSkeleton key={k} />
        ))}
      </div>
    );
  }
  return (
    <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
      {ORDER.map((k) => {
        const snap = kpis[k];
        if (!snap) return <KPICardSkeleton key={k} />;
        return <KPICard key={k} snapshot={snap} />;
      })}
    </div>
  );
}
