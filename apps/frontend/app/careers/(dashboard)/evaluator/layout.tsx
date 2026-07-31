'use client';

import type { ReactNode } from 'react';
import ProtectedRoute from '@/components/ProtectedRoute';
import EvaluatorSidebar from '@/components/evaluator/EvaluatorSidebar';
import EvaluatorTopBar from '@/components/evaluator/EvaluatorTopBar';
import { EvaluatorDashboardProvider } from '@/components/evaluator/EvaluatorDashboardContext';
import TodoPanel from '@/components/todos/TodoPanel';

/**
 * Evaluator — 3-column shell on xl: sidebar + main + right-side to-do
 * panel. RBAC: EVALUATOR + SUPER_ADMIN. The main column carries a
 * persistent {@link EvaluatorTopBar} so the sticky month selector is
 * always visible and reachable from every section — one control, one
 * global selection. The to-do panel is derived live from pending-action
 * queries and never rescopes on the month selection.
 */
export default function EvaluatorLayout({ children }: { children: ReactNode }) {
  return (
    <ProtectedRoute requiredRoles={['EVALUATOR', 'SUPER_ADMIN']}>
      <EvaluatorDashboardProvider>
        <div className="ds flex h-screen overflow-hidden bg-slate-50">
          <EvaluatorSidebar />
          <main className="flex flex-1 flex-col overflow-y-auto">
            <EvaluatorTopBar />
            <div className="flex-1">{children}</div>
          </main>
          <TodoPanel role="EVALUATOR" />
        </div>
      </EvaluatorDashboardProvider>
    </ProtectedRoute>
  );
}
