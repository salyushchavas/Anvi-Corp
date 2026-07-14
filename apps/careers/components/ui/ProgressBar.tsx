'use client';

import { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { TONE_CLASSES, type Semantic } from '@/lib/status';

export interface ProgressBarProps {
  value: number;
  max?: number;
  label?: ReactNode;
  sublabel?: ReactNode;
  accent?: Semantic;
  className?: string;
}

export default function ProgressBar({
  value,
  max = 100,
  label,
  sublabel,
  accent = 'info',
  className,
}: ProgressBarProps) {
  const pct = Math.max(0, Math.min(100, (value / max) * 100));
  // Use the tone's icon class as the fill family (e.g. text-green-600 → bg-green-500).
  const fillBg = TONE_CLASSES[accent].icon.replace('text-', 'bg-').replace('-600', '-500').replace('-500', '-500');
  return (
    <div className={cn('w-full', className)}>
      {(label || sublabel) && (
        <div className="mb-1 flex items-baseline justify-between gap-2">
          {label && <p className="text-xs font-medium text-slate-700">{label}</p>}
          {sublabel && <p className="text-xs text-slate-500">{sublabel}</p>}
        </div>
      )}
      <div
        role="progressbar"
        aria-valuenow={value}
        aria-valuemin={0}
        aria-valuemax={max}
        className="h-1.5 w-full overflow-hidden rounded-full bg-slate-100"
      >
        <div
          className={cn('h-full rounded-full transition-all duration-200 ease-out', fillBg)}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}
