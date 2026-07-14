'use client';

import type { ReactNode } from 'react';
import ProtectedRoute from '@/components/ProtectedRoute';
import ManagerSidebar from '@/components/manager/ManagerSidebar';

/**
 * Manager Phase 0 — 2-column shell on lg+ (sidebar + main). RBAC: MANAGER
 * + SUPER_ADMIN. Mirrors the Trainer / Evaluator shell convention. The
 * shared root layout already wraps the app in AuthProvider +
 * IdleTimeoutProvider (Phase 8.8), so Manager inherits the logout button
 * + idle auto-logout behavior without per-role wiring.
 *
 * <p>Right-side panel + dashboard-polling provider are deferred to Phase 1
 * when the Executive Overview / KPIs land.</p>
 */
export default function ManagerLayout({ children }: { children: ReactNode }) {
  return (
    <ProtectedRoute requiredRoles={['MANAGER', 'SUPER_ADMIN']}>
      <div className="ds flex h-screen overflow-hidden bg-slate-50">
        <ManagerSidebar />
        <main className="flex-1 overflow-y-auto">{children}</main>
      </div>
    </ProtectedRoute>
  );
}
