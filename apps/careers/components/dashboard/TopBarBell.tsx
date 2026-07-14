'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { Bell } from 'lucide-react';
import api from '@/lib/api';

/**
 * Phase 7 notification bell. Polls /api/v1/notifications/unread-count
 * every 30s and shows a red badge with the unread count. Click links to
 * /careers/intern/messages — when other role surfaces wire their own
 * Messages page this can become role-aware.
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
      href="/careers/intern/messages"
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
