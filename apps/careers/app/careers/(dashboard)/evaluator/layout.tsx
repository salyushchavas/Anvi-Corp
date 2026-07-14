'use client';

import type { ReactNode } from 'react';
import ProtectedRoute from '@/components/ProtectedRoute';
import EvaluatorSidebar from '@/components/evaluator/EvaluatorSidebar';
import EvaluatorRightSidePanel from '@/components/evaluator/EvaluatorRightSidePanel';
import { EvaluatorDashboardProvider } from '@/components/evaluator/EvaluatorDashboardContext';

/**
 * Evaluator Phase 1 — 3-column shell on xl: sidebar + main + right-side
 * panel. RBAC: EVALUATOR + SUPER_ADMIN. EvaluatorDashboardProvider polls
 * the dashboard + right-panel every 60s and shares one fetch with all
 * descendants so the right-side panel and Home stay in sync.
 */
export default function EvaluatorLayout({ children }: { children: ReactNode }) {
  return (
    <ProtectedRoute requiredRoles={['EVALUATOR', 'SUPER_ADMIN']}>
      <EvaluatorDashboardProvider>
        <div className="ds flex h-screen overflow-hidden bg-slate-50">
          <EvaluatorSidebar />
          <main className="flex-1 overflow-y-auto">{children}</main>
          <EvaluatorRightSidePanel />
        </div>
      </EvaluatorDashboardProvider>
    </ProtectedRoute>
  );
}
