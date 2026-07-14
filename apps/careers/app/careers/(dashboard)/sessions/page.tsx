'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  AlertCircle,
  CheckCircle2,
  LogOut,
  Monitor,
  ShieldOff,
} from 'lucide-react';
import api from '@/lib/api';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import ProtectedRoute from '@/components/ProtectedRoute';
import { clearAuth } from '@/lib/auth-storage';
import { formatRelative } from '@/lib/format-date';
import type { UserRole, UserSessionResponse } from '@/types';

/**
 * Active sessions surface — applies to every authenticated role. Lists
 * non-revoked sessions for the calling user, lets them revoke one or all-
 * except-current.
 */
export default function SessionsPage() {
  // Open to every authenticated role — the @PreAuthorize on the backend is
  // isAuthenticated().
  const allRoles: UserRole[] = [
    'INTERN',
    'INTERN',
    'ERM',
    'ERM',
    'TRAINER',
    'MANAGER',
    'SUPER_ADMIN',
  ];
  return (
    <ProtectedRoute requiredRoles={allRoles}>
      <DashboardLayout title="Active sessions">
        <Body />
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function Body() {
  const [sessions, setSessions] = useState<UserSessionResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [signOutAllConfirm, setSignOutAllConfirm] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const res = await api.get<UserSessionResponse[]>('/api/v1/me/sessions');
      setSessions(res.data ?? []);
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't load your sessions.");
      setSessions(null);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!toast) return;
    const t = window.setTimeout(() => setToast(null), 3000);
    return () => window.clearTimeout(t);
  }, [toast]);

  async function revokeOne(id: string, isCurrent: boolean) {
    setBusy(id);
    setError(null);
    try {
      await api.delete(`/api/v1/me/sessions/${id}`);
      if (isCurrent) {
        // The caller just signed themselves out of this very browser — wipe
        // local tokens and bounce to login. The next /me request would 401
        // anyway; doing it ourselves is faster + cleaner.
        clearAuth();
        window.location.href = '/careers/login';
        return;
      }
      setToast('Session signed out.');
      void load();
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't sign out that session.");
    } finally {
      setBusy(null);
    }
  }

  async function signOutAll() {
    setBusy('all');
    setError(null);
    try {
      await api.post('/api/v1/me/sessions/sign-out-everywhere', {
        includeCurrent: false,
      });
      setSignOutAllConfirm(false);
      setToast('Signed out of all other devices.');
      void load();
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't sign out everywhere.");
    } finally {
      setBusy(null);
    }
  }

  const hasOthers = (sessions ?? []).some((s) => !s.current);
  const latest = sessions?.[0];

  return (
    <section className="space-y-6">
      <header className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">Active sessions</h1>
          <p className="mt-1 text-sm text-gray-600">
            Browsers and devices currently signed in to your account.
          </p>
          {latest && (
            <p className="mt-2 text-xs text-gray-500">
              Last sign-in: {formatRelative(latest.createdAt)}
            </p>
          )}
        </div>
        {hasOthers && (
          <button
            type="button"
            onClick={() => setSignOutAllConfirm(true)}
            disabled={busy !== null}
            className="inline-flex shrink-0 items-center gap-1.5 rounded-md border border-red-300 bg-white px-3 py-1.5 text-sm font-medium text-red-700 hover:bg-red-50 disabled:opacity-60"
          >
            <ShieldOff className="h-3.5 w-3.5" strokeWidth={2} />
            Sign out everywhere
          </button>
        )}
      </header>

      {toast && (
        <div className="rounded-md border border-green-200 bg-green-50 px-4 py-2 text-sm text-green-800">
          {toast}
        </div>
      )}
      {error && (
        <div className="flex items-start gap-2 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" strokeWidth={2} />
          <p>{error}</p>
        </div>
      )}

      {signOutAllConfirm && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4">
          <p className="text-sm text-red-900">
            Sign out every other browser and device? This won&apos;t sign you
            out from this one.
          </p>
          <div className="mt-3 flex justify-end gap-2">
            <button
              type="button"
              onClick={() => setSignOutAllConfirm(false)}
              disabled={busy === 'all'}
              className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => void signOutAll()}
              disabled={busy === 'all'}
              className="rounded-md bg-red-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-60"
            >
              {busy === 'all' ? 'Signing out…' : 'Yes, sign out'}
            </button>
          </div>
        </div>
      )}

      {sessions === null ? (
        <Skeleton />
      ) : sessions.length === 0 ? (
        <div className="rounded-lg border border-dashed border-gray-300 bg-white p-8 text-center text-sm text-gray-600">
          No active sessions.
        </div>
      ) : (
        <ul className="space-y-2">
          {sessions.map((s) => (
            <li
              key={s.id}
              className="rounded-lg border border-gray-200 bg-white p-4"
            >
              <Row
                session={s}
                busy={busy === s.id}
                onRevoke={() => void revokeOne(s.id, s.current)}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function Row({
  session,
  busy,
  onRevoke,
}: {
  session: UserSessionResponse;
  busy: boolean;
  onRevoke: () => void;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <Monitor className="h-4 w-4 text-gray-500" strokeWidth={2} />
          <span className="text-sm font-medium text-gray-900">
            {session.deviceLabel}
          </span>
          {session.current && (
            <span className="inline-flex items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-green-800">
              <CheckCircle2 className="h-2.5 w-2.5" strokeWidth={2.5} />
              Current
            </span>
          )}
        </div>
        <div className="mt-1 text-xs text-gray-500">
          Last active {formatRelative(session.lastUsedAt)}
          {session.ip && <> · from {session.ip}</>}
        </div>
        {session.userAgent && (
          <div
            className="mt-1 truncate text-[11px] text-gray-400"
            title={session.userAgent}
          >
            {session.userAgent}
          </div>
        )}
      </div>
      <button
        type="button"
        onClick={onRevoke}
        disabled={busy}
        className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-2.5 py-1 text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-60"
      >
        <LogOut className="h-3 w-3" strokeWidth={2} />
        {busy ? 'Signing out…' : session.current ? 'Sign out' : 'Sign out'}
      </button>
    </div>
  );
}

function Skeleton() {
  return (
    <div className="space-y-2">
      <div className="h-16 animate-pulse rounded-lg border border-gray-100 bg-gray-50" />
      <div className="h-16 animate-pulse rounded-lg border border-gray-100 bg-gray-50" />
    </div>
  );
}
