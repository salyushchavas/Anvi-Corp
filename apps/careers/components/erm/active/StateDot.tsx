'use client';

import type { CardState, MonitorState } from './types';

const DOT: Record<CardState, string> = {
  OK: 'bg-green-500',
  WARN: 'bg-amber-500',
  URGENT: 'bg-red-500',
};

export default function StateDot({
  state,
  label,
  detail,
  size = 'sm',
}: {
  state: MonitorState;
  label?: string;
  detail?: boolean;
  size?: 'xs' | 'sm';
}) {
  const dotSize = size === 'xs' ? 'h-1.5 w-1.5' : 'h-2 w-2';
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-slate-700">
      <span
        className={'inline-block rounded-full ' + dotSize + ' ' + DOT[state.state]}
        aria-label={state.state}
      />
      {label ?? state.label}
      {detail && state.detail && (
        <span className="text-[11px] text-slate-500">· {state.detail}</span>
      )}
    </span>
  );
}
