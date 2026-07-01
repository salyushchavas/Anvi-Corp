"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { authApi } from "@/lib/mail-client";
import {
  clearSession,
  getRefreshToken,
  getStoredAccount,
  getToken,
  setSession,
  type StoredAccount,
} from "@/lib/mail-api";
import type { MailAuthResponse } from "@/lib/mail-types";

type Status = "loading" | "authed" | "anon";

interface MailAuthContextValue {
  status: Status;
  account: StoredAccount | null;
  login: (email: string, password: string) => Promise<StoredAccount>;
  applySession: (resp: MailAuthResponse) => StoredAccount;
  logout: () => Promise<void>;
}

const MailAuthContext = createContext<MailAuthContextValue | null>(null);

export function MailAuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>("loading");
  const [account, setAccount] = useState<StoredAccount | null>(null);

  // Hydrate from localStorage after mount (avoids SSR/localStorage mismatch).
  useEffect(() => {
    const stored = getStoredAccount();
    if (stored && getToken()) {
      setAccount(stored);
      setStatus("authed");
    } else {
      setStatus("anon");
    }
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const resp = await authApi.login(email, password);
    const acct = setSession(resp);
    setAccount(acct);
    setStatus("authed");
    return acct;
  }, []);

  const applySession = useCallback((resp: MailAuthResponse) => {
    const acct = setSession(resp);
    setAccount(acct);
    setStatus("authed");
    return acct;
  }, []);

  const logout = useCallback(async () => {
    const rt = getRefreshToken();
    if (rt) {
      try {
        await authApi.logout(rt);
      } catch {
        /* best-effort; clear locally regardless */
      }
    }
    clearSession();
    setAccount(null);
    setStatus("anon");
  }, []);

  return (
    <MailAuthContext.Provider value={{ status, account, login, applySession, logout }}>
      {children}
    </MailAuthContext.Provider>
  );
}

export function useMailAuth(): MailAuthContextValue {
  const ctx = useContext(MailAuthContext);
  if (!ctx) throw new Error("useMailAuth must be used within a MailAuthProvider");
  return ctx;
}
