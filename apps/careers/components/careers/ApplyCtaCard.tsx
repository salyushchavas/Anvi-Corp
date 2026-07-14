'use client';

import Link from 'next/link';
import { useAuth } from '@/lib/auth-context';

/**
 * GAP_REPORT A4 — auth-aware apply CTA for /careers/openings/[slug].
 *
 * Public browse stays public (the server-rendered page is unchanged); this
 * client component just renders the right call-to-action so unauth /
 * unverified visitors don't bounce off the 403 EMAIL_UNVERIFIED on submit:
 *
 *   - Not logged in           → "Log in to apply" → /careers/login?returnTo=…
 *   - Logged in, !verified    → "Verify your email to apply" → /careers/verify-email?returnTo=…
 *   - Logged in, verified     → "Apply Now" → /careers/openings/{slug}/apply
 *
 * The backend ApplicationService.apply remains the authoritative gate; this
 * is honest UX, not authorization.
 */
export default function ApplyCtaCard({ slug }: { slug: string }) {
  const { user, isLoading } = useAuth();
  const applyPath = `/careers/openings/${slug}/apply`;
  const verifyPath = `/careers/verify-email?returnTo=${encodeURIComponent(applyPath)}`;
  const loginPath = `/careers/login?returnTo=${encodeURIComponent(applyPath)}`;

  const buttonClass =
    'block w-full rounded-full bg-gradient-to-r from-accent to-accent-dark px-4 py-3 text-center text-sm font-semibold text-white shadow-glow-accent transition hover:shadow-glow-accent-lg';

  // While auth state is loading, render a neutral disabled button so the
  // server-rendered shell doesn't visibly swap CTAs on hydration.
  if (isLoading) {
    return (
      <div className="sticky top-6 rounded-lg border border-slate-200 bg-white p-6">
        <p className="mb-4 text-sm text-slate-600">
          Ready to apply? You&apos;ll need a resume (PDF or Word).
        </p>
        <span
          className={`${buttonClass} cursor-not-allowed opacity-60`}
          aria-disabled="true"
        >
          Apply Now
        </span>
      </div>
    );
  }

  if (!user) {
    return (
      <div className="sticky top-6 rounded-lg border border-slate-200 bg-white p-6">
        <p className="mb-4 text-sm text-slate-600">
          Sign in to apply. You&apos;ll need a resume (PDF or Word).
        </p>
        <Link href={loginPath} className={buttonClass}>
          Log in to apply
        </Link>
        <p className="mt-3 text-center text-xs text-slate-500">
          New here?{' '}
          <Link
            href={`/careers/register?returnTo=${encodeURIComponent(applyPath)}`}
            className="font-medium text-primary-700 hover:underline"
          >
            Create an account
          </Link>
        </p>
      </div>
    );
  }

  if (user.emailVerified === false) {
    return (
      <div className="sticky top-6 rounded-lg border border-amber-200 bg-amber-50 p-6">
        <p className="mb-2 text-sm font-medium text-amber-900">
          Verify your email to apply
        </p>
        <p className="mb-4 text-sm text-amber-800">
          We confirm your email before you can submit an application and
          receive your Skyzen Applicant ID.
        </p>
        <Link href={verifyPath} className={buttonClass}>
          Verify email
        </Link>
      </div>
    );
  }

  return (
    <div className="sticky top-6 rounded-lg border border-slate-200 bg-white p-6">
      <p className="mb-4 text-sm text-slate-600">
        Ready to apply? You&apos;ll need a resume (PDF or Word).
      </p>
      <Link href={applyPath} className={buttonClass}>
        Apply Now
      </Link>
    </div>
  );
}
