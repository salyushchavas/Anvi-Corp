'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { Bell } from 'lucide-react';
import api from '@/lib/careers/api';

/**
 * Notification bell. Polls /api/v1/notifications/unread-count every 30s
 * and shows a red badge with the unread count. Click navigates to the
 * shared /careers/messages route — role-agnostic, same page whether the
 * caller is Intern, ERM, Trainer, Manager, or Evaluator. Peer entry
 * point for the custom-layout roles is {@code StaffNotificationsButton}.
 */
export default function TopBarBell() {
  const [unread, setUnread] = useState(0);

  const load = useCallback(async () => {
    try {
      const res = await api.get<{ unread: number }>('/api/v1/notifications/unread-count');
      setUnread(res.data.unread ?? 0);
    } catch {
      // Silent — bell stays at 0
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
      aria-label="Notifications"
      className="relative rounded-md p-2 text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2"
    >
      <Bell className="h-4 w-4" strokeWidth={2} />
      {unread > 0 && (
        <span className="absolute -right-0.5 -top-0.5 inline-flex h-4 min-w-[16px] items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-semibold leading-none text-white">
          {unread > 99 ? '99+' : unread}
        </span>
      )}
    </Link>
  );
}
