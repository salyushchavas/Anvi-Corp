'use client';

import Link from 'next/link';
import { ChevronRight } from 'lucide-react';
import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface Crumb {
  label: string;
  href?: string;
}

export interface PageHeaderProps {
  title: ReactNode;
  subtitle?: ReactNode;
  breadcrumb?: Crumb[];
  /** Primary action (typically a Button). Rendered top-right on desktop. */
  primaryAction?: ReactNode;
  /** Secondary actions (any nodes). Rendered next to primary. */
  secondaryActions?: ReactNode;
  /** Right-side decoration (e.g. Read-only pill). */
  meta?: ReactNode;
  className?: string;
}

export default function PageHeader({
  title,
  subtitle,
  breadcrumb,
  primaryAction,
  secondaryActions,
  meta,
  className,
}: PageHeaderProps) {
  return (
    <header className={cn('mb-6 border-b border-slate-200 pb-6', className)}>
      {breadcrumb && breadcrumb.length > 0 && (
        <nav className="mb-3 flex items-center gap-1 text-xs text-slate-500" aria-label="Breadcrumb">
          {breadcrumb.map((c, i) => {
            const last = i === breadcrumb.length - 1;
            return (
              <span key={i} className="flex items-center gap-1">
                {c.href && !last ? (
                  <Link href={c.href} className="hover:text-slate-700">
                    {c.label}
                  </Link>
                ) : (
                  <span className={last ? 'font-medium text-slate-700' : ''}>{c.label}</span>
                )}
                {!last && <ChevronRight className="h-3 w-3 text-slate-400" strokeWidth={2} />}
              </span>
            );
          })}
        </nav>
      )}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 flex-1">
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">{title}</h1>
          {subtitle && <p className="mt-1.5 text-sm text-slate-600">{subtitle}</p>}
          {meta && <div className="mt-2 flex flex-wrap items-center gap-2">{meta}</div>}
        </div>
        {(primaryAction || secondaryActions) && (
          <div className="flex flex-wrap items-center gap-2">
            {secondaryActions}
            {primaryAction}
          </div>
        )}
      </div>
    </header>
  );
}
