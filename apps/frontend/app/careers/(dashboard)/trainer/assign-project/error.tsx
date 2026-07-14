'use client';

// Local error boundary for the project-assign wizard. Without this, any
// thrown render-error (a 403 surfaced as an unhandled rejection in a
// child component, a malformed slot-status payload, a template fetch
// throwing inside a Suspense child) escapes to the global app/error.tsx
// and white-screens the dashboard shell — what users saw as a generic
// "minified React #438" crash. The boundary keeps the trainer dashboard
// chrome intact and shows a friendly, actionable fallback.

import { useEffect } from 'react';
import Link from 'next/link';
import { ArrowLeft, RotateCw } from 'lucide-react';

export default function AssignProjectError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    if (typeof window !== 'undefined') {
      // eslint-disable-next-line no-console
      console.error('[assign-project] render error', error?.digest, error);
    }
  }, [error]);

  return (
    <div className="mx-auto max-w-2xl space-y-4 p-6">
      <p className="text-xs text-slate-500">
        <Link href="/careers/trainer" className="hover:text-slate-700">
          ← Trainer dashboard
        </Link>
      </p>
      <div className="rounded-lg border border-red-200 bg-red-50 p-5">
        <h1 className="text-lg font-semibold text-red-900">
          Couldn&apos;t load the project assignment wizard
        </h1>
        <p className="mt-2 text-sm text-red-800">
          Something went wrong while preparing this page. The most common
          cause is the intern not being in your roster, or a network blip
          loading the slot status. Try again, or head back to the trainer
          dashboard to pick a different intern.
        </p>
        {error?.message && (
          <p className="mt-3 break-words rounded-md border border-red-200 bg-white px-3 py-2 text-xs text-red-900">
            {error.message}
          </p>
        )}
        {error?.digest && (
          <p className="mt-2 text-[11px] text-red-700/80">
            Reference: {error.digest}
          </p>
        )}
        <div className="mt-4 flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => reset()}
            className="inline-flex items-center gap-1 rounded-md bg-red-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-red-800"
          >
            <RotateCw className="h-3.5 w-3.5" /> Try again
          </button>
          <Link
            href="/careers/trainer/active-interns"
            className="inline-flex items-center gap-1 rounded-md border border-red-300 bg-white px-3 py-1.5 text-xs font-semibold text-red-800 hover:bg-red-50"
          >
            <ArrowLeft className="h-3.5 w-3.5" /> Back to interns
          </Link>
        </div>
      </div>
    </div>
  );
}
