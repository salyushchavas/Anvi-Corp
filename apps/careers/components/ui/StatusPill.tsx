'use client';

import { Check, Circle, Clock, Hourglass, XCircle, type LucideIcon } from 'lucide-react';
import { ComponentType, ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { statusLabel, statusSemantic, TONE_CLASSES, type Semantic } from '@/lib/status';

export interface StatusPillProps {
  /** Status string — mapped via STATUS_SEMANTIC. */
  status: string | null | undefined;
  /** Override the default humanized label. */
  label?: string;
  /** Override the default semantic-tone icon. */
  icon?: LucideIcon | ComponentType<{ className?: string }>;
  size?: 'sm' | 'md';
  /** Tone override — bypasses the status map. */
  tone?: Semantic;
  className?: string;
}

const DEFAULT_ICON: Record<Semantic, LucideIcon> = {
  success: Check,
  info: Clock,
  warning: Hourglass,
  danger: XCircle,
  neutral: Circle,
};

const SIZES = {
  sm: 'text-xs px-2 py-0.5 gap-1',
  md: 'text-sm px-2.5 py-1 gap-1.5',
};

export default function StatusPill({
  status,
  label,
  icon,
  size = 'sm',
  tone,
  className,
}: StatusPillProps) {
  const semantic = tone ?? statusSemantic(status);
  const Icon = icon ?? DEFAULT_ICON[semantic];
  const tones = TONE_CLASSES[semantic];
  const text = label ?? statusLabel(status);
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full border font-medium',
        SIZES[size],
        tones.bg,
        tones.text,
        tones.border,
        className,
      )}
    >
      <Icon className={cn('h-3 w-3', tones.icon)} />
      <span>{text}</span>
    </span>
  );
}
