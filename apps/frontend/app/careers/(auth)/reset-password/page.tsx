'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import AuthLayout from '@/components/dashboard/AuthLayout';

/**
 * Legacy URL-token reset endpoint. The forgot-password flow now uses a
 * 6-digit code entered on /careers/forgot-password (no email link click),
 * so this page just shepherds anyone landing here from an old inbox link
 * back to the wizard.
 */
export default function ResetPasswordPage() {
  const router = useRouter();

  useEffect(() => {
    const t = setTimeout(() => router.replace('/careers/forgot-password'), 1500);
    return () => clearTimeout(t);
  }, [router]);

  return (
    <AuthLayout
      title="Reset your password"
      subtitle="This link is no longer used."
    >
      <div className="rounded border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
        Password reset moved to a 6-digit code. Redirecting to the reset page…
      </div>
      <div className="mt-4 text-center text-sm">
        <Link
          href="/careers/forgot-password"
          className="font-medium text-primary-700 hover:text-primary-800 hover:underline"
        >
          Continue to reset password
        </Link>
      </div>
    </AuthLayout>
  );
}
