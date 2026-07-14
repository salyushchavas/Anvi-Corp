'use client';

import type { ReactNode } from 'react';
import DashboardLayout from '@/components/dashboard/DashboardLayout';
import SiteLayout from '@/components/SiteLayout';
import { useAuth } from '@/lib/auth-context';

interface Props {
  title: string;
  children: ReactNode;
}

/**
 * Renders the openings/apply pages inside the candidate DashboardLayout for
 * logged-in candidates (so they keep the same sidebar+topbar chrome as the
 * rest of the candidate dashboard), and the public SiteLayout for anonymous
 * visitors or any non-candidate role.
 *
 * Avoids flashing the wrong layout during the brief auth-rehydration window
 * by rendering a centered spinner while {@link useAuth} is still loading.
 */
export default function AdaptiveCareersLayout({ title, children }: Props) {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div
          aria-label="Loading"
          className="h-10 w-10 animate-spin rounded-full border-4 border-accent border-t-transparent"
        />
      </div>
    );
  }

  const isCandidate = !!user && (user.roles?.includes('INTERN') || user.roles?.includes('INTERN'));

  if (isCandidate) {
    return <DashboardLayout title={title}>{children}</DashboardLayout>;
  }

  // Anonymous OR any non-candidate role: public site header + the same outer
  // chrome the old openings layout.tsx provided (bg + max-width center).
  return (
    <SiteLayout>
      <div className="bg-gray-50">
        <div className="mx-auto max-w-7xl px-6 py-10">{children}</div>
      </div>
    </SiteLayout>
  );
}
