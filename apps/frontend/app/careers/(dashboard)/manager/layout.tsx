'use client';

import type { ReactNode } from 'react';
import ProtectedRoute from '@/components/ProtectedRoute';
import ManagerSidebar from '@/components/manager/ManagerSidebar';
import TodoPanel from '@/components/todos/TodoPanel';

/**
 * Manager — 3-column shell on xl: sidebar + main + right-side to-do panel.
 * RBAC: MANAGER + SUPER_ADMIN. The to-do panel is derived live from the
 * existing pending-action queries (hire approvals, VERIFIED timesheets,
 * VERIFIED weekly reports, pending recording approvals) so items
 * auto-drop when the underlying work is done.
 */
export default function ManagerLayout({ children }: { children: ReactNode }) {
  return (
    <ProtectedRoute requiredRoles={['MANAGER', 'SUPER_ADMIN']}>
      <div className="ds flex h-screen overflow-hidden bg-slate-50">
        <ManagerSidebar />
        <main className="flex-1 overflow-y-auto">{children}</main>
        <TodoPanel role="MANAGER" />
      </div>
    </ProtectedRoute>
  );
}
