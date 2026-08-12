'use client';

import type { ReactNode } from 'react';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ProtectedRoute from '@/components/ProtectedRoute';

/**
 * Intern segment layout. Every intern page lives inside:
 *   (dashboard) layout — mounts InternDashboardBoundary (provider + route
 *                        guard) once for every dashboard route, gated on
 *                        the caller having the INTERN role. See Wave-2 #10.
 *     ProtectedRoute (INTERN role gate — belt-and-suspenders vs. the
 *                    boundary's role check)
 *       DashboardLayout (sidebar + topbar + main column)
 *         {page content — wrapped in InternPageShell for PageHeader + Stepper}
 *
 * The provider broadcasts mode, stepper, module visibility, next-action,
 * and contacts to every child via {@code useInternDashboard()}.
 */
export default function InternSegmentLayout({
  children,
}: {
  children: ReactNode;
}) {
  return (
    <ProtectedRoute requiredRoles={['INTERN']}>
      <DashboardLayout>{children}</DashboardLayout>
    </ProtectedRoute>
  );
}
