'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import api from '@/lib/careers/api';
import { currentMonthValue } from '@/components/common/MonthPicker';
import type { DashboardResponse, RightPanelResponse } from './types';

const POLL_MS = 60_000;
const STORAGE_KEY = 'anvi.evaluator.selectedMonth';
const MONTH_RE = /^\d{4}-\d{2}$/;

interface Ctx {
  dashboard: DashboardResponse | null;
  dashboardLoading: boolean;
  dashboardError: string | null;
  rightPanel: RightPanelResponse | null;
  rightPanelError: string | null;
  refreshAll: () => Promise<void>;
  /**
   * Sticky global month selection for the whole evaluator area. Default
   * = current calendar month. Once the user changes it, it STAYS
   * changed across section navigation, refreshes, and the browser
   * session, until the user changes it again (including clicking
   * "Jump to this month" on the picker).
   *
   * <p>Persistence — the CONTEXT is authoritative, NOT the URL:</p>
   * <ul>
   *   <li>Initialised ONCE on provider mount from
   *       {@code URL ?month → localStorage → current month}. The URL
   *       branch preserves deep-link sharing.</li>
   *   <li>{@code setSelectedMonth} writes to localStorage AND mirrors
   *       the value onto the current URL. Both are best-effort — a
   *       broken storage or router never breaks the picker.</li>
   *   <li>A pathname-change effect re-mirrors so a {@code &lt;Link&gt;}
   *       that omitted {@code ?month} lands on the new URL with the
   *       sticky value re-applied.</li>
   *   <li>The URL is NEVER re-read after mount — that was the "silent
   *       reset" bug in the prior version, where navigating to a page
   *       without {@code ?month} snapped the value back to current.</li>
   * </ul>
   */
  selectedMonth: string;
  setSelectedMonth: (next: string) => void;
  /** True when {@link #selectedMonth} equals the current calendar month. */
  isCurrentMonth: boolean;
}

const EvaluatorContext = createContext<Ctx | undefined>(undefined);

/** Resolves the initial month once on mount. Order: URL → localStorage → now. */
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

export function EvaluatorDashboardProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const searchParams = useSearchParams();
  const [dashboard, setDashboard] = useState<DashboardResponse | null>(null);
  const [dashboardLoading, setDashboardLoading] = useState(true);
  const [dashboardError, setDashboardError] = useState<string | null>(null);
  const [rightPanel, setRightPanel] = useState<RightPanelResponse | null>(null);
  const [rightPanelError, setRightPanelError] = useState<string | null>(null);

  // Detect whether the right-side panel should fetch evaluee-scoped context.
  // The detail route pattern is /careers/evaluator/evaluees/{lifecycleId}.
  const lifecycleId = useMemo(() => {
    if (!pathname) return null;
    const match = pathname.match(/\/careers\/evaluator\/evaluees\/([0-9a-f-]+)/i);
    return match ? match[1] : null;
  }, [pathname]);

  const now = currentMonthValue();

  // Initialise ONCE from URL / localStorage / current month. useState's
  // initialiser runs exactly one time per provider mount. Subsequent
  // renders never re-read the URL — the context state is authoritative
  // (fixes the silent-reset bug on navigation between evaluator pages).
  const [selectedMonth, setSelectedMonthState] = useState<string>(() => resolveInitialMonth(now));
  const isCurrentMonth = selectedMonth === now;

  const setSelectedMonth = useCallback((next: string) => {
    setSelectedMonthState(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* storage disabled — the URL mirror effect below still fires */
    }
  }, []);

  // Mirror the sticky state onto the current URL whenever the state OR
  // the pathname changes. Early-return when the URL already matches so
  // we don't loop against our own router.replace. Current-month drops
  // the param entirely to keep the default URL clean.
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

  const loadDashboard = useCallback(async () => {
    try {
      const url = isCurrentMonth
        ? '/api/v1/evaluator/dashboard'
        : `/api/v1/evaluator/dashboard?month=${encodeURIComponent(selectedMonth)}`;
      const res = await api.get<DashboardResponse>(url);
      setDashboard(res.data);
      setDashboardError(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setDashboardError(ax.response?.data?.error ?? ax.message ?? 'Failed to load dashboard');
    } finally {
      setDashboardLoading(false);
    }
  }, [isCurrentMonth, selectedMonth]);

  const loadRightPanel = useCallback(async () => {
    try {
      const params = new URLSearchParams();
      if (lifecycleId) params.set('lifecycleId', lifecycleId);
      if (!isCurrentMonth) params.set('month', selectedMonth);
      const query = params.toString();
      const url = query
        ? `/api/v1/evaluator/right-panel?${query}`
        : '/api/v1/evaluator/right-panel';
      const res = await api.get<RightPanelResponse>(url);
      setRightPanel(res.data);
      setRightPanelError(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setRightPanelError(ax.response?.data?.error ?? ax.message ?? 'Failed to load panel');
    }
  }, [lifecycleId, isCurrentMonth, selectedMonth]);

  const refreshAll = useCallback(async () => {
    await Promise.all([loadDashboard(), loadRightPanel()]);
  }, [loadDashboard, loadRightPanel]);

  useEffect(() => {
    void loadDashboard();
    const id = setInterval(() => void loadDashboard(), POLL_MS);
    return () => clearInterval(id);
  }, [loadDashboard]);

  useEffect(() => {
    void loadRightPanel();
    const id = setInterval(() => void loadRightPanel(), POLL_MS);
    return () => clearInterval(id);
  }, [loadRightPanel]);

  const value = useMemo<Ctx>(
    () => ({
      dashboard,
      dashboardLoading,
      dashboardError,
      rightPanel,
      rightPanelError,
      refreshAll,
      selectedMonth,
      setSelectedMonth,
      isCurrentMonth,
    }),
    [dashboard, dashboardLoading, dashboardError, rightPanel, rightPanelError,
      refreshAll, selectedMonth, setSelectedMonth, isCurrentMonth],
  );

  return <EvaluatorContext.Provider value={value}>{children}</EvaluatorContext.Provider>;
}

export function useEvaluatorDashboard(): Ctx {
  const ctx = useContext(EvaluatorContext);
  if (!ctx) {
    throw new Error(
      'useEvaluatorDashboard must be used inside EvaluatorDashboardProvider',
    );
  }
  return ctx;
}
