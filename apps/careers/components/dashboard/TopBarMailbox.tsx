'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { Mail } from 'lucide-react';
import api from '@/lib/api';

/**
 * Mail bridge Phase 5 (revised) — read-only mailbox peek mounted in
 * the dashboard topbar beside the bell + profile menu. Polls
 * /api/v1/me/mailbox/summary every 30s, mirroring TopBarBell's
 * cadence.
 *
 * <p>Renders nothing for users with no linked mailbox (the summary
 * endpoint returns {@code hasMailbox=false}, which we hide on). Click
 * opens a popover listing the most recent inbox messages (subject +
 * sender + time) with each item — and a footer CTA — deep-linking to
 * {@code /mail} where the user can compose, reply, mark-read, etc.
 * Compose / reply are intentionally NOT in the dashboard.</p>
 */
type PeekItem = {
  entryId: string;
  fromAddress: string;
  subject: string;
  receivedAt: string | null;
  unread: boolean;
};

type Summary = {
  hasMailbox: boolean;
  mailAccountId: string | null;
  mailAddress: string | null;
  unreadCount: number;
  items: PeekItem[];
};

export default function TopBarMailbox() {
  const [summary, setSummary] = useState<Summary | null>(null);
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get<Summary>('/api/v1/me/mailbox/summary');
      setSummary(res.data);
    } catch {
      // Silent — peek hides on absent data
      setSummary(null);
    }
  }, []);

  useEffect(() => {
    void load();
    const t = window.setInterval(() => void load(), 30_000);
    return () => window.clearInterval(t);
  }, [load]);

  // Click-outside to close the popover.
  useEffect(() => {
    if (!open) return;
    function onDocClick(e: MouseEvent) {
      if (!wrapRef.current) return;
      if (!wrapRef.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, [open]);

  if (!summary || !summary.hasMailbox) return null;

  const unread = summary.unreadCount;

  return (
    <div ref={wrapRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-label="Mailbox peek"
        aria-expanded={open}
        className="relative rounded-md p-2 text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2"
      >
        <Mail className="h-4 w-4" strokeWidth={2} />
        {unread > 0 && (
          <span className="absolute -right-0.5 -top-0.5 inline-flex h-4 min-w-[16px] items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-semibold leading-none text-white">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 z-40 mt-1 w-80 overflow-hidden rounded-md border border-slate-200 bg-white shadow-lg">
          <div className="flex items-center justify-between border-b border-slate-200 px-3 py-2">
            <div>
              <p className="text-xs font-semibold text-slate-900">Company mailbox</p>
              {summary.mailAddress && (
                <p className="font-mono text-[11px] text-slate-500">
                  {summary.mailAddress}
                </p>
              )}
            </div>
            <span className="text-[11px] text-slate-500">
              {unread > 0 ? `${unread} unread` : 'all read'}
            </span>
          </div>

          <ul className="max-h-80 overflow-y-auto divide-y divide-slate-100">
            {summary.items.length === 0 && (
              <li className="px-3 py-6 text-center text-xs text-slate-500">
                No messages yet.
              </li>
            )}
            {summary.items.map((it) => (
              <li key={it.entryId}>
                <Link
                  href="/mail"
                  onClick={() => setOpen(false)}
                  className="block px-3 py-2 transition-colors hover:bg-slate-50"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className={
                      'truncate text-xs '
                      + (it.unread ? 'font-semibold text-slate-900' : 'text-slate-700')
                    }>
                      {it.fromAddress}
                    </span>
                    <span className="shrink-0 text-[10px] text-slate-400">
                      {it.receivedAt ? formatShort(it.receivedAt) : ''}
                    </span>
                  </div>
                  <p className={
                    'truncate text-xs '
                    + (it.unread ? 'text-slate-800' : 'text-slate-500')
                  }>
                    {it.subject || '(no subject)'}
                  </p>
                </Link>
              </li>
            ))}
          </ul>

          <Link
            href="/mail"
            onClick={() => setOpen(false)}
            className="block border-t border-slate-200 px-3 py-2 text-center text-xs font-medium text-brand-700 hover:bg-brand-50"
          >
            Open in /mail →
          </Link>
        </div>
      )}
    </div>
  );
}

function formatShort(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  if (sameDay) {
    return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  }
  return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
}
