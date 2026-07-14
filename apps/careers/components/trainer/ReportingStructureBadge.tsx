'use client';

import type { ReportingStructure } from './types';

export default function ReportingStructureBadge({
  reporting,
  compact = false,
}: {
  reporting: ReportingStructure;
  compact?: boolean;
}) {
  if (compact) {
    return (
      <p className="text-[11px] text-slate-600">
        T: {reporting.trainerName ?? '—'} · E:{' '}
        {reporting.evaluatorName ?? '—'} · M: {reporting.managerName ?? '—'}
      </p>
    );
  }
  return (
    <dl className="grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
      <Cell label="Trainer" value={reporting.trainerName} />
      <Cell label="Evaluator" value={reporting.evaluatorName} />
      <Cell label="Manager" value={reporting.managerName} />
      <Cell label="ERM" value={reporting.ermName} />
    </dl>
  );
}

function Cell({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex flex-col">
      <dt className="text-[10px] font-semibold uppercase tracking-wide text-slate-400">
        {label}
      </dt>
      <dd className="text-slate-800">{value ?? '—'}</dd>
    </div>
  );
}
