'use client';

import type { ReactNode } from 'react';
import Link from 'next/link';
import { ChevronLeft, UserCircle } from 'lucide-react';
import ProtectedRoute from '@/components/ProtectedRoute';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import { useAuth } from '@/lib/careers/auth-context';

/**
 * Wraps the shared {@code /careers/messages} route in {@code DashboardLayout}
 * so every role (Intern, ERM, Trainer, Manager, Evaluator) gets the same
 * consistent chrome — sidebar, top bar with bell + mail — regardless of
 * which sidebar Notifications button they clicked to get here. The role-aware
 * {@code DashboardSidebar} adapts nav to the caller's roles.
 *
 * <p>B2 additions — a role-aware "back to dashboard" link + (for interns) a
 * link to the Profile page directly from the mail shell. Both live above the
 * page content so they're visible without disturbing the existing message
 * feed below.</p>
 */
export default function MessagesLayout({ children }: { children: ReactNode }) {
  return (
    <ProtectedRoute>
      <DashboardLayout>
        <MailShellNav />
        {children}
      </DashboardLayout>
    </ProtectedRoute>
  );
}

function MailShellNav() {
  const { user } = useAuth();
  const roles = user?.roles ?? [];
  // Role-aware landing target. Prefer the intern dashboard for candidates;
  // fall back to the first matching staff dashboard; last resort /careers.
  const dashboardHref = roles.includes('INTERN')
    ? '/careers/intern'
    : roles.includes('ERM')
      ? '/careers/erm'
      : roles.includes('TRAINER')
        ? '/careers/trainer'
        : roles.includes('EVALUATOR')
          ? '/careers/evaluator'
          : roles.includes('MANAGER')
            ? '/careers/manager'
            : roles.includes('REPORTING_MANAGER')
              ? '/careers/reporting-manager'
              : roles.includes('SUPER_ADMIN')
                ? '/careers/admin'
                : '/careers';
  const showProfileLink = roles.includes('INTERN');

  return (
    <div className="mx-auto flex max-w-5xl items-center justify-between gap-3 px-6 pt-4 text-xs text-slate-600">
      <Link
        href={dashboardHref}
        className="inline-flex items-center gap-1 hover:text-slate-900"
      >
        <ChevronLeft className="h-3.5 w-3.5" />
        Back to dashboard
      </Link>
      {showProfileLink && (
        <Link
          href="/careers/intern/profile"
          className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 font-medium text-slate-700 hover:bg-slate-50"
        >
          <UserCircle className="h-3.5 w-3.5" />
          My Profile
        </Link>
      )}
    </div>
  );
}
