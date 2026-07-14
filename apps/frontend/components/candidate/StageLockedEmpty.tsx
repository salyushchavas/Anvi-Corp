'use client';

import Link from 'next/link';
import { Lock, type LucideIcon } from 'lucide-react';
import { ComponentType, ReactNode } from 'react';

interface Props {
  /** Lucide-style icon component (defaults to Lock). */
  icon?: LucideIcon | ComponentType<{ className?: string; strokeWidth?: number }>;
  title: string;
  body: ReactNode;
  /** Secondary CTA route + label. Both required to render the button. */
  ctaHref?: string;
  ctaLabel?: string;
}

/**
 * Stage-aware empty state shown on cycle pages (Projects / Weekly Materials /
 * Weekly Reports / Evaluations) when the caller's role is still APPLICANT
 * — they shouldn't be bounced to the dashboard, but the page also has no
 * real content for them yet. Centered icon + title + body + secondary CTA
 * back to onboarding so the user knows what unlocks it.
 *
 * Deliberately not in components/ui/ — this is a domain-specific arrangement
 * of EmptyState pieces, not a reusable primitive.
 */
export default function StageLockedEmpty({
  icon: Icon = Lock,
  title,
  body,
  ctaHref,
  ctaLabel,
}: Props) {
  return (
    <div className="mx-auto flex max-w-md flex-col items-center justify-center rounded-lg border border-dashed border-slate-200 bg-white py-16 px-6 text-center">
      <Icon className="mb-4 h-12 w-12 text-slate-300" strokeWidth={1.5} />
      <p className="text-base font-medium text-slate-800">{title}</p>
      <p className="mt-1.5 text-sm text-slate-500">{body}</p>
      {ctaHref && ctaLabel && (
        <Link
          href={ctaHref}
          className="mt-5 inline-flex items-center justify-center rounded-md border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2"
        >
          {ctaLabel}
        </Link>
      )}
    </div>
  );
}
