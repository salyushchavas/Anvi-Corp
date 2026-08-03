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
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import api from '@/lib/careers/api';
import { currentMonthValue } from '@/components/common/MonthPicker';

// ── Types ─────────────────────────────────────────────────────────────────

export type ErmKpiKey =
  | 'APPLICATIONS_PENDING_REVIEW'
  | 'INTERVIEWS_TODAY'
  | 'OFFERS_PENDING_SIGNATURE'
  | 'AWAITING_DOCUMENT_PACKET'
  | 'ONBOARDING_OVERDUE'
  | 'I9_EVERIFY_DUE'
  | 'ACTIVE_INTERNS_WITHOUT_PROJECT'
  | 'EVALUATIONS_OVERDUE'
  | 'TIMESHEETS_PENDING_APPROVAL';

export type ErmExceptionType =
  | 'UNSIGNED_OFFER_OVERDUE'
  | 'ONBOARDING_DOC_REJECTED'
  | 'I9_EVERIFY_TIMING_RISK'
  | 'NO_PROJECT_ASSIGNED'
  | 'TRAINER_MEETING_MISSING'
  | 'EVALUATION_OVERDUE'
  | 'TIMESHEET_MISSING'
  | 'EXIT_CHECKLIST_PENDING';

export type ErmExceptionSeverity = 'URGENT' | 'WARN' | 'INFO';

export type ErmScope = 'mine' | 'all';

export interface KpiSnapshot {
  key: ErmKpiKey;
  label: string;
  count: number;
  urgentCount: number;
  helperText: string | null;
  actionUrl: string;
}

export interface ExceptionRow {
  type: ErmExceptionType;
  severity: ErmExceptionSeverity;
  internId: string | null;
  internName: string | null;
  daysOverdue: number;
  actionUrl: string;
  subjectResourceId: string | null;
}

export interface ActivityEntry {
  actorName: string | null;
  action: string | null;
  subjectName: string | null;
  timestamp: string | null;
  deepLink: string;
}

export interface ErmDashboardResponse {
  caller: { firstName: string; lastName: string; role: string };
  asOf: string;
  scope: ErmScope;
  kpis: Record<ErmKpiKey, KpiSnapshot>;
  exceptions: {
    counts: Record<ErmExceptionType, number>;
    topUrgent: ExceptionRow[];
  };
  recentActivity: ActivityEntry[];
  unreadNotifications: number;
}

export interface QuickAction {
  key: string;
  label: string;
  href: string;
  badge: number;
}

export interface ErmRightPanelResponse {
  quickActions: QuickAction[];
  unreadNotifications: number;
  todayInterviewsCount: number;
}

// ── Context ───────────────────────────────────────────────────────────────

interface ErmDashboardContextValue {
  data: ErmDashboardResponse | null;
  rightPanel: ErmRightPanelResponse | null;
  loading: boolean;
  error: string | null;
  scope: ErmScope;
  setScope: (s: ErmScope) => void;
  refresh: () => Promise<void>;
  /**
   * Sticky global month selection for the whole ERM area. Same
   * persistence model as the Trainer/Evaluator contexts — URL ?month
   * (source on first mount) → localStorage (across-session store) →
   * current calendar month. After mount the context is authoritative
   * and mirrors changes onto the URL via {@code router.replace} with
   * {@code scroll: false}. Current-month drops the {@code ?month}
   * param entirely so the default URL stays clean.
   */
  selectedMonth: string;
  setSelectedMonth: (next: string) => void;
  /** True when {@link #selectedMonth} equals the current calendar month. */
  isCurrentMonth: boolean;
}

const ErmDashboardContext = createContext<ErmDashboardContextValue | null>(null);

const DASHBOARD_POLL_MS = 60_000;
const RIGHT_PANEL_POLL_MS = 60_000;
const STORAGE_KEY = 'anvi.erm.selectedMonth';
const MONTH_RE = /^\d{4}-\d{2}$/;

function resolveInitialMonth(now: string): string {
  if (typeof window === 'undefined') return now;
  try {
    const urlMonth = new URLSearchParams(window.location.search).get('month');
    if (urlMonth && MONTH_RE.test(urlMonth)) return urlMonth;
  } catch {
    /* URL parse failure → fall through */
  }
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (stored && MONTH_RE.test(stored)) return stored;
  } catch {
    /* storage disabled → fall through */
  }
  return now;
}

