'use client';

import { CheckCircle2, Info, XCircle } from 'lucide-react';
import hot from 'react-hot-toast';
import { ReactNode } from 'react';

/**
 * Thin wrapper around react-hot-toast. Three variants — success / error /
 * info — with consistent iconography. Auto-dismiss is the library default
 * (4s); hover pauses dismissal; up to 3 visible at once is controlled at
 * the Toaster root in app/layout.tsx.
 */
export const toast = {
  success(message: ReactNode) {
    return hot.custom(
      () => (
        <div className="pointer-events-auto flex items-start gap-2.5 rounded-md border border-green-200 bg-white px-3.5 py-2.5 shadow-ds-md">
          <CheckCircle2 className="mt-0.5 h-4 w-4 text-green-600" strokeWidth={2.5} />
          <p className="text-sm text-slate-800">{message}</p>
        </div>
      ),
      { duration: 4000 },
    );
  },
  error(message: ReactNode) {
    return hot.custom(
      () => (
        <div className="pointer-events-auto flex items-start gap-2.5 rounded-md border border-red-200 bg-white px-3.5 py-2.5 shadow-ds-md">
          <XCircle className="mt-0.5 h-4 w-4 text-red-600" strokeWidth={2.5} />
          <p className="text-sm text-slate-800">{message}</p>
        </div>
      ),
      { duration: 4500 },
    );
  },
  info(message: ReactNode) {
    return hot.custom(
      () => (
        <div className="pointer-events-auto flex items-start gap-2.5 rounded-md border border-slate-300 bg-white px-3.5 py-2.5 shadow-ds-md">
          <Info className="mt-0.5 h-4 w-4 text-slate-500" strokeWidth={2.5} />
          <p className="text-sm text-slate-800">{message}</p>
        </div>
      ),
      { duration: 4000 },
    );
  },
};
