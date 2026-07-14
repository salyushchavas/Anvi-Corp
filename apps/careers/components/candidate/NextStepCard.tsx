'use client';

import Link from 'next/link';
import { ArrowRight, PauseCircle } from 'lucide-react';

export interface NextStepData {
  type?: string | null;
  title?: string | null;
  subtitle?: string | null;
  ctaLabel?: string | null;
  ctaHref?: string | null;
  isWaiting?: boolean;
  waitingFor?: string | null;
}

export default function NextStepCard({ step }: { step: NextStepData | null }) {
  if (!step || !step.title) return null;
  const waiting = !!step.isWaiting;
  return (
    <div
      className={
        'rounded-lg border p-4 ' +
        (waiting
          ? 'border-slate-300 bg-slate-100'
          : 'border-accent/30 bg-accent/5')
      }
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <p className="text-sm font-semibold text-slate-900">{step.title}</p>
            {waiting && (
              <span className="inline-flex items-center gap-1 rounded-full bg-slate-200 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-slate-700">
                <PauseCircle className="h-3 w-3" strokeWidth={2.5} />
                Waiting
              </span>
            )}
          </div>
          {step.subtitle && (
            <p className="mt-0.5 text-xs text-slate-600">{step.subtitle}</p>
          )}
          {waiting && step.waitingFor && (
            <p className="mt-0.5 text-xs text-slate-700">On {step.waitingFor}</p>
          )}
        </div>
        {!waiting && step.ctaHref && step.ctaLabel && (
          <Link
            href={step.ctaHref}
            className="inline-flex items-center gap-1 rounded-md bg-accent px-3 py-1.5 text-xs font-semibold text-white hover:bg-accent-dark"
          >
            {step.ctaLabel}
            <ArrowRight className="h-3 w-3" strokeWidth={2.5} />
          </Link>
        )}
      </div>
    </div>
  );
}
