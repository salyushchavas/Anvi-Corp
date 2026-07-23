'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import api from '@/lib/careers/api';

/**
 * Shared mailbox-peek context — hoisted out of {@code TopBarMailbox} so
 * both the top-bar icon and the sidebar "Mail" item can gate their
 * visibility on the same {@code hasMailbox} flag WITHOUT firing two
 * copies of the summary request every 30s.
 *
 * <p>The provider polls {@code GET /api/v1/me/mailbox/summary} at the
 * same 30-second cadence the topbar peek used to use. Any consumer can
 * call {@link MailboxSummaryContextValue#refresh} to force an
 * out-of-band re-fetch (e.g. after the ERM assign-mailbox flow signals
 * a state change).</p>
 *
 * <p>The provider wraps the whole dashboard shell in
 * {@code DashboardLayout} so every /careers/(dashboard) subtree has
 * access. Consumers rendered outside the provider fall through to a
 * safe null-summary default, which naturally hides the gated UI.</p>
 */
export interface MailboxPeekItem {
  entryId: string;
  fromAddress: string;
  subject: string;
  receivedAt: string | null;
  unread: boolean;
}

export interface MailboxSummary {
  hasMailbox: boolean;
  mailAccountId: string | null;
  mailAddress: string | null;
  unreadCount: number;
  items: MailboxPeekItem[];
}

export interface MailboxSummaryContextValue {
  summary: MailboxSummary | null;
  refresh: () => Promise<void>;
}

const MailboxSummaryContext = createContext<MailboxSummaryContextValue | null>(null);

const POLL_MS = 30_000;

export function MailboxSummaryProvider({ children }: { children: ReactNode }) {
  const [summary, setSummary] = useState<MailboxSummary | null>(null);
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const refresh = useCallback(async () => {
    try {
      const res = await api.get<MailboxSummary>('/api/v1/me/mailbox/summary');
      if (mountedRef.current) setSummary(res.data);
    } catch {
      // Endpoint failure is silent — a mailbox peek that can't load
      // shouldn't crash the dashboard. Consumers treat null as
      // "no mailbox" and hide themselves.
      if (mountedRef.current) setSummary(null);
    }
  }, []);

  useEffect(() => {
    void refresh();
    const t = window.setInterval(() => void refresh(), POLL_MS);
    return () => window.clearInterval(t);
  }, [refresh]);

  const value = useMemo(() => ({ summary, refresh }), [summary, refresh]);

  return (
    <MailboxSummaryContext.Provider value={value}>
      {children}
    </MailboxSummaryContext.Provider>
  );
}

/**
 * Read the shared mailbox summary. Returns a safe null-summary default
 * when called outside a provider — the gated UI will hide, matching
 * "no mailbox" behavior, so components remain composable in isolation.
 */
export function useMailboxSummary(): MailboxSummaryContextValue {
  const ctx = useContext(MailboxSummaryContext);
  if (!ctx) {
    return { summary: null, refresh: async () => {} };
  }
  return ctx;
}
