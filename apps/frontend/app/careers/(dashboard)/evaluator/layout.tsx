'use client';

import type { ReactNode } from 'react';
import ProtectedRoute from '@/components/ProtectedRoute';
import EvaluatorSidebar from '@/components/evaluator/EvaluatorSidebar';
import { EvaluatorDashboardProvider } from '@/components/evaluator/EvaluatorDashboardContext';
import TodoPanel from '@/components/todos/TodoPanel';

/**
 * Evaluator — 3-column shell on xl: sidebar + main + right-side to-do
 * panel. RBAC: EVALUATOR + SUPER_ADMIN. The to-do panel replaces the
 * previous quick-actions panel and is derived live from the existing
 * pending-action queries (PENDING_VIVA projects, SCHEDULED/IN_PROGRESS
 * evaluations, pending acknowledgments, overdue evaluations, in-flight
 * I-983s). EvaluatorDashboardProvider is retained so descendant Home /
 * evaluee-detail pages keep sharing one fetch.
 */
export default function EvaluatorLayout({ children }: { children: ReactNode }) {
  return (
    <ProtectedRoute requiredRoles={['EVALUATOR', 'SUPER_ADMIN']}>
      <EvaluatorDashboardProvider>
        <div className="ds flex h-screen overflow-hidden bg-slate-50">
          <EvaluatorSidebar />
          <main className="flex-1 overflow-y-auto">{children}</main>
          <TodoPanel role="EVALUATOR" />
        </div>
      </EvaluatorDashboardProvider>
    </ProtectedRoute>
  );
}
