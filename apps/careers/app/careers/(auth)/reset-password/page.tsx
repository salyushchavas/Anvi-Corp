'use client';

import { FormEvent, Suspense, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import AuthLayout from '@/components/dashboard/AuthLayout';
import api from '@/lib/api';

export default function ResetPasswordPage() {
  return (
    <AuthLayout
      title="Set a new password"
      subtitle="Choose a strong password"
    >
      <Suspense
        fallback={<div className="h-6 w-32 animate-pulse rounded bg-gray-200" />}
      >
        <ResetPasswordForm />
      </Suspense>
    </AuthLayout>
  );
}

function ResetPasswordForm() {
  const router = useRouter();
  const params = useSearchParams();
  const token = params.get('token') ?? '';

  const [newPassword, setNewPassword] = useState('');
  const [confirmNewPassword, setConfirmNewPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    if (!token) {
      setError('Missing or invalid token.');
      return;
    }
    if (newPassword.length < 8) {
      setError('Password must be at least 8 characters.');
      return;
    }
    if (newPassword !== confirmNewPassword) {
      setError('Passwords do not match.');
      return;
    }

    setLoading(true);
    try {
      await api.post('/auth/reset-password', { token, newPassword });
      setSuccess(true);
      setTimeout(() => router.replace('/careers/login'), 1500);
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? 'Invalid or expired token';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }

  return success ? (
    <div className="rounded border border-green-200 bg-green-50 p-3 text-sm text-green-800">
      Password reset successful. Redirecting to sign in…
    </div>
  ) : (
    <form onSubmit={onSubmit} className="space-y-4">
      {error && (
        <div className="rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {error}
        </div>
      )}
      <div>
        <label htmlFor="newPassword" className="mb-1 block text-sm font-medium text-gray-700">
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
          htmlFor="confirmNewPassword"
          className="mb-1 block text-sm font-medium text-gray-700"
        >
          Confirm new password
        </label>
        <input
          id="confirmNewPassword"
          type="password"
          required
          autoComplete="new-password"
          value={confirmNewPassword}
          onChange={(e) => setConfirmNewPassword(e.target.value)}
          className="w-full rounded border border-gray-300 px-3 py-2 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
        />
      </div>
      <button
        type="submit"
        disabled={loading}
        className="w-full rounded-full bg-gradient-to-r from-accent to-accent-dark px-4 py-2.5 font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg disabled:opacity-50 disabled:shadow-none"
      >
        {loading ? 'Saving…' : 'Reset password'}
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
