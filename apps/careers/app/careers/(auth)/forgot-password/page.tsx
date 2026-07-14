'use client';

import { FormEvent, useState } from 'react';
import Link from 'next/link';
import AuthLayout from '@/components/dashboard/AuthLayout';
import api from '@/lib/api';

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setLoading(true);
    try {
      await api.post('/auth/forgot-password', { email });
    } catch {
      // Backend always returns 200 anyway. We deliberately surface no error
      // to avoid leaking whether the email is registered.
    } finally {
      setSubmitted(true);
      setLoading(false);
    }
  }

  return (
    <AuthLayout
      title="Reset your password"
      subtitle="We'll email you a reset link"
    >
      {submitted ? (
        <div>
          <div className="mb-4 rounded border border-green-200 bg-green-50 p-3 text-sm text-green-800">
            If that email is registered, a reset link has been sent.
          </div>
          <p className="text-xs text-gray-500">
            Dev note: token is logged to backend console for now.
          </p>
          <div className="mt-6 text-center text-sm">
            <Link
              href="/careers/login"
              className="font-medium text-primary-700 hover:text-primary-800 hover:underline"
            >
              Back to sign in
            </Link>
          </div>
        </div>
      ) : (
        <form onSubmit={onSubmit} className="space-y-4">
          <p className="text-sm text-gray-600">
            Enter your email and we&apos;ll send instructions to reset your password.
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
            disabled={loading}
            className="w-full rounded-full bg-gradient-to-r from-accent to-accent-dark px-4 py-2.5 font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg disabled:opacity-50 disabled:shadow-none"
          >
            {loading ? 'Sending…' : 'Send reset link'}
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
      )}
    </AuthLayout>
  );
}
