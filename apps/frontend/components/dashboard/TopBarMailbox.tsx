'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Mail } from 'lucide-react';
import api from '@/lib/careers/api';
import { openMailWithSso } from '@/lib/careers/mail-sso';

/**
 * Mail entry point in the dashboard topbar beside the bell + profile
 * menu. Always visible for authenticated staff; clicking the icon
 * either opens a peek popover (for users with a linked mailbox — polled
 * from /api/v1/me/mailbox/summary every 30s) OR does a direct SSO
 * handoff (for users whose peek data isn't available). Either path
 * ultimately routes to /mail via {@link openMailWithSso}, so the user
 * never sees the mail login form — the careers session mints a fresh
 * mail JWT on the server and hands it back for the client to persist.
 *
 * <p>The peek popover lists recent inbox messages with subject +
 * sender + time. Clicking any message OR the footer CTA triggers the
 * SSO handoff and navigates. Compose / reply stay in /mail proper.</p>
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
  const [handoffInFlight, setHandoffInFlight] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

  const load = useCallback(async () => {
    try {
      const res = await api.get<Summary>('/api/v1/me/mailbox/summary');
      setSummary(res.data);
    } catch {
      // Silent — peek data is optional; the icon still opens the inbox
      // via SSO even when the summary endpoint is unavailable.
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

  // Peek data is optional — when a linked mailbox exists, clicking the
  // icon opens the popover; when it doesn't (or the summary endpoint
  // failed), clicking the icon triggers the SSO handoff directly. Both
  // paths ultimately route to /mail.
  const hasMailbox = Boolean(summary && summary.hasMailbox);
  const unread = summary && summary.hasMailbox ? summary.unreadCount : 0;

  async function handleHandoff() {
    setOpen(false);
    if (handoffInFlight) return;
    setHandoffInFlight(true);
    try {
      await openMailWithSso();
    } finally {
      setHandoffInFlight(false);
    }
  }

  function handleIconClick() {
    if (hasMailbox) {
      // Toggle peek popover — user sees recent messages and can pick
      // one to open (each item also triggers the SSO handoff).
      setOpen((v) => !v);
    } else {
      void handleHandoff();
    }
  }

  return (
    <div ref={wrapRef} className="relative">
      <button
        type="button"
        onClick={handleIconClick}
        aria-label={hasMailbox ? 'Mailbox peek' : 'Open mail'}
        aria-expanded={hasMailbox ? open : undefined}
        disabled={handoffInFlight}
        className="relative rounded-md p-2 text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2 disabled:cursor-progress disabled:opacity-60"
      >
        <Mail className="h-4 w-4" strokeWidth={2} />
        {unread > 0 && (
          <span className="absolute -right-0.5 -top-0.5 inline-flex h-4 min-w-[16px] items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-semibold leading-none text-white">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && hasMailbox && summary && (
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
                <button
                  type="button"
                  onClick={handleHandoff}
                  disabled={handoffInFlight}
                  className="block w-full px-3 py-2 text-left transition-colors hover:bg-slate-50 disabled:cursor-progress disabled:opacity-60"
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
                </button>
              </li>
            ))}
          </ul>

          <button
            type="button"
            onClick={handleHandoff}
            disabled={handoffInFlight}
            className="block w-full border-t border-slate-200 px-3 py-2 text-center text-xs font-medium text-brand-700 hover:bg-brand-50 disabled:cursor-progress disabled:opacity-60"
          >
            {handoffInFlight ? 'Opening…' : 'Open in /mail →'}
          </button>
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
