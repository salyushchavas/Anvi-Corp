'use client';

import type { AlertSeverity } from './types';

const COLOR: Record<AlertSeverity, string> = {
  URGENT: 'bg-red-500',
  WARN: 'bg-amber-500',
  INFO: 'bg-slate-500',
};

export default function AlertSeverityDot({
  severity,
  label,
}: {
  severity: AlertSeverity | null | undefined;
  label?: string;
}) {
  if (!severity) {
    return <span className="text-xs text-slate-400">—</span>;
  }
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-slate-700">
      <span
        className={'inline-block h-2 w-2 rounded-full ' + COLOR[severity]}
        aria-label={severity}
      />
      {label ?? severity}
    </span>
  );
}
