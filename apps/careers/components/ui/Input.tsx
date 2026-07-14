'use client';

import { InputHTMLAttributes, TextareaHTMLAttributes, forwardRef, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  invalid?: boolean;
  leftIcon?: ReactNode;
  rightIcon?: ReactNode;
}

const BASE_INPUT =
  'block w-full rounded-md border bg-white px-3 text-sm text-slate-900 placeholder:text-slate-400 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:opacity-70';

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { className, invalid, leftIcon, rightIcon, ...rest },
  ref,
) {
  const borders = invalid ? 'border-red-300' : 'border-slate-300 hover:border-slate-400';
  if (leftIcon || rightIcon) {
    return (
      <div className="relative">
        {leftIcon && (
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-slate-400">
            {leftIcon}
          </span>
        )}
        <input
          ref={ref}
          className={cn(BASE_INPUT, 'h-10', borders, leftIcon ? 'pl-9' : null, rightIcon ? 'pr-9' : null, className)}
          {...rest}
        />
        {rightIcon && (
          <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-slate-400">
            {rightIcon}
          </span>
        )}
      </div>
    );
  }
  return (
    <input
      ref={ref}
      className={cn(BASE_INPUT, 'h-10', borders, className)}
      {...rest}
    />
  );
});

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  invalid?: boolean;
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { className, invalid, rows = 4, ...rest },
  ref,
) {
  const borders = invalid ? 'border-red-300' : 'border-slate-300 hover:border-slate-400';
  return (
    <textarea
      ref={ref}
      rows={rows}
      className={cn(BASE_INPUT, 'py-2 leading-relaxed', borders, className)}
      {...rest}
    />
  );
});

export interface LabelProps {
  htmlFor?: string;
  required?: boolean;
  hint?: ReactNode;
  className?: string;
  children: ReactNode;
}

export function Label({ htmlFor, required, hint, className, children }: LabelProps) {
  return (
    <div className={cn('mb-1.5 flex items-baseline justify-between gap-2', className)}>
      <label htmlFor={htmlFor} className="text-sm font-medium text-slate-700">
        {children}
        {required && <span className="ml-0.5 text-red-600">*</span>}
      </label>
      {hint && <span className="text-xs text-slate-500">{hint}</span>}
    </div>
  );
}

export function FieldError({ children }: { children: ReactNode }) {
  if (!children) return null;
  return (
    <p role="alert" className="mt-1 text-xs text-red-700">
      {children}
    </p>
  );
}
