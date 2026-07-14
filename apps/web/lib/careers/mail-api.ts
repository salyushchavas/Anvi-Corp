// Independent axios client for the mail module. Mirrors lib/api.ts's pattern
// (single-flight 401 refresh) but is a SEPARATE instance: it attaches the mail
// Bearer (mail.token), refreshes via /api/mail/auth/refresh, and on failure
// redirects to /mail/login. It does NOT import or share Skyzen's `api` instance,
// so the two interceptor chains never interfere.

import axios, {
  AxiosError,
  AxiosHeaders,
  InternalAxiosRequestConfig,
} from 'axios';
import {
  clearMailAuth,
  getMailRefreshToken,
  getMailToken,
  setMailRefreshToken,
  setMailToken,
} from './mail-auth-storage';

// Same env var Skyzen uses — the mail UI is same-origin with careers, so this
// already points at the backend. No new env var needed.
const baseURL = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

export const mailApiBaseURL = baseURL;

export const mailApi = axios.create({ baseURL });

const LOGIN_URL = '/mail/login';
const AUTH_PATHS = ['/mail/login'];
// Never attempt a refresh-and-retry for the mail auth endpoints themselves.
const NEVER_RETRY = [
  '/api/mail/auth/login',
  '/api/mail/auth/refresh',
  '/api/mail/auth/logout',
];

function isRetryableRequest(url: string | undefined): boolean {
  if (!url) return true;
  return !NEVER_RETRY.some((p) => url.endsWith(p) || url.includes(p + '?'));
}

mailApi.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getMailToken();
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

// Single-flight refresh: concurrent 401s share one /api/mail/auth/refresh call.
let refreshPromise: Promise<string | null> | null = null;
// Monotonic session counter, bumped by invalidateMailSession() (logout). Guards
// against a refresh that resolves AFTER logout re-writing the cleared tokens.
let sessionEpoch = 0;

/**
 * Tear down the mail session: clear storage AND invalidate any in-flight
 * refresh so a late-resolving refresh cannot resurrect the cleared tokens
 * (the logout-vs-refresh race). Use this from logout instead of clearMailAuth.
 */
export function invalidateMailSession(): void {
  sessionEpoch += 1;
  refreshPromise = null;
  clearMailAuth();
}

/**
 * Public refresh entrypoint for non-axios callers (the SSE stream, which uses
 * fetch so it can send the Bearer header). Shares the same single-flight refresh
 * + sessionEpoch guard as the axios interceptor.
 */
export function refreshMailToken(): Promise<string | null> {
  return tryRefreshAccessToken();
}

async function tryRefreshAccessToken(): Promise<string | null> {
  const refresh = getMailRefreshToken();
  if (!refresh) return null;
  if (refreshPromise) return refreshPromise;
  const epochAtStart = sessionEpoch;
  refreshPromise = (async () => {
    try {
      const res = await axios.post<{ token: string; refreshToken?: string }>(
        `${baseURL}/api/mail/auth/refresh`,
        { refreshToken: refresh },
        { headers: { 'Content-Type': 'application/json' } },
      );
      // Session was invalidated (e.g. logout) while this was in flight →
      // discard the result; do NOT re-write tokens that were just cleared.
      if (sessionEpoch !== epochAtStart) return null;
      setMailToken(res.data.token);
      setMailRefreshToken(res.data.refreshToken);
      return res.data.token;
    } catch {
      return null;
    } finally {
      refreshPromise = null;
    }
  })();
  return refreshPromise;
}

mailApi.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const cfg = error.config as
      | (InternalAxiosRequestConfig & { _retried?: boolean })
      | undefined;
    const status = error.response?.status;

    // Best-effort enrichment so callers can read userMessage/traceId/errorCode.
    try {
      const data = error.response?.data as
        | { error?: string; message?: string; traceId?: string; code?: string }
        | undefined;
      const headerTraceId = error.response?.headers?.['x-trace-id'] as
        | string
        | undefined;
      const e = error as AxiosError & {
        userMessage?: string;
        traceId?: string;
        errorCode?: string;
      };
      e.userMessage = data?.message ?? data?.error ?? undefined;
      e.traceId = data?.traceId ?? headerTraceId;
      e.errorCode = data?.code;
    } catch {
      // never break the error path
    }

    if (
      status === 401 &&
      cfg &&
      !cfg._retried &&
      isRetryableRequest(cfg.url) &&
      typeof window !== 'undefined' &&
      getMailRefreshToken()
    ) {
      cfg._retried = true;
      const fresh = await tryRefreshAccessToken();
      if (fresh) {
        cfg.headers = (cfg.headers ?? new AxiosHeaders()) as AxiosHeaders;
        cfg.headers.set('Authorization', `Bearer ${fresh}`);
        return mailApi.request(cfg);
      }
    }

    if (status === 401 && typeof window !== 'undefined') {
      clearMailAuth();
      const path = window.location.pathname;
      const onAuthPage = AUTH_PATHS.some((p) => path === p);
      if (!onAuthPage) {
        window.location.href = LOGIN_URL;
      }
    }

    return Promise.reject(error);
  },
);
