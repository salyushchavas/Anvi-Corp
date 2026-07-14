'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { CheckCircle2, KeyRound, ShieldAlert } from 'lucide-react';
import api from '@/lib/api';
import { useAuth } from '@/lib/auth-context';

/**
 * First-login forced password-change screen. Reached automatically
 * whenever the authenticated user has {@code mustChangePassword=true} —
 * the AuthProvider useEffect bounces every other route to this page,
 * and the backend's ForcePasswordChangeFilter returns 403
 * PASSWORD_CHANGE_REQUIRED on every other API call until the flag
 * clears. Calls the existing
 * {@code POST /api/v1/users/me/change-password} endpoint; on success the
 * server flips the flag to false, the user is reloaded via the refresh
 * hook, and they land on /careers (their role dashboard).
 */
export default function ForceChangePasswordPage() {
  const { user, isLoading, logout } = useAuth();
  const router = useRouter();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  if (isLoading) {
    return (
      <main className="mx-auto flex min-h-[60vh] max-w-md items-center justify-center p-6">
        <div className="h-32 w-full animate-pulse rounded-lg bg-slate-100" />
      </main>
    );
  }

  // The AuthProvider bounces unauthenticated users away from this route,
  // but defend against a direct nav while logged out anyway — there's
  // nothing for them to change.
  if (!user) {
    return (
      <main className="mx-auto max-w-md p-6">
        <p className="rounded-md border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
          Sign in to change your password.
        </p>
      </main>
    );
  }

  // The user may have already changed their password but landed here via
  // a stale tab — let them out instead of trapping them.
  if (!user.mustChangePassword) {
    if (typeof window !== 'undefined') router.replace('/careers');
    return null;
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr(null);
    if (newPassword.length < 8) {
      setErr('New password must be at least 8 characters.');
      return;
    }
    if (newPassword !== confirmPassword) {
      setErr("New password and confirmation don't match.");
      return;
    }
    if (newPassword === currentPassword) {
      setErr('New password must be different from the temporary one.');
      return;
    }
    setSubmitting(true);
    try {
      await api.post('/api/v1/users/me/change-password', {
        currentPassword,
        newPassword,
      });
      // Hard reload so the AuthProvider re-fetches /auth/me, picks up
      // mustChangePassword=false, and the route guard stops bouncing.
      window.location.assign('/careers');
    } catch (e2) {
      const ax = e2 as { response?: { status?: number; data?: { error?: string; message?: string } }; message?: string };
      const msg = ax.response?.data?.error ?? ax.response?.data?.message
        ?? ax.message ?? 'Could not change password';
      setErr(msg);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="mx-auto max-w-md p-6">
      <div className="mb-6 flex items-start gap-3 rounded-lg border border-amber-200 bg-amber-50 p-4">
        <ShieldAlert className="mt-0.5 h-5 w-5 shrink-0 text-amber-700" strokeWidth={2} />
        <div>
          <h1 className="text-base font-semibold text-amber-900">
            Set a new password
          </h1>
          <p className="mt-1 text-xs text-amber-900/90">
            Your account was created with a temporary password. You need
            to set a permanent one before continuing.
          </p>
        </div>
      </div>

      <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <div className="mb-4 flex items-center gap-2">
          <KeyRound className="h-4 w-4 text-brand-700" />
          <h2 className="text-sm font-semibold text-slate-900">
            Signed in as {user.email}
          </h2>
        </div>
        <form onSubmit={submit} className="space-y-4">
          <Field
            label="Temporary password"
            type="password"
            value={currentPassword}
            onChange={setCurrentPassword}
            placeholder="The password you were given"
            autoComplete="current-password"
            required
          />
          <Field
            label="New password (min 8 chars)"
            type="password"
            value={newPassword}
            onChange={setNewPassword}
            placeholder="At least 8 characters"
            autoComplete="new-password"
            required
          />
          <Field
            label="Confirm new password"
            type="password"
            value={confirmPassword}
            onChange={setConfirmPassword}
            placeholder="Re-enter your new password"
            autoComplete="new-password"
            required
          />
          {err && (
            <p className="rounded-md border border-red-200 bg-red-50 p-2 text-xs text-red-800">
              {err}
            </p>
          )}
          <div className="flex items-center justify-between gap-3 pt-2">
            <button
              type="button"
              onClick={() => { logout(); router.replace('/careers/login'); }}
              className="text-xs font-medium text-slate-500 hover:text-slate-700"
            >
              Sign out instead
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="inline-flex items-center gap-1.5 rounded-md bg-brand-700 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-800 disabled:opacity-60"
            >
              <CheckCircle2 className="h-4 w-4" />
              {submitting ? 'Saving…' : 'Set new password'}
            </button>
          </div>
        </form>
      </section>
    </main>
  );
}

function Field({
  label, type, value, onChange, placeholder, autoComplete, required,
}: {
  label: string;
  type: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  autoComplete?: string;
  required?: boolean;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-semibold text-slate-700">
        {label}{required && <span className="text-red-500"> *</span>}
      </span>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        autoComplete={autoComplete}
        required={required}
        className="w-full rounded-md border border-slate-200 px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
      />
    </label>
  );
}
