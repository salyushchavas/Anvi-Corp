'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Bell, CheckCheck, ChevronRight } from 'lucide-react';
import api from '@/lib/api';
import InternPageShell from '@/components/intern/InternPageShell';
import { toast } from '@/components/ui/Toast';

interface UserNotification {
  id: string;
  eventType: string;
  title: string;
  body: string;
  actionUrl?: string | null;
  readAt?: string | null;
  createdAt: string;
}

interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

type Tab = 'all' | 'unread';

export default function InternMessagesPage() {
  const [tab, setTab] = useState<Tab>('all');
  const [items, setItems] = useState<UserNotification[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get<Page<UserNotification>>(
        `/api/v1/notifications?page=0&size=100&unread=${tab === 'unread'}`,
      );
      setItems(res.data.content ?? []);
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Failed to load messages');
    } finally {
      setLoading(false);
    }
  }, [tab]);

  useEffect(() => { void load(); }, [load]);

  async function markAllRead() {
    try {
      await api.post('/api/v1/notifications/mark-all-read');
      await load();
      toast.success('All messages marked as read.');
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Mark-all-read failed');
    }
  }

  async function openRow(n: UserNotification) {
    try {
      if (!n.readAt) await api.post(`/api/v1/notifications/${n.id}/read`);
    } finally {
      if (n.actionUrl) window.location.href = n.actionUrl;
      else await load();
    }
  }

  const grouped = useMemo(() => groupByDate(items), [items]);

  return (
    <InternPageShell title="Messages" subtitle="Your activity feed across the platform.">
      <div className="mb-4 flex items-center justify-between gap-3">
        <div role="tablist" className="inline-flex rounded-lg border border-slate-200 bg-white p-1">
          <TabButton active={tab === 'all'} onClick={() => setTab('all')}>All</TabButton>
          <TabButton active={tab === 'unread'} onClick={() => setTab('unread')}>Unread</TabButton>
        </div>
        <button
          type="button"
          onClick={markAllRead}
          className="inline-flex items-center gap-1 rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50"
        >
          <CheckCheck className="h-3 w-3" /> Mark all read
        </button>
      </div>

      {loading && <div className="h-32 animate-pulse rounded-lg bg-slate-50" aria-hidden />}
      {err && <p className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800">{err}</p>}
      {!loading && !err && items.length === 0 && (
        <p className="rounded-md border border-dashed border-slate-300 bg-white p-10 text-center text-sm text-slate-500">
          <Bell className="mx-auto mb-2 h-6 w-6 text-slate-300" />
          You're all caught up.
        </p>
      )}

      {!loading && grouped.length > 0 && (
        <div className="space-y-6">
          {grouped.map(({ label, rows }) => (
            <section key={label}>
              <h2 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">{label}</h2>
              <ul className="space-y-2">
                {rows.map((n) => (
                  <li key={n.id}>
                    <button
                      type="button"
                      onClick={() => openRow(n)}
                      className={
                        'flex w-full items-center gap-3 rounded-md border p-3 text-left transition-colors '
                        + (n.readAt
                          ? 'border-slate-200 bg-white hover:bg-slate-50'
                          : 'border-brand-200 bg-brand-50 hover:bg-brand-100')
                      }
                    >
                      <span className={
                        'mt-1 h-2 w-2 shrink-0 rounded-full '
                        + (n.readAt ? 'bg-slate-300' : 'bg-brand-600')
                      } />
                      <div className="min-w-0 flex-1">
                        <div className="text-sm font-medium text-slate-900">{n.title}</div>
                        <div className="mt-0.5 line-clamp-2 text-xs text-slate-600">{n.body}</div>
                        <div className="mt-1 text-[11px] text-slate-400">
                          {new Date(n.createdAt).toLocaleString()}
                        </div>
                      </div>
                      {n.actionUrl && <ChevronRight className="h-4 w-4 shrink-0 text-slate-400" />}
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
    </InternPageShell>
  );
}

function TabButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      onClick={onClick}
      className={
        'rounded-md px-4 py-1.5 text-sm font-medium transition-colors '
        + (active ? 'bg-brand-700 text-white shadow-sm' : 'text-slate-600 hover:bg-slate-100')
      }
    >
      {children}
    </button>
  );
}

function groupByDate(items: UserNotification[]): { label: string; rows: UserNotification[] }[] {
  const today: UserNotification[] = [];
  const yesterday: UserNotification[] = [];
  const thisWeek: UserNotification[] = [];
  const earlier: UserNotification[] = [];
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const startOfYesterday = startOfToday - 86_400_000;
  const startOfWeek = startOfToday - 7 * 86_400_000;
  for (const n of items) {
    const t = new Date(n.createdAt).getTime();
    if (t >= startOfToday) today.push(n);
    else if (t >= startOfYesterday) yesterday.push(n);
    else if (t >= startOfWeek) thisWeek.push(n);
    else earlier.push(n);
  }
  const out: { label: string; rows: UserNotification[] }[] = [];
  if (today.length) out.push({ label: 'Today', rows: today });
  if (yesterday.length) out.push({ label: 'Yesterday', rows: yesterday });
  if (thisWeek.length) out.push({ label: 'This week', rows: thisWeek });
  if (earlier.length) out.push({ label: 'Earlier', rows: earlier });
  return out;
}
