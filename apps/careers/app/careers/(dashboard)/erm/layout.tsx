'use client';

import type { ReactNode } from 'react';
import { ErmDashboardProvider } from '@/components/erm/ErmDashboardContext';

/**
 * ERM section layout. Hosts {@link ErmDashboardProvider} so every ERM
 * page (Home + the 13 deep-link surfaces) shares the same dashboard +
 * right-panel polling. Each page still wraps its content in
 * {@code DashboardLayout} for the sidebar/topbar chrome.
 */
export default function ErmLayout({ children }: { children: ReactNode }) {
  return <ErmDashboardProvider>{children}</ErmDashboardProvider>;
}
