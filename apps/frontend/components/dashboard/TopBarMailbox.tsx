'use client';

import { useEffect, useRef, useState } from 'react';
import { Mail } from 'lucide-react';
import { openMailWithSso } from '@/lib/careers/mail-sso';
import { useAuth } from '@/lib/careers/auth-context';
import { shouldShowMailEntry, useMailboxSummary } from './MailboxSummaryContext';

/**
 * Mail entry point in the dashboard topbar beside the bell + profile
 * menu. Consumes {@link useMailboxSummary} — the shared context that
 * polls {@code /api/v1/me/mailbox/summary} once and is also used by
 * the sidebar Mail item — so both entry points render / hide together
 * with a single request cadence.
 *
 * <p>Visibility rule (role-aware): staff always see the icon — their
 * mailbox is provisioned at account creation and the SSO handoff works
 * off {@code User.email}, independent of whether the peek summary
 * could hydrate. Interns only see the icon after the ERM assigns their
 * mailbox ({@code hasMailbox=true}). See {@link shouldShowMailEntry}
 * for the exact rule; same gate the sidebar "Mail" item uses.</p>
 *
 * <p>Clicking the icon opens a peek popover listing the most recent
 * inbox messages with subject + sender + time. Clicking any message
 * OR the footer CTA triggers the SSO handoff and navigates to
 * {@code /mail} authenticated. Compose / reply stay in /mail proper.</p>
 */
export default function TopBarMailbox() {
  const { summary } = useMailboxSummary();
  const { user } = useAuth();
  const [open, setOpen] = useState(false);
  const [handoffInFlight, setHandoffInFlight] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

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

  // Role-aware gate — staff always see mail; interns see it only after
  // the ERM assigns them a mailbox. Same rule the sidebar Mail item
  // uses; centralized in shouldShowMailEntry so the two entry points
  // can't drift.
  if (!shouldShowMailEntry(user, summary)) return null;

  const unread = summary?.unreadCount ?? 0;

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

  return (
    <div ref={wrapRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-label="Mailbox peek"
        aria-expanded={open}
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

      {open && (
        <div className="absolute right-0 z-40 mt-1 w-80 overflow-hidden rounded-md border border-slate-200 bg-white shadow-lg">
          <div className="flex items-center justify-between border-b border-slate-200 px-3 py-2">
            <div>
              <p className="text-xs font-semibold text-slate-900">Company mailbox</p>
              {summary?.mailAddress && (
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
            {(summary?.items ?? []).length === 0 && (
              <li className="px-3 py-6 text-center text-xs text-slate-500">
                {summary ? 'No messages yet.' : 'Peek unavailable — open /mail directly.'}
              </li>
            )}
            {(summary?.items ?? []).map((it) => (
              <li key={it.entryId}>
                <button
                  type="button"
                  onClick={handleHandoff}
                  disabled={handoffInFlight}
                  className="block w-full px-3 py-2 text-left transition-colors hover:bg-slate-50 disabled:cursor-progress disabled:opacity-60"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span
                      className={
                        'truncate text-xs ' +
                        (it.unread ? 'font-semibold text-slate-900' : 'text-slate-700')
                      }
                    >
                      {it.fromAddress}
                    </span>
                    <span className="shrink-0 text-[10px] text-slate-400">
                      {it.receivedAt ? formatShort(it.receivedAt) : ''}
                    </span>
                  </div>
                  <p
                    className={
                      'truncate text-xs ' +
                      (it.unread ? 'text-slate-800' : 'text-slate-500')
                    }
                  >
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
