'use client';

import { HTMLAttributes, ReactNode, forwardRef } from 'react';
import { cn } from '@/lib/cn';
import { TONE_CLASSES, type Semantic } from '@/lib/status';

export type CardVariant = 'default' | 'interactive' | 'compact' | 'accent';

export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  variant?: CardVariant;
  accent?: Semantic;
  /** When true, hover elevation + cursor; used on tiles that link. */
  asLink?: boolean;
}

const PAD: Record<CardVariant, string> = {
  default: 'p-6',
  interactive: 'p-6',
  compact: 'p-4',
  accent: 'p-6',
};

export const Card = forwardRef<HTMLDivElement, CardProps>(function Card(
  { variant = 'default', accent = 'info', asLink, className, children, ...rest },
  ref,
) {
  const isInteractive = variant === 'interactive' || asLink;
  const accentBorder =
    variant === 'accent' ? `border-l-4 ${TONE_CLASSES[accent].border}` : '';
  return (
    <div
      ref={ref}
      className={cn(
        'rounded-lg border border-slate-200 bg-white shadow-ds-sm transition-shadow duration-150',
        PAD[variant],
        isInteractive && 'cursor-pointer hover:shadow-ds-md hover:border-slate-300',
        accentBorder,
        className,
      )}
      {...rest}
    >
      {children}
    </div>
  );
});

export interface CardHeaderProps {
  title?: ReactNode;
  subtitle?: ReactNode;
  eyebrow?: ReactNode;
  actions?: ReactNode;
  className?: string;
}

export function CardHeader({ title, subtitle, eyebrow, actions, className }: CardHeaderProps) {
  return (
    <header className={cn('mb-4 flex items-start justify-between gap-3', className)}>
      <div className="min-w-0">
        {eyebrow && (
          <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-slate-500">
            {eyebrow}
          </p>
        )}
        {title && (
          <h3 className="text-base font-semibold text-slate-900">{title}</h3>
        )}
        {subtitle && <p className="mt-0.5 text-sm text-slate-600">{subtitle}</p>}
      </div>
      {actions && <div className="shrink-0">{actions}</div>}
    </header>
  );
}

export function CardFooter({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return <footer className={cn('mt-4 flex items-center justify-end gap-2', className)}>{children}</footer>;
}
