import type { ReactNode } from 'react';

interface Props {
  label: string;
  htmlFor?: string;
  required?: boolean;
  error?: string | null;
  helper?: string;
  children: ReactNode;
  className?: string;
}

export default function FormField({
  label,
  htmlFor,
  required,
  error,
  helper,
  children,
  className,
}: Props) {
  return (
    <div className={className ?? 'mb-5'}>
      <label
        htmlFor={htmlFor}
        className="mb-1.5 block text-sm font-medium text-gray-900"
      >
        {label}
        {required && <span className="ml-0.5 text-red-500">*</span>}
      </label>
      {children}
      {error ? (
        <p className="mt-1 text-xs text-red-600">{error}</p>
      ) : helper ? (
        <p className="mt-1 text-xs text-gray-500">{helper}</p>
      ) : null}
    </div>
  );
}

/** Shared input className. Append " border-red-300 focus:ring-red-500 focus:border-red-500" on error. */
export const INPUT_CLASS =
  'w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm focus:border-primary-700 focus:outline-none focus:ring-1 focus:ring-primary-700 disabled:bg-gray-50 disabled:text-gray-500 disabled:cursor-not-allowed';

export const INPUT_CLASS_ERROR =
  'w-full rounded-md border border-red-300 bg-white px-3 py-2 text-sm focus:border-red-500 focus:outline-none focus:ring-1 focus:ring-red-500';

export function inputClass(hasError?: boolean): string {
  return hasError ? INPUT_CLASS_ERROR : INPUT_CLASS;
}
