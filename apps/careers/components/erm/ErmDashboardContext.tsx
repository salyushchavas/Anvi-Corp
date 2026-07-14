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
import api from '@/lib/api';

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
}

const ErmDashboardContext = createContext<ErmDashboardContextValue | null>(null);

const DASHBOARD_POLL_MS = 60_000;
const RIGHT_PANEL_POLL_MS = 60_000;

export function ErmDashboardProvider({ children }: { children: ReactNode }) {
  const [data, setData] = useState<ErmDashboardResponse | null>(null);
  const [rightPanel, setRightPanel] = useState<ErmRightPanelResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [scope, setScopeState] = useState<ErmScope>('mine');
  const mounted = useRef(true);

  const loadDashboard = useCallback(async (s: ErmScope) => {
    try {
      const res = await api.get<ErmDashboardResponse>(
        `/api/v1/erm/dashboard?scope=${s}`,
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
  }, []);

  const loadRightPanel = useCallback(async () => {
    try {
      const res = await api.get<ErmRightPanelResponse>('/api/v1/erm/right-panel');
      if (!mounted.current) return;
      setRightPanel(res.data);
    } catch {
      // Silent — right panel falls back to empty state
    }
  }, []);

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
    () => ({ data, rightPanel, loading, error, scope, setScope, refresh }),
    [data, rightPanel, loading, error, scope, setScope, refresh],
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
