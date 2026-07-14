'use client';

import { ReactNode, useState } from 'react';
import {
  AlertCircle,
  CheckCircle2,
  Info,
  X,
  XCircle,
  type LucideIcon,
} from 'lucide-react';
import { cn } from '@/lib/cn';
import { TONE_CLASSES, type Semantic } from '@/lib/status';

export interface BannerProps {
  variant?: Extract<Semantic, 'info' | 'success' | 'warning' | 'danger'>;
  title: ReactNode;
  description?: ReactNode;
  icon?: LucideIcon;
  action?: ReactNode;
  dismissible?: boolean;
  className?: string;
}

const ICONS: Record<NonNullable<BannerProps['variant']>, LucideIcon> = {
  info: Info,
  success: CheckCircle2,
  warning: AlertCircle,
  danger: XCircle,
};

const ACCENT_BORDER: Record<NonNullable<BannerProps['variant']>, string> = {
  info: 'border-l-blue-500',
  success: 'border-l-emerald-500',
  warning: 'border-l-amber-500',
  danger: 'border-l-red-500',
};

export default function Banner({
  variant = 'info',
  title,
  description,
  icon,
  action,
  dismissible,
  className,
}: BannerProps) {
  const [hidden, setHidden] = useState(false);
  const Icon = icon ?? ICONS[variant];
  const tones = TONE_CLASSES[variant];
  if (hidden) return null;
  return (
    <div
      role={variant === 'danger' ? 'alert' : 'status'}
      className={cn(
        'flex items-start gap-3 rounded-r-md border-l-4 p-4',
        ACCENT_BORDER[variant],
        tones.bg,
        className,
      )}
    >
      <Icon className={cn('mt-0.5 h-4 w-4 shrink-0', tones.icon)} strokeWidth={2.5} />
      <div className="min-w-0 flex-1">
        <p className={cn('text-sm font-medium', tones.text)}>{title}</p>
        {description && (
          <p className={cn('mt-0.5 text-xs', tones.text, 'opacity-90')}>{description}</p>
        )}
      </div>
      {action && <div className="shrink-0">{action}</div>}
      {dismissible && (
        <button
          type="button"
          onClick={() => setHidden(true)}
          aria-label="Dismiss"
          className="shrink-0 rounded p-0.5 text-slate-500 hover:bg-slate-100"
        >
          <X className="h-3.5 w-3.5" strokeWidth={2.5} />
        </button>
      )}
    </div>
  );
}
