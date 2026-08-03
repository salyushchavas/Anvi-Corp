'use client';

import { Suspense, type ReactNode } from 'react';
import { ErmDashboardProvider } from '@/components/erm/ErmDashboardContext';

/**
 * ERM section layout. Hosts {@link ErmDashboardProvider} so every ERM
 * page (Home + the 13 deep-link surfaces) shares the same dashboard +
 * right-panel polling AND the sticky global month selector. Each page
 * still wraps its content in {@code DashboardLayout} for the
 * sidebar/topbar chrome.
 *
 * <p>The {@link Suspense} wrapper is required by Next.js because
 * {@code ErmDashboardProvider} calls {@code useSearchParams()} to seed
 * the sticky month from a URL {@code ?month} on first mount — that
 * hook must be inside a Suspense boundary during static prerender.
 * A {@code null} fallback keeps the pre-hydration paint blank rather
 * than flashing a partial layout; the full ERM shell renders on the
 * hydration pass.</p>
 */
export default function ErmLayout({ children }: { children: ReactNode }) {
  return (
    <Suspense fallback={null}>
      <ErmDashboardProvider>{children}</ErmDashboardProvider>
    </Suspense>
  );
}
