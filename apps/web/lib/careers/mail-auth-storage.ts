// Mail-module auth storage. SEPARATE from Skyzen's lib/auth-storage.ts — distinct
// localStorage keys (mail.* vs skyzen.*) so the two sessions never collide. The
// mail client reads/writes ONLY these keys and never touches skyzen.*.

/** The mail identity persisted client-side (assembled from the S1 API responses). */
export interface MailAccount {
  accountId: string;
  email: string;
  displayName: string | null;
  role: string;
  mustChangePassword: boolean;
  domainId: string | null;
}

const TOKEN_KEY = 'mail.token';
const REFRESH_TOKEN_KEY = 'mail.refreshToken';
const ACCOUNT_KEY = 'mail.account';

function hasWindow(): boolean {
  return typeof window !== 'undefined';
}

export function getMailToken(): string | null {
  if (!hasWindow()) return null;
  return window.localStorage.getItem(TOKEN_KEY);
}

export function setMailToken(token: string): void {
  if (!hasWindow()) return;
  window.localStorage.setItem(TOKEN_KEY, token);
}

export function getMailRefreshToken(): string | null {
  if (!hasWindow()) return null;
  return window.localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function setMailRefreshToken(token: string | null | undefined): void {
  if (!hasWindow()) return;
  if (token) {
    window.localStorage.setItem(REFRESH_TOKEN_KEY, token);
  } else {
    window.localStorage.removeItem(REFRESH_TOKEN_KEY);
  }
}

export function getMailAccount(): MailAccount | null {
  if (!hasWindow()) return null;
  const raw = window.localStorage.getItem(ACCOUNT_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as MailAccount;
  } catch {
    return null;
  }
}

export function setMailAccount(account: MailAccount): void {
  if (!hasWindow()) return;
  window.localStorage.setItem(ACCOUNT_KEY, JSON.stringify(account));
}

/** Persist a freshly-issued token pair + account in one call. */
export function setMailAuth(args: {
  token: string;
  refreshToken: string | null | undefined;
  account: MailAccount;
}): void {
  setMailToken(args.token);
  setMailRefreshToken(args.refreshToken);
  setMailAccount(args.account);
}

export function clearMailAuth(): void {
  if (!hasWindow()) return;
  window.localStorage.removeItem(TOKEN_KEY);
  window.localStorage.removeItem(REFRESH_TOKEN_KEY);
  window.localStorage.removeItem(ACCOUNT_KEY);
}
