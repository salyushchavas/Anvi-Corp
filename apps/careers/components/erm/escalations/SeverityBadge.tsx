'use client';

import type { Severity } from './types';

const STYLES: Record<Severity, string> = {
  URGENT: 'bg-red-100 text-red-800 ring-red-200',
  WARN: 'bg-amber-100 text-amber-800 ring-amber-200',
  INFO: 'bg-slate-100 text-slate-700 ring-slate-200',
};

const DOT: Record<Severity, string> = {
  URGENT: 'bg-red-500',
  WARN: 'bg-amber-500',
  INFO: 'bg-slate-500',
};

export default function SeverityBadge({
  severity,
  compact = false,
}: {
  severity: Severity;
  compact?: boolean;
}) {
  if (compact) {
    return (
      <span
        className={'inline-block h-2 w-2 rounded-full ' + DOT[severity]}
        aria-label={severity}
      />
    );
  }
  return (
    <span
      className={
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ring-1 ring-inset ' +
        STYLES[severity]
      }
    >
      <span className={'h-1.5 w-1.5 rounded-full ' + DOT[severity]} />
      {severity}
    </span>
  );
}
