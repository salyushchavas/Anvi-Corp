'use client';

import Link from 'next/link';
import { Bell, CalendarDays } from 'lucide-react';
import { useTrainerDashboard } from './TrainerDashboardContext';
import type { Alert, QuickAction } from './types';

/**
 * Trainer Phase 1 — right-side panel: 5 doc §4 quick actions + 2
 * alerts + today's meeting count + unread notification count. Renders
 * Phase 2/3 destinations as "Coming soon" until those phases ship.
 */
export default function TrainerRightSidePanel() {
  const { rightPanel, rightPanelError } = useTrainerDashboard();

  if (rightPanelError) {
    return (
      <aside className="hidden h-full w-64 shrink-0 border-l border-slate-200 bg-white p-4 xl:flex xl:flex-col">
        <p className="text-xs text-red-700">{rightPanelError}</p>
      </aside>
    );
  }
  if (!rightPanel) {
    return (
      <aside className="hidden h-full w-64 shrink-0 border-l border-slate-200 bg-white xl:flex xl:flex-col">
        <div className="border-b border-slate-100 px-4 py-3">
          <div className="h-3 w-32 animate-pulse rounded bg-slate-100" />
        </div>
        <div className="space-y-2 p-4">
          {Array.from({ length: 5 }).map((_, i) => (
            <div
              key={i}
              className="h-9 w-full animate-pulse rounded bg-slate-100"
            />
          ))}
        </div>
      </aside>
    );
  }

  return (
    <aside className="hidden h-full w-64 shrink-0 flex-col border-l border-slate-200 bg-white xl:flex">
      <header className="border-b border-slate-100 px-4 py-3">
        <h3 className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
          Quick actions
        </h3>
      </header>
      <ul className="space-y-1 p-2">
        {rightPanel.quickActions.map((qa) => (
          <QuickActionRow key={qa.key} action={qa} />
        ))}
      </ul>

      <header className="border-y border-slate-100 px-4 py-3">
        <h3 className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
          Alerts
        </h3>
      </header>
      <ul className="space-y-1 p-2">
        {rightPanel.alerts.map((a) => (
          <AlertRow key={a.key} alert={a} />
        ))}
      </ul>

      <div className="mt-auto border-t border-slate-100 p-3 text-xs text-slate-600">
        <p className="flex items-center gap-2">
          <CalendarDays className="h-3.5 w-3.5 text-slate-400" strokeWidth={2} />
          <Link href="/careers/trainer" className="hover:text-slate-900">
            Today's meetings:{' '}
            <strong>{rightPanel.todayMeetingsCount}</strong>
          </Link>
        </p>
        <p className="mt-2 flex items-center gap-2">
          <Bell className="h-3.5 w-3.5 text-slate-400" strokeWidth={2} />
          Unread: <strong>{rightPanel.unreadNotifications}</strong>
        </p>
      </div>
    </aside>
  );
}

function QuickActionRow({ action }: { action: QuickAction }) {
  const tip = action.comingSoon
    ? 'Wired in Trainer Phase 2 or 3'
    : undefined;
  return (
    <li>
      <Link
        href={action.href}
        title={tip}
        className={
          'flex items-center justify-between rounded-md px-3 py-2 text-sm ' +
          (action.comingSoon
            ? 'text-slate-500 hover:bg-slate-50'
            : 'text-slate-800 hover:bg-slate-50')
        }
      >
        <span className="truncate">{action.label}</span>
        {action.badge > 0 && (
          <span
            className={
              'rounded-full px-1.5 py-0.5 text-[10px] font-semibold ' +
              (action.badge >= 5
                ? 'bg-red-100 text-red-700'
                : 'bg-slate-100 text-slate-600')
            }
          >
            {action.badge}
          </span>
        )}
      </Link>
    </li>
  );
}

function AlertRow({ alert }: { alert: Alert }) {
  const dotClass =
    alert.severity === 'URGENT'
      ? 'bg-red-500'
      : alert.severity === 'WARN'
        ? 'bg-amber-500'
        : 'bg-slate-500';
  return (
    <li className="flex items-center justify-between px-3 py-1.5 text-xs">
      <span className="flex items-center gap-2">
        <span className={'h-1.5 w-1.5 rounded-full ' + dotClass} />
        <span className="text-slate-700">{alert.label}</span>
      </span>
      <span className="tabular-nums text-slate-600">{alert.count}</span>
    </li>
  );
}
