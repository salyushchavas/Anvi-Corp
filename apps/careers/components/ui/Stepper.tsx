'use client';

import Link from 'next/link';
import {
  Check,
  Circle,
  Clock,
  Hourglass,
  MinusCircle,
  XCircle,
  type LucideIcon,
} from 'lucide-react';
import { cn } from '@/lib/cn';
import { type Semantic, TONE_CLASSES } from '@/lib/status';

export type TimelineStatus =
  | 'DONE'
  | 'IN_PROGRESS'
  | 'WAITING'
  | 'BLOCKED'
  | 'NOT_STARTED'
  | 'SKIPPED';

export interface TimelineStep {
  key: string;
  label: string;
  status: TimelineStatus;
  description?: string | null;
  completedAt?: string | null;
  waitingFor?: string | null;
  actionLabel?: string | null;
  actionHref?: string | null;
}

interface Props {
  steps: TimelineStep[];
  variant?: 'detailed' | 'compact';
}

const STATUS_TONE: Record<TimelineStatus, Semantic> = {
  DONE: 'success',
  IN_PROGRESS: 'info',
  WAITING: 'warning',
  BLOCKED: 'danger',
  NOT_STARTED: 'neutral',
  SKIPPED: 'neutral',
};

const STATUS_ICON: Record<TimelineStatus, LucideIcon> = {
  DONE: Check,
  IN_PROGRESS: Clock,
  WAITING: Hourglass,
  BLOCKED: XCircle,
  NOT_STARTED: Circle,
  SKIPPED: MinusCircle,
};

function formatTime(iso?: string | null): string | null {
  if (!iso) return null;
  try {
    const d = new Date(iso);
    return d.toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
    });
  } catch {
    return iso;
  }
}

export default function Stepper({ steps, variant = 'detailed' }: Props) {
  if (!steps?.length) return null;
  return (
    <ol className="space-y-0" role="list">
      {steps.map((step, idx) => {
        const last = idx === steps.length - 1;
        const next = steps[idx + 1];
        const tone = STATUS_TONE[step.status];
        const tones = TONE_CLASSES[tone];
        const Icon = STATUS_ICON[step.status];
        const isCurrent = step.status === 'IN_PROGRESS' || step.status === 'WAITING' || step.status === 'BLOCKED';
        const dim = step.status === 'NOT_STARTED' || step.status === 'SKIPPED';
        const dashed = next?.status === 'SKIPPED' || step.status === 'SKIPPED';
        const annotation = (() => {
          if (step.status === 'DONE') return formatTime(step.completedAt) ?? 'Completed';
          if (step.status === 'WAITING') return step.waitingFor ? `Waiting on ${step.waitingFor}` : 'Waiting';
          if (step.status === 'BLOCKED') return 'Action required';
          if (step.status === 'IN_PROGRESS') return 'In progress';
          if (step.status === 'SKIPPED') return 'Not applicable';
          return null;
        })();

        return (
          <li key={step.key} className="relative flex gap-4 pb-5 last:pb-0">
            {/* Rail */}
            {!last && (
              <span
                aria-hidden="true"
                className={cn(
                  'absolute left-[13px] top-7 -bottom-1 w-px',
                  dashed
                    ? 'border-l border-dashed border-slate-200'
                    : 'bg-slate-200',
                )}
              />
            )}
            {/* Icon node */}
            <span
              className={cn(
                'relative z-[1] flex h-7 w-7 shrink-0 items-center justify-center rounded-full border',
                tones.bg,
                tones.border,
              )}
            >
              <Icon className={cn('h-3.5 w-3.5', tones.icon)} strokeWidth={2.5} />
            </span>
            {/* Body */}
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <p
                  className={cn(
                    'text-sm font-medium leading-5',
                    dim ? 'text-slate-400' : 'text-slate-900',
                  )}
                >
                  {step.label}
                </p>
                {annotation && (
                  <span
                    className={cn(
                      'text-xs',
                      step.status === 'BLOCKED' ? 'text-red-700' : 'text-slate-500',
                    )}
                  >
                    {annotation}
                  </span>
                )}
              </div>
              {(variant === 'detailed' || isCurrent) && step.description && (
                <p className={cn('mt-0.5 text-xs', dim ? 'text-slate-400' : 'text-slate-600')}>
                  {step.description}
                </p>
              )}
              {step.actionHref && step.actionLabel && (
                <Link
                  href={step.actionHref}
                  className="mt-1.5 inline-flex items-center gap-1 text-xs font-medium text-brand-700 hover:underline"
                >
                  {step.actionLabel} →
                </Link>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
