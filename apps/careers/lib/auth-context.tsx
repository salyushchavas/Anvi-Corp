'use client';

import { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import api from './api';
import {
  clearAuth,
  getToken,
  getUser,
  setRefreshToken,
  setToken,
  setUser,
} from './auth-storage';
import type { AuthResponse, DegreeLevel, User, WorkAuthTrack } from '@/types';

/**
 * Phase 1.4 — optional intake profile + neutral work-auth self-attestation
 * the registration form can collect up-front. Every field is optional so an
 * older caller passing only the four legacy params keeps working unchanged.
 */
export interface RegistrationIntake {
  legalName?: string;
  preferredName?: string;
  /** Legacy free-text summary (pre-Phase 1.5). New form leaves blank and
   *  uses the structured trio below. */
  education?: string;
  school?: string;
  /** Legacy free-text degree (pre-Phase 1.5). New form leaves blank and
   *  uses `degreeLevel` instead. */
  degree?: string;
  /** Phase 1.5 — structured education. */
  degreeLevel?: DegreeLevel;
  specialization?: string;
  graduationYear?: number;
  skillset?: string;
  authorizedToWork?: boolean;
  sponsorshipNeeded?: boolean;
  expectedTrack?: WorkAuthTrack;
  /** ISO yyyy-mm-dd; END date — only sent when the chosen track's
   *  VisaDateRequirement is END_ONLY or BOTH. */
  validityDate?: string;
  /** ISO yyyy-mm-dd; START date — only sent when the chosen track's
   *  VisaDateRequirement is BOTH. */
  validityStartDate?: string;
}

interface MeResponse {
  userId: string;
  email: string;
  fullName: string;
  phoneNumber?: string;
  roles: User['roles'];
  createdAt?: string;
  emailVerified?: boolean;
  applicantId?: string;
  /** Phase 3 step 6 — candidate's expectedTrack so the sidebar can hide STEM-only tiles. */
  expectedTrack?: WorkAuthTrack;
  /** TRUE for staff accounts created with a temp password (drives the
   *  force-change-password redirect gate). */
  mustChangePassword?: boolean;
}

interface AuthContextValue {
  user: User | null;
  isLoading: boolean;
  login: (email: string, password: string) => Promise<User>;
  register: (
    email: string,
    password: string,
    fullName: string,
    phoneNumber?: string,
    intake?: RegistrationIntake,
    acceptedTos?: boolean,
  ) => Promise<User>;
  /**
   * Update the locally-cached user object after a state change (e.g.
   * email verification flipped emailVerified to true). Persists to
   * localStorage so a hard refresh sees the new value.
   */
  updateUser: (patch: Partial<User>) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

function userFromAuthResponse(res: AuthResponse, phoneNumber?: string): User {
  return {
    userId: res.userId,
    email: res.email,
    fullName: res.fullName,
    phoneNumber,
    roles: res.roles,
    emailVerified: res.emailVerified,
    applicantId: res.applicantId,
    mustChangePassword: res.mustChangePassword,
  };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUserState] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    const stored = getUser();
    if (stored) setUserState(stored);
    setIsLoading(false);
    // Phase 1.2: refresh from /auth/me so localStorage caches written before
    // emailVerified/applicantId existed pick up the new fields, and so a
    // verification flipped in another tab is reflected on the next load.
    // Silent — if it fails (token expired etc.) the cached user stays valid.
    if (getToken()) {
      void refreshFromMe();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Force-change-password gate. Mirrors the server-side
  // ForcePasswordChangeFilter: when the user has the temp-password flag,
  // every route except the change-password screen is unreachable. The
  // server returns 403 PASSWORD_CHANGE_REQUIRED so this redirect is the
  // UX, not the security guarantee. Re-runs on every route change so a
  // back-button bypass instantly bounces back.
  useEffect(() => {
    if (
      user?.mustChangePassword
      && pathname !== '/careers/force-change-password'
    ) {
      router.replace('/careers/force-change-password');
    }
  }, [user?.mustChangePassword, pathname, router]);

  async function refreshFromMe(): Promise<void> {
    try {
      const res = await api.get<MeResponse>('/auth/me');
      const me = res.data;
      setUserState((curr) => {
        const next: User = {
          userId: me.userId,
          email: me.email,
          fullName: me.fullName,
          phoneNumber: me.phoneNumber ?? curr?.phoneNumber,
          roles: me.roles,
          createdAt: me.createdAt ?? curr?.createdAt,
          emailVerified: me.emailVerified,
          applicantId: me.applicantId,
          expectedTrack: me.expectedTrack,
          mustChangePassword: me.mustChangePassword,
        };
        setUser(next);
        return next;
      });
    } catch {
      // ignore — cached user remains in effect
    }
  }

  async function login(email: string, password: string): Promise<User> {
    const res = await api.post<AuthResponse>('/auth/login', { email, password });
    setToken(res.data.token);
    setRefreshToken(res.data.refreshToken ?? null);
    const u = userFromAuthResponse(res.data);
    setUser(u);
    setUserState(u);
    return u;
  }

  async function register(
    email: string,
    password: string,
    fullName: string,
    phoneNumber?: string,
    intake?: RegistrationIntake,
    acceptedTos?: boolean,
  ): Promise<User> {
    const res = await api.post<AuthResponse>('/auth/register', {
      email,
      password,
      fullName,
      phoneNumber,
      // Phase 1.4 intake + neutral self-attestation. Fields the user didn't
      // fill stay undefined and serialise as absent rather than empty strings.
      legalName: intake?.legalName,
      preferredName: intake?.preferredName,
      education: intake?.education,
      school: intake?.school,
      degree: intake?.degree,
      // Phase 1.5 — structured education.
      degreeLevel: intake?.degreeLevel,
      specialization: intake?.specialization,
      graduationYear: intake?.graduationYear,
      skillset: intake?.skillset,
      authorizedToWork: intake?.authorizedToWork,
      sponsorshipNeeded: intake?.sponsorshipNeeded,
      expectedTrack: intake?.expectedTrack,
      // Phase 1.5 — visa-conditional dates. Caller has already nulled out
      // fields that don't apply to the chosen track.
      validityDate: intake?.validityDate,
      validityStartDate: intake?.validityStartDate,
      // Required by the backend's @AssertTrue gate.
      acceptedTos,
    });
    setToken(res.data.token);
    setRefreshToken(res.data.refreshToken ?? null);
    const u = userFromAuthResponse(res.data, phoneNumber);
    setUser(u);
    setUserState(u);
    return u;
  }

  function updateUser(patch: Partial<User>): void {
    setUserState((curr) => {
      if (!curr) return curr;
      const next = { ...curr, ...patch };
      setUser(next);
      return next;
    });
  }

  function logout(): void {
    clearAuth();
    setUserState(null);
  }

  return (
    <AuthContext.Provider value={{ user, isLoading, login, register, updateUser, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
