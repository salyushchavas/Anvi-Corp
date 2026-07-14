'use client';

import { ReactNode, useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth-context';
import { getDashboardForUser } from '@/lib/role-routing';
import type { UserRole } from '@/types';

interface Props {
  children: ReactNode;
  /**
   * If provided, the user must have at least one of these roles.
   * If omitted, any authenticated user passes.
   */
  requiredRoles?: UserRole[];
}

export default function ProtectedRoute({ children, requiredRoles }: Props) {
  const router = useRouter();
  const pathname = usePathname();
  const { user, isLoading } = useAuth();

  useEffect(() => {
    if (isLoading) return;

    if (!user) {
      const search = typeof window !== 'undefined' ? window.location.search : '';
      const current = pathname + (search || '');
      const returnTo = encodeURIComponent(current);
      router.replace(`/careers/login?returnTo=${returnTo}`);
      return;
    }

    if (requiredRoles && requiredRoles.length > 0) {
      const ok = user.roles?.some((r) => requiredRoles.includes(r));
      if (!ok) {
        router.replace(getDashboardForUser(user));
      }
    }
  }, [isLoading, user, requiredRoles, router, pathname]);

  const roleMismatch =
    !isLoading &&
    user &&
    requiredRoles &&
    requiredRoles.length > 0 &&
    !user.roles?.some((r) => requiredRoles.includes(r));

  if (isLoading || !user || roleMismatch) {
    return (
      <div className="flex min-h-[40vh] items-center justify-center">
        <div
          aria-label="Loading"
          className="h-10 w-10 animate-spin rounded-full border-4 border-accent border-t-transparent"
        />
      </div>
    );
  }

  return <>{children}</>;
}
