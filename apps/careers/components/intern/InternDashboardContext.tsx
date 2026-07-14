'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import api from '@/lib/api';

// ── Types ─────────────────────────────────────────────────────────────────

export type InternLifecycleStatus =
  | 'REGISTERED'
  | 'EMAIL_VERIFIED'
  | 'APPLICATION_SUBMITTED'
  | 'SHORTLISTED'
  | 'INTERVIEW_SCHEDULED'
  | 'INTERVIEW_COMPLETED'
  | 'OFFER_SENT'
  | 'OFFER_SIGNED'
  | 'EMPLOYEE_ID_CREATED'
  | 'ONBOARDING_ASSIGNED'
  | 'ONBOARDING_ACCEPTED'
  | 'ACTIVE_INTERN'
  | 'INACTIVE_INTERN';

export type InternMode =
  | 'APPLICANT'
  | 'INTERVIEW'
  | 'OFFER'
  | 'NEW_HIRE'
  | 'ACTIVE_INTERN'
  | 'INACTIVE';

export interface InternModuleState {
  visible: boolean;
  locked?: boolean;
  readOnly?: boolean;
}

export interface InternModulesMap {
  home: InternModuleState;
  jobPostings: InternModuleState;
  myApplications: InternModuleState;
  interviewCenter: InternModuleState;
  offerLetter: InternModuleState;
  onboarding: InternModuleState;
  myProjects: InternModuleState;
  timesheets: InternModuleState;
  evaluations: InternModuleState;
  documents: InternModuleState;
  messages: InternModuleState;
  doubts: InternModuleState;
  help: InternModuleState;
}

export interface InternNextAction {
  title: string;
  description: string;
  ctaLabel: string | null;
  ctaHref: string | null;
  waiting: boolean;
  waitingFor: string | null;
}

export interface InternContact {
  name: string;
  email: string;
}

export interface InternContacts {
  erm: InternContact | null;
  trainer: InternContact | null;
  evaluator: InternContact | null;
  manager: InternContact | null;
}

export interface InternExitSummary {
  exitType: string;
  exitDate: string;
  durationDays: number;
  projectsCompleted: number;
  evaluationsCount: number;
  averageScore: number | null;
  timesheetsApproved: number;
  totalApprovedHours: number | string;
  feedbackSubmitted: boolean;
  internVisibleSummary: string | null;
  finalEvaluationId: string | null;
}

/**
 * Surfaced only when the latest interview decision is SELECTED and the
 * applicant hasn't acknowledged yet. Powers the dedicated selection-ack
 * card on the intern home with the "Receive my offer letter" button.
 */
export interface InternSelectionAck {
  applicationId: string;
  jobTitle: string | null;
  applicantVisibleNotes: string | null;
}

/**
 * Approach 1 — derived "can this intern apply?" snapshot. Drives the
 * dashboard completion card + the disabled-Apply tooltip; the server
 * stays authoritative via the apply endpoint guard (409 PROFILE_INCOMPLETE).
 * Always non-null on the response — empty profile reads as
 * {complete:false, percent:0, missing:["phone","school",…]}.
 */
export interface InternApplyReadiness {
  complete: boolean;
  percent: number;
  missing: string[];
}

export interface InternDashboardResponse {
  user: {
    firstName: string;
    lastName: string;
    email: string;
    applicantId: string | null;
    employeeId: string | null;
  };
  lifecycleStatus: InternLifecycleStatus;
  mode: InternMode;
  emailVerified: boolean;
  modules: InternModulesMap;
  nextAction: InternNextAction;
  contacts: InternContacts;
  exitSummary: InternExitSummary | null;
  selectionAck: InternSelectionAck | null;
  applyReadiness: InternApplyReadiness;
  lastUpdatedAt: string;
}

/** Phase 8 — convenience flag for any form/button that needs to lock when inactive. */
export function useInternFormsDisabled(): boolean {
  const ctx = useContext(InternDashboardContext);
  return ctx?.data?.mode === 'INACTIVE';
}

// ── Context ───────────────────────────────────────────────────────────────

interface InternDashboardContextValue {
  data: InternDashboardResponse | null;
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

const InternDashboardContext = createContext<InternDashboardContextValue | null>(
  null,
);

const POLL_MS = 30_000;

export function InternDashboardProvider({ children }: { children: ReactNode }) {
  const [data, setData] = useState<InternDashboardResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const mountedRef = useRef(true);

  const load = useCallback(async () => {
    try {
      const res = await api.get<InternDashboardResponse>('/api/v1/intern/dashboard');
      if (!mountedRef.current) return;
      setData(res.data);
      setError(null);
    } catch (e) {
      if (!mountedRef.current) return;
      const msg = e instanceof Error ? e.message : 'Failed to load dashboard';
      setError(msg);
    } finally {
      if (mountedRef.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    void load();
    const t = window.setInterval(() => {
      void load();
    }, POLL_MS);
    return () => {
      mountedRef.current = false;
      window.clearInterval(t);
    };
  }, [load]);

  return (
    <InternDashboardContext.Provider
      value={{ data, loading, error, refresh: load }}
    >
      {children}
    </InternDashboardContext.Provider>
  );
}

export function useInternDashboard(): InternDashboardContextValue {
  const ctx = useContext(InternDashboardContext);
  if (!ctx) {
    throw new Error(
      'useInternDashboard must be used inside InternDashboardProvider',
    );
  }
  return ctx;
}

/**
 * Soft accessor — returns null when used outside the provider rather than
 * throwing. Useful for components (e.g. the global sidebar) that render
 * for every role and only adapt when an intern is logged in.
 */
export function useInternDashboardOptional(): InternDashboardContextValue | null {
  return useContext(InternDashboardContext);
}
