'use client';

import type { ReactNode } from 'react';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';

/**
 * Wraps the shared {@code /careers/messages} route in {@code DashboardLayout}
 * so every role (Intern, ERM, Trainer, Manager, Evaluator) gets the same
 * consistent chrome — sidebar, top bar with bell + mail — regardless of
 * which sidebar Notifications button they clicked to get here. The role-aware
 * {@code DashboardSidebar} adapts nav to the caller's roles.
 *
 * <p>No {@code requiredRoles} — the endpoint the page hits is user-scoped
 * server-side, so any authenticated user may view their own notifications.
 * {@code ProtectedRoute} still enforces authentication.</p>
 */
export default function MessagesLayout({ children }: { children: ReactNode }) {
  return (
    <ProtectedRoute>
      <DashboardLayout>{children}</DashboardLayout>
    </ProtectedRoute>
  );
}
