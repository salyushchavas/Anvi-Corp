'use client';

import { Check } from 'lucide-react';
import { cn } from '@/lib/cn';

interface Props {
  steps: { key: string; label: string }[];
  currentIndex: number;
}

export default function StepperHorizontal({ steps, currentIndex }: Props) {
  return (
    <ol className="flex w-full items-center" role="list" aria-label="Progress">
      {steps.map((step, idx) => {
        const isPast = idx < currentIndex;
        const isCurrent = idx === currentIndex;
        const isFuture = idx > currentIndex;
        const isLast = idx === steps.length - 1;
        return (
          <li key={step.key} className="flex flex-1 items-center">
            <div className="flex items-center gap-2">
              <span
                aria-current={isCurrent ? 'step' : undefined}
                className={cn(
                  'flex h-7 w-7 shrink-0 items-center justify-center rounded-full border text-xs font-semibold',
                  isPast && 'border-brand-700 bg-brand-700 text-white',
                  isCurrent && 'border-brand-700 bg-brand-50 text-brand-700',
                  isFuture && 'border-slate-300 bg-white text-slate-400',
                )}
              >
                {isPast ? <Check className="h-3.5 w-3.5" strokeWidth={3} /> : idx + 1}
              </span>
              <span
                className={cn(
                  'text-xs font-medium sm:text-sm',
                  isPast && 'text-slate-700',
                  isCurrent && 'text-brand-700',
                  isFuture && 'text-slate-400',
                )}
              >
                {step.label}
              </span>
            </div>
            {!isLast && (
              <span
                aria-hidden="true"
                className={cn(
                  'mx-3 h-px flex-1',
                  isPast ? 'bg-brand-700' : 'bg-slate-200',
                )}
              />
            )}
          </li>
        );
      })}
    </ol>
  );
}
