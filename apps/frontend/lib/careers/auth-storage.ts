import type { User } from '@/types';

/**
 * Security Wave 2 (H) — the access + refresh tokens moved OUT of
 * localStorage into httpOnly cookies set by the backend on
 * login/register/refresh/activate. JavaScript can no longer read them, so
 * an XSS payload can't exfiltrate the session. The token setters/getters
 * are removed; the {@link User} cache stays because it contains no
 * secrets (just display name / role / email / applicantId — all fields
 * we already send to the client on every /auth/me).
 *
 * <p>{@link clearAuth} now only wipes the cached user object. The
 * server-side cookie clear happens via the {@code POST /auth/logout}
 * call in {@code auth-context.tsx}.</p>
 */

const USER_KEY = 'careers.user';

export function getUser(): User | null {
  if (typeof window === 'undefined') return null;
  const raw = window.localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as User;
  } catch {
    return null;
  }
}

export function setUser(user: User): void {
  if (typeof window === 'undefined') return;
  window.localStorage.setItem(USER_KEY, JSON.stringify(user));
}

/**
 * Best-effort client-side cleanup. Wipes the cached User object.
 * The server-side session clear happens via {@code POST /auth/logout}
 * in {@code auth-context.tsx}, which invalidates the httpOnly access
 * + refresh cookies (Security Wave 2 moved tokens out of
 * localStorage into httpOnly cookies — no JS-readable token keys
 * exist anymore).
 */
export function clearAuth(): void {
  if (typeof window === 'undefined') return;
  window.localStorage.removeItem(USER_KEY);
}
