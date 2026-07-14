'use client';

import { ReactNode, MouseEvent } from 'react';
import toast from 'react-hot-toast';

type Variant = 'primary' | 'secondary' | 'danger' | 'ghost';
type Size = 'sm' | 'md';

interface Props {
  children: ReactNode;
  variant?: Variant;
  size?: Size;
  className?: string;
  /** Custom toast message; defaults to "Coming soon — this feature is being built". */
  message?: string;
  /** Optional preceding icon node. */
  icon?: ReactNode;
  title?: string;
  ariaLabel?: string;
}

const VARIANT_CLASS: Record<Variant, string> = {
  primary: 'bg-accent text-white hover:bg-accent-dark',
  secondary:
    'bg-white border border-gray-300 text-gray-700 hover:bg-gray-50',
  danger: 'bg-red-600 text-white hover:bg-red-700',
  ghost: 'text-gray-600 hover:bg-gray-100',
};

const SIZE_CLASS: Record<Size, string> = {
  sm: 'text-xs px-3 py-1.5 rounded-md',
  md: 'text-sm px-4 py-2 rounded-md',
};

export default function MockButton({
  children,
  variant = 'primary',
  size = 'sm',
  className,
  message = 'Coming soon — this feature is being built',
  icon,
  title,
  ariaLabel,
}: Props) {
  function onClick(e: MouseEvent<HTMLButtonElement>) {
    e.stopPropagation();
    toast(message, { icon: '✨' });
  }
  return (
    <button
      type="button"
      onClick={onClick}
      title={title}
      aria-label={ariaLabel}
      className={
        'inline-flex items-center gap-1.5 font-medium transition-colors ' +
        VARIANT_CLASS[variant] +
        ' ' +
        SIZE_CLASS[size] +
        (className ? ' ' + className : '')
      }
    >
      {icon}
      {children}
    </button>
  );
}
