'use client';

import { useState } from 'react';
import { Mail } from 'lucide-react';
import { openMailWithSso } from '@/lib/careers/mail-sso';

/**
 * Staff-only "Mail" launcher for role dashboards on the custom layout
 * stack (Trainer / Manager / Evaluator) — a peer of the shared
 * {@code DashboardSidebar}'s {@code MailSidebarItem} but WITHOUT a
 * {@link import('./MailboxSummaryContext').MailboxSummaryContext}
 * dependency, so it slots into layouts that don't wrap in
 * {@code MailboxSummaryProvider}.
 *
 * <p>Visibility is unconditional — every consumer is a role-scoped shell
 * (via {@code ProtectedRoute requiredRoles=[...staff]}), and staff are
 * always-mail per {@code STAFF_ROLES} in
 * {@code MailboxSummaryContext.shouldShowMailEntry}. If the SSO bridge
 * 404s (legacy staff with no paired mailbox), {@link openMailWithSso}
 * surfaces a clean toast — no broken redirect.</p>
 *
 * <p>Click flow: POST {@code /api/v1/mail/sso-token}, write mail token
 * to {@code mail.*} localStorage, full-nav to {@code /mail}. Mail app
 * boots authenticated — no second login.</p>
 */
export default function StaffMailButton() {
  const [busy, setBusy] = useState(false);
  return (
    <button
      type="button"
      onClick={async () => {
        if (busy) return;
        setBusy(true);
        try {
          await openMailWithSso();
        } finally {
          setBusy(false);
        }
      }}
      disabled={busy}
      className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-50 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 disabled:cursor-progress disabled:opacity-60"
    >
      <Mail className="h-4 w-4" strokeWidth={2} />
      <span className="flex-1 text-left">{busy ? 'Opening…' : 'Mail'}</span>
    </button>
  );
}
