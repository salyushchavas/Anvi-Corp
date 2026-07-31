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

interface Ctx {
  dashboard: DashboardResponse | null;
  dashboardLoading: boolean;
  dashboardError: string | null;
  rightPanel: RightPanelResponse | null;
  rightPanelError: string | null;
  refreshAll: () => Promise<void>;
  /**
   * Currently-selected month as {@code YYYY-MM}. Default =
   * current calendar month. Synced with the {@code ?month} URL param —
   * changing this value updates the URL (dropping the param entirely
   * when the value is the current month so a clean URL stays clean).
   */
  selectedMonth: string;
  setSelectedMonth: (next: string) => void;
  /** True when {@link #selectedMonth} equals the current calendar month. */
  isCurrentMonth: boolean;
}

const EvaluatorContext = createContext<Ctx | undefined>(undefined);

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

  // URL is the source of truth for the selected month. A missing /
  // malformed ?month value falls back to the current calendar month —
  // matches the backend MonthRange.parse() default so client + server
  // never disagree on what "no month" means.
  const now = currentMonthValue();
  const rawMonthParam = searchParams?.get('month') ?? null;
  const selectedMonth = useMemo(() => {
    if (rawMonthParam && /^\d{4}-\d{2}$/.test(rawMonthParam)) {
      return rawMonthParam;
    }
    return now;
  }, [rawMonthParam, now]);
  const isCurrentMonth = selectedMonth === now;

  const setSelectedMonth = useCallback((next: string) => {
    const params = new URLSearchParams(
      searchParams ? searchParams.toString() : '',
    );
    if (next === now) {
      // Current-month keeps a clean URL — drop the param entirely.
      params.delete('month');
    } else {
      params.set('month', next);
    }
    const query = params.toString();
    router.replace(query ? `${pathname}?${query}` : pathname ?? '/', {
      scroll: false,
    });
  }, [now, pathname, router, searchParams]);

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
