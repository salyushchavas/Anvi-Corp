'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth-context';
import { getDashboardForUser } from '@/lib/role-routing';

export default function HomePage() {
  const router = useRouter();
  const { user, isLoading } = useAuth();

  useEffect(() => {
    if (isLoading) return;
    if (user) router.replace(getDashboardForUser(user));
    else router.replace('/careers/login');
  }, [user, isLoading, router]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50">
      <div
        aria-label="Loading"
        className="h-10 w-10 animate-spin rounded-full border-4 border-accent border-t-transparent"
      />
    </div>
  );
}
