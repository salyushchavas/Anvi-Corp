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
import type {
  TrainerDashboardResponse,
  TrainerRightPanelResponse,
} from './types';

/**
 * Trainer — shared context for Home dashboard + right-side panel +
 * sticky global month selector. Mirrors the Evaluator context's
 * persistence model (URL → localStorage → current month; context is
 * authoritative — URL is a mirror not a source-of-truth after mount).
 *
 * <p>The month value participates in dashboard + right-panel refetches
 * whenever it changes. To-do buckets intentionally pin to NOW and never
 * take a {@code ?month} — that mismatch is what the "past month" chip
 * on the top bar warns about.</p>
 */

const DASHBOARD_POLL_MS = 60_000;
const RIGHT_PANEL_POLL_MS = 60_000;
const STORAGE_KEY = 'anvi.trainer.selectedMonth';
const MONTH_RE = /^\d{4}-\d{2}$/;

interface Ctx {
  dashboard: TrainerDashboardResponse | null;
  rightPanel: TrainerRightPanelResponse | null;
  dashboardLoading: boolean;
  dashboardError: string | null;
  rightPanelError: string | null;
  refreshDashboard: () => Promise<void>;
  refreshRightPanel: () => Promise<void>;
  refreshAll: () => Promise<void>;
  /**
   * Sticky global month selection for the whole trainer area. See
   * {@link EvaluatorDashboardProvider} for the full contract — this is
   * the exact same model with a Trainer-namespaced localStorage key so
   * roles that share a browser don't collide.
   */
  selectedMonth: string;
  setSelectedMonth: (next: string) => void;
  /** True when {@link #selectedMonth} equals the current calendar month. */
  isCurrentMonth: boolean;
}

const TrainerDashboardCtx = createContext<Ctx | null>(null);

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

export function TrainerDashboardProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const searchParams = useSearchParams();
  const [dashboard, setDashboard] = useState<TrainerDashboardResponse | null>(null);
  const [rightPanel, setRightPanel] = useState<TrainerRightPanelResponse | null>(null);
  const [dashboardLoading, setDashboardLoading] = useState(true);
  const [dashboardError, setDashboardError] = useState<string | null>(null);
  const [rightPanelError, setRightPanelError] = useState<string | null>(null);

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

  const loadDashboard = useCallback(async () => {
    try {
      const url = isCurrentMonth
        ? '/api/v1/trainer/dashboard'
        : `/api/v1/trainer/dashboard?month=${encodeURIComponent(selectedMonth)}`;
      const res = await api.get<TrainerDashboardResponse>(url);
      setDashboard(res.data);
      setDashboardError(null);
    } catch (e: any) {
      setDashboardError(
        e?.response?.data?.error ?? e?.message ?? 'Dashboard fetch failed',
      );
    } finally {
      setDashboardLoading(false);
    }
  }, [isCurrentMonth, selectedMonth]);

  const loadRightPanel = useCallback(async () => {
    try {
      const url = isCurrentMonth
        ? '/api/v1/trainer/right-panel'
        : `/api/v1/trainer/right-panel?month=${encodeURIComponent(selectedMonth)}`;
      const res = await api.get<TrainerRightPanelResponse>(url);
      setRightPanel(res.data);
      setRightPanelError(null);
    } catch (e: any) {
      setRightPanelError(
        e?.response?.data?.error ?? e?.message ?? 'Right panel fetch failed',
      );
    }
  }, [isCurrentMonth, selectedMonth]);

  const refreshDashboard = useCallback(async () => {
    try {
      await api.post('/api/v1/trainer/dashboard/refresh');
    } catch {
      // best-effort cache invalidate; reload anyway
    }
    await loadDashboard();
  }, [loadDashboard]);

  const refreshRightPanel = useCallback(async () => {
    await loadRightPanel();
  }, [loadRightPanel]);

  const refreshAll = useCallback(async () => {
    await Promise.all([refreshDashboard(), loadRightPanel()]);
  }, [refreshDashboard, loadRightPanel]);

  useEffect(() => {
    void loadDashboard();
    const id = setInterval(() => {
      void loadDashboard();
    }, DASHBOARD_POLL_MS);
    return () => clearInterval(id);
  }, [loadDashboard]);

  useEffect(() => {
    void loadRightPanel();
    const id = setInterval(() => {
      void loadRightPanel();
    }, RIGHT_PANEL_POLL_MS);
    return () => clearInterval(id);
  }, [loadRightPanel]);

  const value = useMemo<Ctx>(
    () => ({
      dashboard,
      rightPanel,
      dashboardLoading,
      dashboardError,
      rightPanelError,
      refreshDashboard,
      refreshRightPanel,
      refreshAll,
      selectedMonth,
      setSelectedMonth,
      isCurrentMonth,
    }),
    [
      dashboard,
      rightPanel,
      dashboardLoading,
      dashboardError,
      rightPanelError,
      refreshDashboard,
      refreshRightPanel,
      refreshAll,
      selectedMonth,
      setSelectedMonth,
      isCurrentMonth,
    ],
  );

  return (
    <TrainerDashboardCtx.Provider value={value}>
      {children}
    </TrainerDashboardCtx.Provider>
  );
}

export function useTrainerDashboard(): Ctx {
  const ctx = useContext(TrainerDashboardCtx);
  if (!ctx) {
    throw new Error(
      'useTrainerDashboard must be used inside <TrainerDashboardProvider>',
    );
  }
  return ctx;
}
