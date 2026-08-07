import axios, {
  type AxiosError,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from 'axios';
import { clearAuth } from './auth-storage';

const baseURL = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';

// Exported so diagnostic surfaces (e.g. /register debug panel) can show
// the value the running client actually resolved at build time.
export const apiBaseURL = baseURL;

/**
 * Security Wave 2 (H) — the session moved from a Bearer token in
 * localStorage to an httpOnly cookie ({@code anvi_access}) the backend
 * sets on login/register/refresh/activate. {@code withCredentials: true}
 * tells the browser to attach cookies to every cross-origin request, and
 * a matching CORS config on the backend already whitelists this origin
 * with {@code Access-Control-Allow-Credentials: true}.
 *
 * <p>The request interceptor now injects the CSRF double-submit header
 * ({@code X-CSRF-Token}) on every state-changing call — the value comes
 * from the non-httpOnly {@code XSRF-TOKEN} cookie the backend seeds on
 * the first GET and rotates on login. See CsrfDoubleSubmitFilter.</p>
 */
export const api = axios.create({
  baseURL,
  withCredentials: true,
});

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-CSRF-Token';
const UNSAFE_METHODS = new Set(['post', 'put', 'patch', 'delete']);

function readCookie(name: string): string | null {
  if (typeof document === 'undefined') return null;
  const prefix = name + '=';
  const parts = document.cookie ? document.cookie.split('; ') : [];
  for (const part of parts) {
    if (part.startsWith(prefix)) {
      return decodeURIComponent(part.substring(prefix.length));
    }
  }
  return null;
}

api.interceptors.request.use((config) => {
  const method = (config.method ?? 'get').toLowerCase();
  if (UNSAFE_METHODS.has(method)) {
    const token = readCookie(CSRF_COOKIE);
    if (token) {
      config.headers.set(CSRF_HEADER, token);
    }
  }
  // For FormData uploads (resume upload, etc.): drop any forced Content-Type
  // so the browser can set "multipart/form-data; boundary=..." automatically.
  if (config.data instanceof FormData) {
    config.headers.delete('Content-Type');
  }
  return config;
});

// Paths where a 401 is expected (the user is mid-auth) — don't redirect.
const AUTH_PATHS = [
  '/careers/login',
  '/careers/register',
  '/careers/forgot-password',
  '/careers/reset-password',
];

const LOGIN_URL = '/careers/login';

// Requests we don't try to refresh + retry — failure on these means the user
// genuinely needs to re-authenticate, and any retry would just hide the real
// error.
const NEVER_RETRY = ['/auth/login', '/auth/register', '/auth/refresh'];

// Concurrent-401 handling: while the first 401 is busy refreshing, every other
// 401 should wait for that single attempt rather than each firing its own.
let refreshPromise: Promise<boolean> | null = null;

async function tryRefreshAccessToken(): Promise<boolean> {
  if (refreshPromise) return refreshPromise;
  refreshPromise = (async () => {
    try {
      // Security Wave 2 — refresh token lives in the {@code anvi_refresh}
      // httpOnly cookie; no body needed. Server reads the cookie, rotates
      // the session, and Set-Cookies the new access + refresh + CSRF
      // values on the response.
      await axios.post(
        `${baseURL}/auth/refresh`,
        {},
        {
          withCredentials: true,
          headers: {
            [CSRF_HEADER]: readCookie(CSRF_COOKIE) ?? '',
          },
        },
      );
      return true;
    } catch {
      return false;
    } finally {
      refreshPromise = null;
    }
  })();
  return refreshPromise;
}

function isRetryableRequest(url: string | undefined): boolean {
  if (!url) return true;
  return !NEVER_RETRY.some((p) => url.endsWith(p) || url.includes(p + '?'));
}

interface RetryConfig extends InternalAxiosRequestConfig {
  _retried?: boolean;
}

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const status = error?.response?.status;
    const cfg = error?.config as RetryConfig | undefined;

    // F4 — enrich the AxiosError so any caller can read a normalized
    // user-facing message + the per-request traceId without re-parsing
    // the response shape. Back-compat: the existing 191 call sites
    // continue to read `error.response.data.error` unchanged because
    // the backend keeps emitting both `error` and `message` (same
    // string).
    try {
      const data = error?.response?.data as
        | { error?: string; message?: string; traceId?: string; code?: string }
        | undefined;
      const headerTraceId =
        (error?.response?.headers?.['x-trace-id'] as string | undefined) ?? undefined;
      (error as AxiosError & {
        userMessage?: string;
        traceId?: string;
        errorCode?: string;
      }).userMessage = data?.message ?? data?.error ?? undefined;
      (error as AxiosError & { traceId?: string }).traceId =
        data?.traceId ?? headerTraceId;
      (error as AxiosError & { errorCode?: string }).errorCode = data?.code;
    } catch {
      // Enrichment is best-effort — never break the original error path.
    }

    // Try a refresh-and-retry once per request. The refresh cookie is
    // sent automatically by the browser; if it's missing we go straight
    // to the login redirect below.
    if (
      status === 401 &&
      cfg &&
      !cfg._retried &&
      isRetryableRequest(cfg.url) &&
      typeof window !== 'undefined'
    ) {
      cfg._retried = true;
      const ok = await tryRefreshAccessToken();
      if (ok) {
        // Cookie already refreshed by the server; just replay.
        return api.request(cfg as AxiosRequestConfig);
      }
    }

    if (status === 401 && typeof window !== 'undefined') {
      clearAuth();
      const path = window.location.pathname;
      const onAuthPage = AUTH_PATHS.some((p) => path === p);
      if (!onAuthPage) {
        window.location.href = LOGIN_URL;
      }
    }
    return Promise.reject(error);
  }
);

export default api;
