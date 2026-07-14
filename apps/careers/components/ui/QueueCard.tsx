'use client';

import Link from 'next/link';
import { ArrowRight, type LucideIcon } from 'lucide-react';
import { ComponentType, ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { TONE_CLASSES, type Semantic } from '@/lib/status';

export interface QueueCardProps {
  title: string;
  count: number | string;
  description?: ReactNode;
  accent?: Semantic;
  ctaLabel?: string;
  ctaHref?: string;
  onCtaClick?: () => void;
  icon?: LucideIcon | ComponentType<{ className?: string }>;
  className?: string;
}

export default function QueueCard({
  title,
  count,
  description,
  accent = 'info',
  ctaLabel,
  ctaHref,
  onCtaClick,
  icon: Icon,
  className,
}: QueueCardProps) {
  const tones = TONE_CLASSES[accent];
  const cta =
    ctaLabel && (ctaHref || onCtaClick) ? (
      ctaHref ? (
        <Link
          href={ctaHref}
          className="inline-flex items-center gap-1 text-xs font-medium text-brand-700 hover:underline"
        >
          {ctaLabel}
          <ArrowRight className="h-3 w-3" strokeWidth={2.5} />
        </Link>
      ) : (
        <button
          type="button"
          onClick={onCtaClick}
          className="inline-flex items-center gap-1 text-xs font-medium text-brand-700 hover:underline"
        >
          {ctaLabel}
          <ArrowRight className="h-3 w-3" strokeWidth={2.5} />
        </button>
      )
    ) : null;
  return (
    <div
      className={cn(
        'relative overflow-hidden rounded-lg border border-slate-200 bg-white p-5 shadow-ds-sm transition-shadow hover:shadow-ds-md',
        className,
      )}
    >
      <span
        aria-hidden="true"
        className={cn('absolute left-0 top-0 h-full w-1', tones.bg.replace('-50', '-500'))}
      />
      <div className="flex items-start justify-between gap-3 pl-2">
        {Icon && (
          <span
            className={cn(
              'flex h-9 w-9 items-center justify-center rounded-full',
              tones.bg,
              tones.icon,
            )}
          >
            <Icon className="h-4 w-4" />
          </span>
        )}
        <div className="ml-auto text-right">
          <p className="text-3xl font-semibold leading-none text-slate-900">{count}</p>
        </div>
      </div>
      <p className="mt-3 pl-2 text-sm font-medium text-slate-700">{title}</p>
      {description && (
        <p className="mt-0.5 pl-2 text-xs text-slate-500">{description}</p>
      )}
      {cta && <div className="mt-3 pl-2">{cta}</div>}
    </div>
  );
}
