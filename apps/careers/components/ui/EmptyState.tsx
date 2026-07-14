'use client';

import { ComponentType, ReactNode } from 'react';
import { Inbox, type LucideIcon } from 'lucide-react';
import { cn } from '@/lib/cn';

export interface EmptyStateProps {
  icon?: LucideIcon | ComponentType<{ className?: string }>;
  title: string;
  description?: ReactNode;
  primaryAction?: ReactNode;
  className?: string;
}

export default function EmptyState({
  icon: Icon = Inbox,
  title,
  description,
  primaryAction,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-200 bg-white py-16 px-6 text-center',
        className,
      )}
    >
      <Icon className="mb-4 h-12 w-12 text-slate-300" strokeWidth={1.5} />
      <p className="text-base font-medium text-slate-700">{title}</p>
      {description && (
        <p className="mt-1.5 max-w-md text-sm text-slate-500">{description}</p>
      )}
      {primaryAction && <div className="mt-5">{primaryAction}</div>}
    </div>
  );
}
