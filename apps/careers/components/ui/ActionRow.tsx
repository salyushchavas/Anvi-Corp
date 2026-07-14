'use client';

import { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import Avatar from './Avatar';

export interface ActionRowProps {
  /** Either an avatar name (string) or a fully-rendered avatar/icon node. */
  avatar?: ReactNode | string;
  primary: ReactNode;
  secondary?: ReactNode;
  status?: ReactNode;
  meta?: ReactNode;
  actions?: ReactNode;
  href?: string;
  onClick?: () => void;
  className?: string;
}

export default function ActionRow({
  avatar,
  primary,
  secondary,
  status,
  meta,
  actions,
  href,
  onClick,
  className,
}: ActionRowProps) {
  const isClickable = !!(href || onClick);
  const Component: any = href ? 'a' : 'div';
  return (
    <Component
      href={href}
      onClick={onClick}
      className={cn(
        'group flex items-center gap-3 rounded-md border border-slate-200 bg-white px-4 py-3 transition-colors duration-150',
        isClickable && 'cursor-pointer hover:bg-slate-50 hover:border-slate-300',
        className,
      )}
    >
      {typeof avatar === 'string' ? <Avatar name={avatar} size="md" /> : avatar}
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-slate-900">{primary}</p>
        {secondary && (
          <p className="mt-0.5 truncate text-xs text-slate-500">{secondary}</p>
        )}
      </div>
      {status && <div className="shrink-0">{status}</div>}
      {meta && <div className="hidden shrink-0 text-xs text-slate-500 sm:block">{meta}</div>}
      {actions && <div className="flex shrink-0 items-center gap-1.5">{actions}</div>}
    </Component>
  );
}
