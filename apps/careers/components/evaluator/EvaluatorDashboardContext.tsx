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
import { usePathname } from 'next/navigation';
import api from '@/lib/api';
import type { DashboardResponse, RightPanelResponse } from './types';

const POLL_MS = 60_000;

interface Ctx {
  dashboard: DashboardResponse | null;
  dashboardLoading: boolean;
  dashboardError: string | null;
  rightPanel: RightPanelResponse | null;
  rightPanelError: string | null;
  refreshAll: () => Promise<void>;
}

const EvaluatorContext = createContext<Ctx | undefined>(undefined);

export function EvaluatorDashboardProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname();
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

  const loadDashboard = useCallback(async () => {
    try {
      const res = await api.get<DashboardResponse>('/api/v1/evaluator/dashboard');
      setDashboard(res.data);
      setDashboardError(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setDashboardError(ax.response?.data?.error ?? ax.message ?? 'Failed to load dashboard');
    } finally {
      setDashboardLoading(false);
    }
  }, []);

  const loadRightPanel = useCallback(async () => {
    try {
      const url = lifecycleId
        ? `/api/v1/evaluator/right-panel?lifecycleId=${lifecycleId}`
        : '/api/v1/evaluator/right-panel';
      const res = await api.get<RightPanelResponse>(url);
      setRightPanel(res.data);
      setRightPanelError(null);
    } catch (e) {
      const ax = e as { response?: { data?: { error?: string } }; message?: string };
      setRightPanelError(ax.response?.data?.error ?? ax.message ?? 'Failed to load panel');
    }
  }, [lifecycleId]);

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
    }),
    [dashboard, dashboardLoading, dashboardError, rightPanel, rightPanelError, refreshAll],
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
