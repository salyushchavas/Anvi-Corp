// The ONE mail-api client. Every /api/mail/** call goes through here: it attaches
// the Bearer token, single-flights a 401 refresh via /api/mail/auth/refresh, guards
// against the logout-vs-refresh race with a session epoch, and redirects to
// /mail/login when refresh fails. It is entirely separate from anvicorp's
// contact-form fetch (which is untouched).

import type { MailRole } from "./mail-types";

const API_BASE = (process.env.NEXT_PUBLIC_API_URL ?? "").replace(/\/+$/, "");

export const TOKEN_KEY = "mail.token";
export const REFRESH_KEY = "mail.refreshToken";
export const ACCOUNT_KEY = "mail.account";

export interface StoredAccount {
  accountId: string;
  email: string;
  displayName: string | null;
  role: MailRole;
  mustChangePassword: boolean;
}

interface SessionResponse {
  token: string;
  refreshToken: string;
  accountId: string;
  email: string;
  displayName: string | null;
  role: MailRole;
  mustChangePassword: boolean;
}

function ls(): Storage | null {
  return typeof window === "undefined" ? null : window.localStorage;
}

export function getToken(): string | null {
  return ls()?.getItem(TOKEN_KEY) ?? null;
}
export function getRefreshToken(): string | null {
  return ls()?.getItem(REFRESH_KEY) ?? null;
}
export function getStoredAccount(): StoredAccount | null {
  const raw = ls()?.getItem(ACCOUNT_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as StoredAccount;
  } catch {
    return null;
  }
}

/** Persist a login/refresh/change-password result. */
export function setSession(r: SessionResponse): StoredAccount {
  const account: StoredAccount = {
    accountId: r.accountId,
    email: r.email,
    displayName: r.displayName,
    role: r.role,
    mustChangePassword: r.mustChangePassword,
  };
  const s = ls();
  if (s) {
    s.setItem(TOKEN_KEY, r.token);
    s.setItem(REFRESH_KEY, r.refreshToken);
    s.setItem(ACCOUNT_KEY, JSON.stringify(account));
  }
  return account;
}

// Bumped on every logout/clear so an in-flight refresh started before the logout
// cannot resurrect a dead session (guards the logout-vs-refresh race).
let sessionEpoch = 0;

export function clearSession(): void {
  sessionEpoch++;
  const s = ls();
  if (s) {
    s.removeItem(TOKEN_KEY);
    s.removeItem(REFRESH_KEY);
    s.removeItem(ACCOUNT_KEY);
  }
}

export class MailApiError extends Error {
  readonly status: number;
  readonly code: string | null;
  constructor(status: number, message: string, code: string | null) {
    super(message);
    this.name = "MailApiError";
    this.status = status;
    this.code = code;
  }
}

let refreshPromise: Promise<string | null> | null = null;

async function performRefresh(): Promise<string | null> {
  const rt = getRefreshToken();
  if (!rt) return null;
  const epochAtStart = sessionEpoch;
  const res = await fetch(`${API_BASE}/api/mail/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken: rt }),
  });
  if (!res.ok) {
    clearSession();
    return null;
  }
  const data = (await res.json()) as SessionResponse;
  // If a logout happened while this refresh was in flight, discard the result.
  if (epochAtStart !== sessionEpoch) return null;
  setSession(data);
  return data.token;
}

function refreshOnce(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = performRefresh().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

function redirectToLogin(): void {
  if (typeof window !== "undefined" && !window.location.pathname.startsWith("/mail/login")) {
    window.location.href = "/mail/login";
  }
}

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  /** false → no Bearer, no refresh (used for login/refresh itself). Default true. */
  auth?: boolean;
  query?: Record<string, string | number | undefined>;
}

/** JSON request on the authed instance. */
export async function mailApi<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const url = `${API_BASE}${path}${buildQuery(opts.query)}`;
  const init: RequestInit = {
    method: opts.method ?? "GET",
    headers: { "Content-Type": "application/json" },
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  };
  const res = await fetchWithAuth(url, init, opts.auth !== false);
  if (!res.ok) throw await toError(res);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/**
 * Upload a RAW octet-stream body (a File/Blob) on the SAME authed instance
 * (Bearer + single-flight 401 refresh). The backend reads the request body as
 * raw bytes; filename/contentType go as query params. Response parsed as JSON.
 */
export async function mailUpload<T>(
  path: string,
  file: Blob,
  query?: Record<string, string | number | undefined>,
): Promise<T> {
  const url = `${API_BASE}${path}${buildQuery(query)}`;
  const res = await fetchWithAuth(url, { method: "POST", body: file }, true);
  if (!res.ok) throw await toError(res);
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export interface BlobDownload {
  blob: Blob;
  filename: string | null;
  contentType: string | null;
}

/**
 * Download a binary body through the authed WALLED proxy (Bearer + refresh).
 * The bytes come back decrypted with a Content-Disposition filename — there is
 * no presigned / raw S3 URL.
 */
export async function mailDownloadBlob(path: string): Promise<BlobDownload> {
  const res = await fetchWithAuth(`${API_BASE}${path}`, { method: "GET" }, true);
  if (!res.ok) throw await toError(res);
  return {
    blob: await res.blob(),
    filename: filenameFromDisposition(res.headers.get("Content-Disposition")),
    contentType: res.headers.get("Content-Type"),
  };
}

/** Shared authed fetch: attaches the Bearer, single-flights a 401 refresh, retries once. */
async function fetchWithAuth(
  url: string,
  init: RequestInit,
  useAuth: boolean,
  allowRefresh = true,
): Promise<Response> {
  const headers = new Headers(init.headers);
  if (useAuth) {
    const t = getToken();
    if (t) headers.set("Authorization", `Bearer ${t}`);
  }
  const res = await fetch(url, { ...init, headers });
  if (res.status === 401 && useAuth && allowRefresh && getRefreshToken()) {
    const newToken = await refreshOnce();
    if (newToken) return fetchWithAuth(url, init, useAuth, false); // retry once with the fresh token
    redirectToLogin();
    throw new MailApiError(401, "Your session has expired. Please sign in again.", "MAIL_SESSION_EXPIRED");
  }
  return res;
}

function filenameFromDisposition(cd: string | null): string | null {
  if (!cd) return null;
  const star = /filename\*=(?:UTF-8'')?([^;]+)/i.exec(cd);
  if (star) {
    try {
      return decodeURIComponent(star[1].trim().replace(/^"|"$/g, ""));
    } catch {
      /* fall through to plain */
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(cd);
  return plain ? plain[1].trim() : null;
}

async function toError(res: Response): Promise<MailApiError> {
  let message = `Request failed (${res.status})`;
  let code: string | null = null;
  try {
    const j = await res.json();
    message = j.message || j.error || message;
    code = j.code ?? null;
  } catch {
    /* non-JSON body */
  }
  return new MailApiError(res.status, message, code);
}

function buildQuery(q?: Record<string, string | number | undefined>): string {
  if (!q) return "";
  const parts = Object.entries(q)
    .filter(([, v]) => v !== undefined && v !== "")
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join("&")}` : "";
}
