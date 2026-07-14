'use client';

import type { ReactNode } from 'react';
import ProtectedRoute from '@/components/ProtectedRoute';
import TrainerSidebar from '@/components/trainer/TrainerSidebar';
import TrainerRightSidePanel from '@/components/trainer/TrainerRightSidePanel';
import { TrainerDashboardProvider } from '@/components/trainer/TrainerDashboardContext';

/**
 * Trainer Phase 1 — 3-column shell on xl: sidebar + main + right-side
 * panel. RBAC: TRAINER + SUPER_ADMIN. TrainerDashboardProvider runs a
 * single 60s poll for the Home + right-side panel data so consumers
 * share one fetch.
 */
export default function TrainerLayout({ children }: { children: ReactNode }) {
  return (
    <ProtectedRoute requiredRoles={['TRAINER', 'SUPER_ADMIN']}>
      <TrainerDashboardProvider>
        <div className="ds flex h-screen overflow-hidden bg-slate-50">
          <TrainerSidebar />
          <main className="flex-1 overflow-y-auto">{children}</main>
          <TrainerRightSidePanel />
        </div>
      </TrainerDashboardProvider>
    </ProtectedRoute>
  );
}
