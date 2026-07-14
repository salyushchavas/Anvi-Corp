'use client';

import { FormEvent, Suspense, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import toast from 'react-hot-toast';
import api from '@/lib/careers/api';
import { useAuth } from '@/lib/careers/auth-context';
import AuthLayout from '@/components/dashboard/AuthLayout';
import { BRAND } from '@/lib/careers/brand';
import type { VerifyEmailResponse } from '@/types';

export default function VerifyEmailPage() {
  return (
    <Suspense fallback={<AuthLayout title="Verify your email" subtitle="Loading…"><></></AuthLayout>}>
      <VerifyEmailInner />
    </Suspense>
  );
}

function VerifyEmailInner() {
  const router = useRouter();
  const params = useSearchParams();
  const { user, updateUser } = useAuth();

  // Email source-of-truth: URL param (set by the register flow) wins over the
  // logged-in user, since an unauthenticated visitor (e.g. arriving via a
  // verification link in their inbox) may not yet have a session.
  const initialEmail = params.get('email') ?? user?.email ?? '';
  const returnTo = safeReturnTo(params.get('returnTo'));

  const [email, setEmail] = useState(initialEmail);
  // SECURITY — the verification code is never round-tripped through the API.
  // The user reads the 6-digit code from their email and types it here.
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [resending, setResending] = useState(false);

  // If the user is already verified (e.g. they refreshed the page after
  // verifying, or arrived here via a stale link), skip straight to their
  // landing destination.
  useEffect(() => {
    if (user?.emailVerified) {
      router.replace(returnTo ?? '/careers/intern');
    }
  }, [user?.emailVerified, returnTo, router]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!/^\d{6}$/.test(code)) {
      setError('Enter the 6-digit code from your email.');
      return;
    }
    setSubmitting(true);
    try {
      const res = await api.post<VerifyEmailResponse>('/auth/verify-email', {
        email,
        code,
      });
      updateUser({
        emailVerified: true,
        applicantId: res.data.applicantId,
      });
      toast.success(
        res.data.applicantId
          ? `Verified — your Applicant ID is ${res.data.applicantId}`
          : 'Email verified'
      );
      router.replace(returnTo ?? '/careers/intern');
    } catch (err: any) {
      setError(err?.response?.data?.error ?? 'Verification failed.');
    } finally {
      setSubmitting(false);
    }
  }

  async function onResend() {
    setError(null);
    setResending(true);
    try {
      await api.post('/auth/resend-verification', { email });
      toast.success('If the account exists and is unverified, a new code is on its way.');
    } catch (err: any) {
      setError(err?.response?.data?.error ?? "Couldn't resend the code.");
    } finally {
      setResending(false);
    }
  }

  return (
    <AuthLayout
      title="Verify your email"
      subtitle={`Enter the 6-digit code we sent to your inbox to unlock internships and receive your ${BRAND.name} Applicant ID.`}
    >
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
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            className="w-full rounded border border-gray-300 px-3 py-2 focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            autoComplete="email"
          />
        </div>
        <div>
          <label htmlFor="code" className="mb-1 block text-sm font-medium text-gray-700">
            Verification code
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
            className="w-full rounded border border-gray-300 px-3 py-2 tracking-[0.5em] text-center text-lg focus:border-accent focus:outline-none focus:ring-1 focus:ring-accent"
            autoComplete="one-time-code"
          />
        </div>
        <button
          type="submit"
          disabled={submitting || code.length !== 6}
          className="w-full rounded-full bg-gradient-to-r from-accent to-accent-dark px-4 py-2.5 font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg disabled:opacity-50 disabled:shadow-none"
        >
          {submitting ? 'Verifying…' : 'Verify email'}
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

function safeReturnTo(raw: string | null): string | null {
  if (!raw) return null;
  const decoded = decodeURIComponent(raw);
  if (!decoded.startsWith('/') || decoded.startsWith('//')) return null;
  return decoded;
}
