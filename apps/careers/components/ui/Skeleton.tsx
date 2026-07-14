'use client';

import { cn } from '@/lib/cn';

export interface SkeletonProps {
  className?: string;
  /** Shortcut for a circle skeleton (avatar slot). */
  circle?: boolean;
}

export default function Skeleton({ className, circle }: SkeletonProps) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'block animate-pulse bg-slate-200/80',
        circle ? 'rounded-full' : 'rounded',
        className,
      )}
    />
  );
}

/** Convenience: full Card-shaped skeleton matching p-6 cards. */
export function CardSkeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-ds-sm">
      <Skeleton className="mb-3 h-4 w-1/3" />
      {Array.from({ length: lines }).map((_, i) => (
        <Skeleton key={i} className={cn('mb-2 h-3', i === lines - 1 ? 'w-2/3' : 'w-full')} />
      ))}
    </div>
  );
}

/** Convenience: a row of N tiles. */
export function TilesSkeleton({ count = 3 }: { count?: number }) {
  return (
    <div className={cn('grid gap-3', count >= 3 ? 'sm:grid-cols-3' : 'sm:grid-cols-2')}>
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="h-24 animate-pulse rounded-lg border border-slate-200 bg-white" />
      ))}
    </div>
  );
}
