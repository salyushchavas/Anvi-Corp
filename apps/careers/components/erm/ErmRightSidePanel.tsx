'use client';

import Link from 'next/link';
import { Bell, Video } from 'lucide-react';
import { useErmDashboard } from './ErmDashboardContext';
import QuickActionsPanel from './QuickActionsPanel';

/**
 * Phase 1 — ERM-variant of the right-side panel. Distinct shape from
 * the intern's contact panel: quick actions + today's interview count
 * + unread notification bell shortcut.
 */
export default function ErmRightSidePanel() {
  const { rightPanel, loading } = useErmDashboard();

  return (
    <aside className="space-y-4">
      <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
          Quick actions
        </h3>
        <div className="mt-3">
          <QuickActionsPanel
            actions={rightPanel?.quickActions ?? null}
            loading={loading}
          />
        </div>
      </section>

      <Link
        href="/careers/erm/interviews"
        className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm text-slate-700 shadow-sm hover:bg-slate-50"
      >
        <Video className="h-4 w-4 text-slate-500" />
        <span className="flex-1">Interviews today</span>
        <span className="rounded-full bg-brand-100 px-2 py-0.5 text-[11px] font-semibold text-brand-800 tabular-nums">
          {rightPanel?.todayInterviewsCount ?? 0}
        </span>
      </Link>

      <Link
        href="/careers/intern/messages"
        className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm text-slate-700 shadow-sm hover:bg-slate-50"
      >
        <Bell className="h-4 w-4 text-slate-500" />
        <span className="flex-1">Notifications</span>
        {rightPanel && rightPanel.unreadNotifications > 0 && (
          <span className="rounded-full bg-red-600 px-2 py-0.5 text-[11px] font-semibold text-white tabular-nums">
            {rightPanel.unreadNotifications}
          </span>
        )}
      </Link>
    </aside>
  );
}
