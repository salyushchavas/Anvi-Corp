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
import api from '@/lib/api';
import type {
  TrainerDashboardResponse,
  TrainerRightPanelResponse,
} from './types';

/**
 * Trainer Phase 1 — shared context for Home dashboard + right-side
 * panel. 60s polling for each; refresh() invalidates both.
 *
 * <p>Mirrors the ErmDashboardContext pattern. Components consume via
 * {@code useTrainerDashboard()}.</p>
 */

const DASHBOARD_POLL_MS = 60_000;
const RIGHT_PANEL_POLL_MS = 60_000;

interface Ctx {
  dashboard: TrainerDashboardResponse | null;
  rightPanel: TrainerRightPanelResponse | null;
  dashboardLoading: boolean;
  dashboardError: string | null;
  rightPanelError: string | null;
  refreshDashboard: () => Promise<void>;
  refreshRightPanel: () => Promise<void>;
  refreshAll: () => Promise<void>;
}

const TrainerDashboardCtx = createContext<Ctx | null>(null);

export function TrainerDashboardProvider({ children }: { children: ReactNode }) {
  const [dashboard, setDashboard] = useState<TrainerDashboardResponse | null>(null);
  const [rightPanel, setRightPanel] = useState<TrainerRightPanelResponse | null>(null);
  const [dashboardLoading, setDashboardLoading] = useState(true);
  const [dashboardError, setDashboardError] = useState<string | null>(null);
  const [rightPanelError, setRightPanelError] = useState<string | null>(null);

  const loadDashboard = useCallback(async () => {
    try {
      const res = await api.get<TrainerDashboardResponse>(
        '/api/v1/trainer/dashboard',
      );
      setDashboard(res.data);
      setDashboardError(null);
    } catch (e: any) {
      setDashboardError(
        e?.response?.data?.error ?? e?.message ?? 'Dashboard fetch failed',
      );
    } finally {
      setDashboardLoading(false);
    }
  }, []);

  const loadRightPanel = useCallback(async () => {
    try {
      const res = await api.get<TrainerRightPanelResponse>(
        '/api/v1/trainer/right-panel',
      );
      setRightPanel(res.data);
      setRightPanelError(null);
    } catch (e: any) {
      setRightPanelError(
        e?.response?.data?.error ?? e?.message ?? 'Right panel fetch failed',
      );
    }
  }, []);

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