export function ErmDashboardProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const searchParams = useSearchParams();

  const [data, setData] = useState<ErmDashboardResponse | null>(null);
  const [rightPanel, setRightPanel] = useState<ErmRightPanelResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [scope, setScopeState] = useState<ErmScope>('mine');
  const mounted = useRef(true);

  const now = currentMonthValue();
  const [selectedMonth, setSelectedMonthState] = useState<string>(() => resolveInitialMonth(now));
  const isCurrentMonth = selectedMonth === now;

  const setSelectedMonth = useCallback((next: string) => {
    setSelectedMonthState(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* storage disabled — URL mirror below still fires */
    }
  }, []);

  // Mirror sticky state onto the URL. Current-month drops the param so
  // the default URL stays clean. Early-return when already in-sync
  // prevents infinite router.replace loops.
  useEffect(() => {
    if (!pathname) return;
    const currentParams = new URLSearchParams(
      searchParams ? searchParams.toString() : '',
    );
    const desired = isCurrentMonth ? null : selectedMonth;
    const existing = currentParams.get('month');
    if (existing === desired) return;
    if (desired === null) {
      currentParams.delete('month');
    } else {
      currentParams.set('month', desired);
    }
    const query = currentParams.toString();
    router.replace(query ? `${pathname}?${query}` : pathname, { scroll: false });
  }, [pathname, selectedMonth, isCurrentMonth, router, searchParams]);

  const loadDashboard = useCallback(async (s: ErmScope) => {
    try {
      const params = new URLSearchParams();
      params.set('scope', s);
      if (!isCurrentMonth) params.set('month', selectedMonth);
      const res = await api.get<ErmDashboardResponse>(
        `/api/v1/erm/dashboard?${params.toString()}`,
      );
      if (!mounted.current) return;
      setData(res.data);
      setError(null);
    } catch (e) {
      if (!mounted.current) return;
      setError(e instanceof Error ? e.message : 'Failed to load ERM dashboard');
    } finally {
      if (mounted.current) setLoading(false);
    }
  }, [isCurrentMonth, selectedMonth]);

  const loadRightPanel = useCallback(async () => {
    try {
      const url = isCurrentMonth
        ? '/api/v1/erm/right-panel'
        : `/api/v1/erm/right-panel?month=${encodeURIComponent(selectedMonth)}`;
      const res = await api.get<ErmRightPanelResponse>(url);
      if (!mounted.current) return;
      setRightPanel(res.data);
    } catch {
      // Silent — right panel falls back to empty state
    }
  }, [isCurrentMonth, selectedMonth]);

  useEffect(() => {
    mounted.current = true;
    void loadDashboard(scope);
    const t = window.setInterval(() => {
      void loadDashboard(scope);
    }, DASHBOARD_POLL_MS);
    return () => {
      mounted.current = false;
      window.clearInterval(t);
    };
  }, [loadDashboard, scope]);

  useEffect(() => {
    void loadRightPanel();
    const t = window.setInterval(() => {
      void loadRightPanel();
    }, RIGHT_PANEL_POLL_MS);
    return () => window.clearInterval(t);
  }, [loadRightPanel]);

  const refresh = useCallback(async () => {
    try {
      await api.post('/api/v1/erm/dashboard/refresh');
    } catch {
      // ignore; we'll fall back to the timed reload
    }
    await Promise.all([loadDashboard(scope), loadRightPanel()]);
  }, [loadDashboard, loadRightPanel, scope]);

  const setScope = useCallback((s: ErmScope) => {
    setScopeState(s);
  }, []);

  const value = useMemo<ErmDashboardContextValue>(
    () => ({
      data,
      rightPanel,
      loading,
      error,
      scope,
      setScope,
      refresh,
      selectedMonth,
      setSelectedMonth,
      isCurrentMonth,
    }),
    [
      data,
      rightPanel,
      loading,
      error,
      scope,
      setScope,
      refresh,
      selectedMonth,
      setSelectedMonth,
      isCurrentMonth,
    ],
  );

  return (
    <ErmDashboardContext.Provider value={value}>
      {children}
    </ErmDashboardContext.Provider>
  );
}

export function useErmDashboard(): ErmDashboardContextValue {
  const ctx = useContext(ErmDashboardContext);
  if (!ctx) {
    throw new Error('useErmDashboard must be used inside ErmDashboardProvider');
  }
  return ctx;
}

/**
 * Soft variant — returns {@code null} when the caller isn't inside an
 * {@link ErmDashboardProvider}. Used by shared chrome (e.g.
 * {@code DashboardLayout}) that renders on both ERM and non-ERM pages
 * and wants to conditionally show ERM-only widgets without crashing on
 * the non-ERM leg.
 */
export function useErmDashboardOptional(): ErmDashboardContextValue | null {
  return useContext(ErmDashboardContext);
}
