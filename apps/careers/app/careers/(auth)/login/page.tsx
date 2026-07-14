'use client';

import { FormEvent, Suspense, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import AuthLayout from '@/components/dashboard/AuthLayout';
import { useAuth } from '@/lib/auth-context';
import { BRAND } from '@/lib/brand';
import { getDashboardForUser, returnToIsAllowedForUser } from '@/lib/role-routing';

export default function LoginPage() {
  return (
    <Suspense fallback={<AuthLayout title="Welcome back" subtitle="Loading…"><div /></AuthLayout>}>
      <LoginInner />
    </Suspense>
  );
}

function LoginInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (searchParams?.get('reason') === 'idle') {
      setNotice('You were signed out due to inactivity. Please sign in again.');
    }
  }, [searchParams]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const user = await login(email, password);
      const returnTo = safeReturnTo();
      // Defense-in-depth against a stale {@code ?returnTo} sneaking in
      // (the sign-out race previously stamped the prior role's path).
      // Honour returnTo ONLY when the freshly authenticated user has
      // the role required to render it; otherwise drop it and land on
      // the role's own dashboard.
      const target = returnTo && returnToIsAllowedForUser(returnTo, user)
        ? returnTo
        : getDashboardForUser(user);
      router.replace(target);
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? 'Login failed';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }

  function safeReturnTo(): string | null {
    if (typeof window === 'undefined') return null;
    const raw = new URLSearchParams(window.location.search).get('returnTo');
    if (!raw) return null;
    const decoded = decodeURIComponent(raw);
    if (!decoded.startsWith('/') || decoded.startsWith('//')) return null;
    return decoded;
  }

  return (
    <AuthLayout title="Welcome back" subtitle={`Sign in to your ${BRAND.productName} account`}>
      {notice && (
        <div className="mb-4 rounded border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
          {notice}
        </div>
      )}
      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {error}
        </div>
      )}
      <form onSubmit={onSubmit} className="space-y-4">
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
        <div>
          <label htmlFor="password" className="mb-1 block text-sm font-medium text-gray-700">
            Password
          </label>
          <input
            id="password"
            type="password"
            required
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="w-full rounded border border-gray-300 px-3 py-2 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
          />
        </div>
        <button
          type="submit"
          disabled={loading}
          className="w-full rounded-full bg-gradient-to-r from-accent to-accent-dark px-4 py-2.5 font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg disabled:opacity-50 disabled:shadow-none"
        >
          {loading ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      <div className="mt-6 flex justify-between text-sm">
        <Link
          href="/careers/register"
          className="font-medium text-primary-700 hover:text-primary-800 hover:underline"
        >
          Create an account
        </Link>
        <Link
          href="/careers/forgot-password"
          className="font-medium text-primary-700 hover:text-primary-800 hover:underline"
        >
          Forgot password?
        </Link>
      </div>
    </AuthLayout>
  );
}
