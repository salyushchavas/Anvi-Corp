'use client';

import type { ReactNode } from 'react';
import { useAuth } from '@/lib/careers/auth-context';
import { InternDashboardProvider } from './InternDashboardContext';
import InternModuleRouteGuard from './InternModuleRouteGuard';

/**
 * Mount the intern dashboard provider (+ its route guard) once for the whole
 * (dashboard) route group, but ONLY when the caller is an intern. Previously
 * the provider was mounted inside the intern segment layout only; that meant
 * any dashboard route outside {@code /careers/intern/*} — notably the shared
 * {@code /careers/messages} route — rendered with a null module map, so the
 * role-aware sidebar silently ungated every intern link and let the intern
 * click through to modules that should have been hidden. Wave-2 #10.
 *
 * <p>Non-intern users see no change: the provider isn't mounted, no extra
 * request fires, and the sidebar's {@code useInternDashboardOptional()} keeps
 * returning null (staff sidebars don't use module gating).</p>
 */
export default function InternDashboardBoundary({
  children,
}: {
  children: ReactNode;
}) {
  const { user } = useAuth();
  const isIntern = !!user?.roles?.includes('INTERN');

  if (!isIntern) return <>{children}</>;

  return (
    <InternDashboardProvider>
      <InternModuleRouteGuard />
      {children}
    </InternDashboardProvider>
  );
}
