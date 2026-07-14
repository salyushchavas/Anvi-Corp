'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import {
  AlertCircle,
  AlertTriangle,
  Info,
  LifeBuoy,
  Mail,
  MessageSquare,
} from 'lucide-react';
import api from '@/lib/api';

interface Contact {
  name: string;
  email: string;
  role: string;
}

interface Reminder {
  severity: 'URGENT' | 'WARN' | 'INFO';
  text: string;
  actionUrl?: string | null;
}

interface RightPanelResponse {
  contacts: {
    erm?: Contact | null;
    trainer?: Contact | null;
    evaluator?: Contact | null;
    manager?: Contact | null;
  };
  unreadCount: number;
  reminders: Reminder[];
}

const REMINDER_STYLES: Record<Reminder['severity'], { box: string; icon: React.ReactNode }> = {
  URGENT: {
    box: 'border-red-200 bg-red-50 text-red-900',
    icon: <AlertCircle className="h-4 w-4 text-red-600" strokeWidth={2.5} />,
  },
  WARN: {
    box: 'border-amber-200 bg-amber-50 text-amber-900',
    icon: <AlertTriangle className="h-4 w-4 text-amber-600" strokeWidth={2.5} />,
  },
  INFO: {
    box: 'border-slate-300 bg-slate-100 text-slate-700',
    icon: <Info className="h-4 w-4 text-slate-500" strokeWidth={2.5} />,
  },
};

/**
 * Phase 7 right-side panel. Rendered as an aside on the right of every
 * intern page on lg+ screens; tucks under the main content on smaller
 * viewports. Polls the dedicated /api/v1/intern/right-panel endpoint
 * every 60s so contacts / reminders / unread count stay fresh.
 */
export default function RightSidePanel() {
  const [data, setData] = useState<RightPanelResponse | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try {
      const res = await api.get<RightPanelResponse>('/api/v1/intern/right-panel');
      setData(res.data);
    } catch {
      // empty-state fallback below
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    const t = window.setInterval(() => void load(), 60_000);
    return () => window.clearInterval(t);
  }, [load]);

  const contacts: { role: string; contact: Contact }[] = [];
  if (data?.contacts.erm) contacts.push({ role: 'ERM', contact: data.contacts.erm });
  if (data?.contacts.trainer) contacts.push({ role: 'Trainer', contact: data.contacts.trainer });
  if (data?.contacts.evaluator) contacts.push({ role: 'Evaluator', contact: data.contacts.evaluator });
  if (data?.contacts.manager) contacts.push({ role: 'Manager', contact: data.contacts.manager });

  return (
    <aside className="space-y-4">
      {loading && (
        <div className="h-32 animate-pulse rounded-lg bg-slate-50" aria-hidden />
      )}

      {!loading && (
        <>
          <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
            <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
              Your team
            </h3>
            {contacts.length === 0 ? (
              <p className="mt-2 text-xs text-slate-500">
                Contacts will appear here once your team is assigned.
              </p>
            ) : (
              <ul className="mt-2 space-y-2">
                {contacts.map(({ role, contact }) => (
                  <li key={role} className="flex items-center gap-2">
                    <span className="flex h-7 w-7 items-center justify-center rounded-full bg-brand-100 text-[10px] font-semibold text-brand-800">
                      {initials(contact.name)}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-xs font-medium text-slate-900">
                        {contact.name}
                        <span className="ml-1 text-[10px] font-normal text-slate-500">
                          {role}
                        </span>
                      </div>
                      <a href={`mailto:${contact.email}`}
                        className="inline-flex items-center gap-0.5 text-[11px] text-brand-700 hover:underline">
                        <Mail className="h-2.5 w-2.5" />
                        {contact.email}
                      </a>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {data && data.reminders.length > 0 && (
            <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                Reminders
              </h3>
              <ul className="mt-2 space-y-2">
                {data.reminders.map((r, i) => {
                  const styles = REMINDER_STYLES[r.severity];
                  const inner = (
                    <div className={'flex items-start gap-2 rounded-md border px-2.5 py-2 text-xs ' + styles.box}>
                      <span className="mt-0.5">{styles.icon}</span>
                      <span className="flex-1">{r.text}</span>
                    </div>
                  );
                  return (
                    <li key={i}>
                      {r.actionUrl
                        ? <Link href={r.actionUrl}>{inner}</Link>
                        : inner}
                    </li>
                  );
                })}
              </ul>
            </section>
          )}

          <Link
            href="/careers/intern/messages"
            className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm text-slate-700 shadow-sm transition-colors hover:bg-slate-50"
          >
            <MessageSquare className="h-4 w-4 text-slate-500" />
            <span className="flex-1">Messages</span>
            {data && data.unreadCount > 0 && (
              <span className="rounded-full bg-brand-700 px-2 py-0.5 text-[10px] font-semibold text-white">
                {data.unreadCount}
              </span>
            )}
          </Link>

          <Link
            href="/careers/intern/help"
            className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm text-slate-700 shadow-sm transition-colors hover:bg-slate-50"
          >
            <LifeBuoy className="h-4 w-4 text-slate-500" />
            Need help?
          </Link>
        </>
      )}
    </aside>
  );
}

function initials(name: string): string {
  return name.trim().split(/\s+/).slice(0, 2).map((w) => w[0]?.toUpperCase() ?? '').join('');
}
