'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { Bell } from 'lucide-react';
import api from '@/lib/careers/api';

/**
 * Staff notifications entry point for role dashboards on the custom
 * layout stack (Trainer / Manager / Evaluator) — peer of the
 * {@code TopBarBell} that Intern/ERM see in their shared top bar.
 *
 * <p>Polls {@code /api/v1/notifications/unread-count} at the same 30s
 * cadence as {@link TopBarBell} — since only ONE of the two is mounted
 * per role dashboard (custom layouts don't render {@code TopBarBell}),
 * there's no double-polling to worry about.</p>
 *
 * <p>Click navigates to the shared {@code /careers/messages} route
 * (role-agnostic notifications center). Renders unconditionally: every
 * consumer sidebar is already role-gated by {@code ProtectedRoute}, and
 * every authenticated user can read their own notifications feed.</p>
 */
export default function StaffNotificationsButton() {
  const [unread, setUnread] = useState(0);

  const load = useCallback(async () => {
    try {
      const res = await api.get<{ unread: number }>('/api/v1/notifications/unread-count');
      setUnread(res.data.unread ?? 0);
    } catch {
      // Silent — button stays at 0 rather than surfacing a poll failure.
    }
  }, []);

  useEffect(() => {
    void load();
    const t = window.setInterval(() => void load(), 30_000);
    return () => window.clearInterval(t);
  }, [load]);

  return (
    <Link
      href="/careers/messages"
      className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
    >
      <span className="relative">
        <Bell className="h-4 w-4" strokeWidth={2} />
        {unread > 0 && (
          <span className="absolute -right-1.5 -top-1.5 inline-flex h-4 min-w-[16px] items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-semibold leading-none text-white">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </span>
      <span className="flex-1 text-left">Messages</span>
    </Link>
  );
}
