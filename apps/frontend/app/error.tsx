'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import SiteFooter from '@/components/careers/SiteFooter';
import { BRAND } from '@/lib/careers/brand';

/**
 * App Router global error boundary. Branded shell, friendly copy, and a
 * "try again" button that re-mounts the segment. Renders for any unhandled
 * client- or server-side error thrown above the route group.
 */
export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // The digest is the only server-side trace the client receives; log it
    // so a support request from the user can be cross-referenced against
    // the backend logs.
    if (typeof window !== 'undefined') {
      // eslint-disable-next-line no-console
      console.error('Unhandled UI error', error?.digest, error);
    }
  }, [error]);

  return (
    <div className="flex min-h-screen flex-col bg-gray-50">
      <header className="bg-chrome-dark py-4 text-white">
        <div className="mx-auto flex max-w-3xl items-center gap-3 px-6">
          <Link href="/" className="flex items-center gap-2.5">
            <span className="flex h-9 w-9 items-center justify-center overflow-hidden rounded-md bg-white/10 p-1">
              <img
                src={BRAND.logoUrl}
                alt={BRAND.name}
                className="h-7 w-7 object-contain"
              />
            </span>
            <span className="flex flex-col leading-tight">
              <span className="text-[17px] font-extrabold uppercase tracking-wide text-white">
                {BRAND.name}
              </span>
              <span className="text-[10px] uppercase tracking-[0.15em] text-white/50">
                {BRAND.legalName}
              </span>
            </span>
          </Link>
        </div>
      </header>
      <div className="bg-accent hover:bg-accent-dark px-6 py-1" />

      <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col items-center justify-center px-6 py-16 text-center">
        <p className="text-7xl font-extrabold tracking-tight text-accent">500</p>
        <h1 className="mt-4 text-2xl font-bold text-gray-900">Something went wrong.</h1>
        <p className="mt-2 max-w-md text-sm text-gray-600">
          A short hiccup on our end. The team has been notified — please try
          again, and if the problem keeps happening, email{' '}
          <a
            href={`mailto:${BRAND.supportEmail}`}
            className="font-medium text-accent-dark hover:underline"
          >
            {BRAND.supportEmail}
          </a>
          .
        </p>
        {error?.digest && (
          <p className="mt-3 text-xs text-gray-400">Reference: {error.digest}</p>
        )}
        <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
          <button
            type="button"
            onClick={() => reset()}
            className="rounded-full bg-accent hover:bg-accent-dark px-5 py-2 text-sm font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg"
          >
            Try again
          </button>
          <Link
            href="/"
            className="rounded-full border border-gray-300 bg-white px-5 py-2 text-sm font-medium text-gray-700 transition hover:bg-gray-50"
          >
            Go home
          </Link>
        </div>
      </main>
      <SiteFooter />
    </div>
  );
}
