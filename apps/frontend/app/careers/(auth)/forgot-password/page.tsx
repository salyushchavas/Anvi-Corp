'use client';

import { FormEvent, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import AuthLayout from '@/components/dashboard/AuthLayout';
import PasswordInput from '@/components/careers/PasswordInput';
import api from '@/lib/careers/api';

type Step = 'email' | 'reset' | 'done';

/**
 * Forgot-password wizard — matches the registration verify-email UX:
 *   1. enter email → backend emails a 6-digit code (silent no-op if unknown)
 *   2. type the code + new + confirm → backend validates + BCrypt-hashes
 *   3. success screen auto-redirects to /careers/login
 * The user never leaves the app; no link click required.
 */
export default function ForgotPasswordPage() {
  const router = useRouter();
  const [step, setStep] = useState<Step>('email');
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [resending, setResending] = useState(false);

  // Auto-redirect after success so the user lands on login without an extra click.
  useEffect(() => {
    if (step !== 'done') return;
    const t = setTimeout(() => router.replace('/careers/login'), 1500);
    return () => clearTimeout(t);
  }, [step, router]);

  async function sendCode(currentEmail: string): Promise<boolean> {
    setError(null);
    try {
      await api.post('/auth/forgot-password', { email: currentEmail });
      return true;
    } catch {
      // Backend always returns 200; a network failure lands here. Still show
      // the neutral notice so an attacker can't distinguish this from the
      // "email not registered" path.
      return true;
    }
  }

  async function onSubmitEmail(e: FormEvent) {
    e.preventDefault();
    setLoading(true);
    const ok = await sendCode(email);
    setLoading(false);
    if (ok) {
      setNotice('If that email is registered, a 6-digit code has been sent. Check your inbox.');
      setStep('reset');
    }
  }

  async function onSubmitReset(e: FormEvent) {
    e.preventDefault();
    setError(null);

    if (!/^\d{6}$/.test(code)) {
      setError('Enter the 6-digit code from your email.');
      return;
    }
    if (newPassword.length < 8) {
      setError('Password must be at least 8 characters.');
      return;
    }
    if (newPassword !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }

    setLoading(true);
    try {
      await api.post('/auth/reset-password', { email, code, newPassword });
      setStep('done');
    } catch (err: any) {
      setError(err?.response?.data?.error ?? 'Invalid or expired code.');
    } finally {
      setLoading(false);
    }
  }

  async function onResend() {
    setResending(true);
    const ok = await sendCode(email);
    setResending(false);
    if (ok) {
      setNotice('A fresh code has been sent (if the email is registered).');
    }
  }

  if (step === 'done') {
    return (
      <AuthLayout title="Password reset" subtitle="You're all set.">
        <div className="rounded border border-green-200 bg-green-50 p-3 text-sm text-green-800">
          Password reset successfully. Redirecting to sign in…
        </div>
      </AuthLayout>
    );
  }

  if (step === 'reset') {
    return (
      <AuthLayout
        title="Enter your reset code"
        subtitle="We emailed a 6-digit code — enter it with your new password."
      >
        {notice && (
          <div className="mb-4 rounded border border-green-200 bg-green-50 p-3 text-sm text-green-800">
            {notice}
          </div>
        )}
        {error && (
          <div className="mb-4 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
            {error}
          </div>
        )}
        <form onSubmit={onSubmitReset} className="space-y-4">
          <div>
            <label htmlFor="email" className="mb-1 block text-sm font-medium text-gray-700">
              Email
            </label>
            <input
              id="email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="email"
              className="w-full rounded border border-gray-300 px-3 py-2 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label htmlFor="code" className="mb-1 block text-sm font-medium text-gray-700">
              6-digit code
            </label>
            <input
              id="code"
              type="text"
              inputMode="numeric"
              pattern="\d{6}"
              maxLength={6}
              required
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
              placeholder="123456"
              autoComplete="one-time-code"
              className="w-full rounded border border-gray-300 px-3 py-2 text-center text-lg tracking-[0.5em] focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label htmlFor="newPassword" className="mb-1 block text-sm font-medium text-gray-700">
              New password (min 8 characters)
            </label>
            <PasswordInput
              id="newPassword"
              required
              minLength={8}
              autoComplete="new-password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              className="w-full rounded border border-gray-300 px-3 py-2 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <div>
            <label htmlFor="confirmPassword" className="mb-1 block text-sm font-medium text-gray-700">
              Confirm new password
            </label>
            <PasswordInput
              id="confirmPassword"
              required
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              className="w-full rounded border border-gray-300 px-3 py-2 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            />
          </div>
          <button
            type="submit"
            disabled={loading}
            className="w-full rounded-full bg-accent hover:bg-accent-dark px-4 py-2.5 font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg disabled:opacity-50 disabled:shadow-none"
          >
            {loading ? 'Resetting…' : 'Reset password'}
          </button>
        </form>
        <div className="mt-4 flex flex-col gap-2 text-center text-sm">
          <button
            type="button"
            onClick={() => void onResend()}
            disabled={resending || !email}
            className="text-primary-700 hover:text-primary-800 hover:underline disabled:opacity-50"
          >
            {resending ? 'Sending…' : "Didn't get a code? Resend"}
          </button>
          <button
            type="button"
            onClick={() => {
              setStep('email');
              setNotice(null);
              setError(null);
              setCode('');
              setNewPassword('');
              setConfirmPassword('');
            }}
            className="text-gray-500 hover:text-gray-700 hover:underline"
          >
            Use a different email
          </button>
          <Link
            href="/careers/login"
            className="text-gray-500 hover:text-gray-700 hover:underline"
          >
            Back to sign in
          </Link>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      title="Reset your password"
      subtitle="We'll email you a 6-digit code."
    >
      <form onSubmit={onSubmitEmail} className="space-y-4">
        <p className="text-sm text-gray-600">
          Enter your email and we&apos;ll send a 6-digit code to reset your password.
        </p>
        <div>
          <label htmlFor="email" className="mb-1 block text-sm font-medium text-gray-700">
            Email
          </label>
          <input
            id="email"
            type="email"
            required
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="w-full rounded border border-gray-300 px-3 py-2 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
        </div>
        <button
          type="submit"
          disabled={loading || !email}
          className="w-full rounded-full bg-accent hover:bg-accent-dark px-4 py-2.5 font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg disabled:opacity-50 disabled:shadow-none"
        >
          {loading ? 'Sending…' : 'Send reset code'}
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
    </AuthLayout>
  );
}
