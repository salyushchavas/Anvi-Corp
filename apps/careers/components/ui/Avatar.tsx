'use client';

import { cn } from '@/lib/cn';

export interface AvatarProps {
  name?: string | null;
  src?: string | null;
  size?: 'xs' | 'sm' | 'md' | 'lg';
  className?: string;
}

const SIZES = {
  xs: 'h-6 w-6 text-[10px]',
  sm: 'h-8 w-8 text-xs',
  md: 'h-9 w-9 text-sm',
  lg: 'h-12 w-12 text-base',
};

function initials(name?: string | null): string {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

export default function Avatar({ name, src, size = 'sm', className }: AvatarProps) {
  if (src) {
    return (
      // eslint-disable-next-line @next/next/no-img-element
      <img
        src={src}
        alt={name ?? 'Avatar'}
        className={cn('shrink-0 rounded-full object-cover', SIZES[size], className)}
      />
    );
  }
  return (
    <span
      aria-hidden={!name}
      className={cn(
        'inline-flex shrink-0 select-none items-center justify-center rounded-full bg-slate-200 font-semibold text-slate-700',
        SIZES[size],
        className,
      )}
    >
      {initials(name)}
    </span>
  );
}
