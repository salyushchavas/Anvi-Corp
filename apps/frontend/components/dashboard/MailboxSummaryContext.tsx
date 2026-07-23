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
import type { User, UserRole } from '@/types';

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

/**
 * Role-aware visibility rule for the mail entry points (top-bar icon +
 * sidebar item). Staff always see mail — their mailbox is provisioned
 * at account creation via the unified admin-create dual-write, so the
 * SSO handoff always resolves via {@code User.email → localPart@domain}
 * regardless of whether the summary peek could hydrate. Interns start
 * without a mailbox and only see the entry once the ERM assigns one
 * (peek reports {@code hasMailbox=true}).
 *
 * <p>{@code STAFF_ROLES} is defined as "everything except INTERN".
 * A user carrying multiple roles counts as staff if ANY of them is a
 * staff role — e.g. a MANAGER who also holds INTERN keeps the mail UI
 * visible via the MANAGER side.</p>
 *
 * <p>When no user is loaded yet (initial render before auth resolves)
 * we hide — matches the pre-existing behavior, avoids a flash.</p>
 */
const STAFF_ROLES: readonly UserRole[] = [
  'TRAINER',
  'EVALUATOR',
  'REPORTING_MANAGER',
  'MANAGER',
  'ERM',
  'SUPER_ADMIN',
];

export function shouldShowMailEntry(
  user: User | null | undefined,
  summary: MailboxSummary | null,
): boolean {
  if (!user) return false;
  const roles = user.roles ?? [];
  const isStaff = roles.some((r) => STAFF_ROLES.includes(r));
  if (isStaff) return true;
  // Intern path: keep the pre-existing hasMailbox gate. Applicants (no
  // candidate row) share the INTERN role but never have a mailbox, so
  // they naturally stay hidden here.
  return !!summary?.hasMailbox;
}
