'use client';

import type { ReactNode } from 'react';
import ProtectedRoute from '@/components/ProtectedRoute';
import TrainerSidebar from '@/components/trainer/TrainerSidebar';
import TrainerRightSidePanel from '@/components/trainer/TrainerRightSidePanel';
import TrainerTopBar from '@/components/trainer/TrainerTopBar';
import { TrainerDashboardProvider } from '@/components/trainer/TrainerDashboardContext';

/**
 * Trainer — 3-column shell on xl: sidebar + main + right-side panel.
 * RBAC: TRAINER + SUPER_ADMIN. TrainerDashboardProvider runs a single
 * 60s poll for the Home + right-side panel data so consumers share one
 * fetch, and owns the sticky month state. TrainerTopBar renders above
 * every child page so the sticky month picker is always available.
 */
export default function TrainerLayout({ children }: { children: ReactNode }) {
  return (
    <ProtectedRoute requiredRoles={['TRAINER', 'SUPER_ADMIN']}>
      <TrainerDashboardProvider>
        <div className="ds flex h-screen overflow-hidden bg-slate-50">
          <TrainerSidebar />
          <div className="flex flex-1 flex-col overflow-hidden">
            <TrainerTopBar />
            <main className="flex-1 overflow-y-auto">{children}</main>
          </div>
          <TrainerRightSidePanel />
        </div>
      </TrainerDashboardProvider>
    </ProtectedRoute>
  );
}
