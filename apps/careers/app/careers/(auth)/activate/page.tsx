'use client';

import { FormEvent, Suspense, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import AuthLayout from '@/components/dashboard/AuthLayout';
import api from '@/lib/api';

/**
 * Public landing for the admin-issued staff activation link. The route
 * is permitted in SecurityConfig; the link's token is validated against
 * a hashed-at-rest row server-side. On success the API returns a normal
 * AuthResponse so the user lands authenticated.
 */
export default function ActivatePage() {
  return (
    <AuthLayout
      title="Activate your account"
      subtitle="Set a password to finish setup"
    >
      <Suspense
        fallback={<div className="h-6 w-32 animate-pulse rounded bg-gray-200" />}
      >
        <ActivateForm />
      </Suspense>
    </AuthLayout>
  );
}

interface ValidationResult {
  email: string;
  fullName?: string | null;
  role?: string | null;
  expiresAt?: string | null;
}

function ActivateForm() {
  const router = useRouter();
  const params = useSearchParams();
  const token = params.get('token') ?? '';

  const [validation, setValidation] = useState<ValidationResult | null>(null);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [validating, setValidating] = useState(true);
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  // One-shot validate on mount. The endpoint is GET so it doesn't burn
  // the token; we can show "expired/used" without consuming anything.
  useEffect(() => {
    if (!token) {
      setValidationError('Missing or invalid activation token.');
      setValidating(false);
      return;
    }
    (async () => {
      try {
        const res = await api.get<ValidationResult>(
          `/auth/activate/validate?token=${encodeURIComponent(token)}`,
        );
        setValidation(res.data);
      } catch (err: any) {
        const msg = err?.response?.data?.error
          ?? 'This activation link is invalid or has expired.';
        setValidationError(msg);
      } finally {
        setValidating(false);
      }
    })();
  }, [token]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (newPassword.length < 8) {
      setError('Password must be at least 8 characters.');
      return;
    }
    if (newPassword !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }
    setSubmitting(true);
    try {
      // POST /auth/activate redeems the token + sets the password +
      // returns a session — but we intentionally do NOT auto-login the
      // user here: they should land on /careers/login fresh so the
      // AuthProvider initializes cleanly with their new credentials.
      await api.post('/auth/activate', { token, newPassword });
      setSuccess(true);
      setTimeout(() => router.replace('/careers/login'), 1500);
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? 'Could not activate your account.';
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  }

  if (validating) {
    return (
      <div className="space-y-3">
        <div className="h-6 w-48 animate-pulse rounded bg-gray-200" />
        <div className="h-10 w-full animate-pulse rounded bg-gray-100" />
      </div>
    );
  }

  if (validationError) {
    return (
      <div className="space-y-4">
        <div className="rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {validationError}
        </div>
        <p className="text-sm text-gray-600">
          Ask your admin to re-send the invite — they can issue a fresh
          link from the user management screen.
        </p>
        <Link
          href="/careers/login"
          className="block text-center text-sm font-medium text-primary-700 hover:text-primary-800 hover:underline"
        >
          Back to sign in
        </Link>
      </div>
    );
  }

  if (success) {
    return (
      <div className="rounded border border-green-200 bg-green-50 p-3 text-sm text-green-800">
        Account activated. Redirecting to sign in…
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      {validation && (
        <div className="rounded border border-gray-200 bg-gray-50 p-3 text-xs text-gray-700">
          Setting the password for{' '}
          <strong className="text-gray-900">{validation.email}</strong>
          {validation.role ? ` (${validation.role})` : ''}.
        </div>
      )}
      {error && (
        <div className="rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {error}
        </div>
      )}
      <div>
        <label
          htmlFor="newPassword"
          className="mb-1 block text-sm font-medium text-gray-700"
        >
          New password (min 8 characters)
        </label>
        <input
          id="newPassword"
          type="password"
          required
          minLength={8}
          autoComplete="new-password"
          value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)}
          className="w-full rounded border border-gray-300 px-3 py-2 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
        />
      </div>
      <div>
        <label
          htmlFor="confirmPassword"
          className="mb-1 block text-sm font-medium text-gray-700"
        >
          Confirm password
        </label>
        <input
          id="confirmPassword"
          type="password"
          required
          autoComplete="new-password"
          value={confirmPassword}
          onChange={(e) => setConfirmPassword(e.target.value)}
          className="w-full rounded border border-gray-300 px-3 py-2 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
        />
      </div>
      <button
        type="submit"
        disabled={submitting}
        className="w-full rounded-full bg-gradient-to-r from-accent to-accent-dark px-4 py-2.5 font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg disabled:opacity-50 disabled:shadow-none"
      >
        {submitting ? 'Activating…' : 'Activate account'}
      </button>
      <div className="text-center text-sm">
        <Link
          href="/careers/login"
          className="font-medium text-primary-700 hover:text-primary-800 hover:underline"
        >
          Back to sign in
        </Link>
      </div>
    </form>
  );
}
