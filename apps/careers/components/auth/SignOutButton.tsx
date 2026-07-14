'use client';

import { LogOut } from 'lucide-react';
import { useAuth } from '@/lib/auth-context';
import { broadcastSessionEvent } from '@/lib/session-broadcast';

/**
 * Shared sign-out control. Funnels through {@link useAuth().logout} (which
 * clears stored tokens + in-memory user) and then HARD-navigates to the
 * login page via {@code window.location.replace}.
 *
 * <p>The hard navigation is load-bearing. The previous implementation
 * called {@code logout()} (which sets {@code user=null}) and then used
 * {@code router.replace('/careers/login')}. That sequence let the
 * source page's {@code ProtectedRoute} re-render with {@code user=null}
 * BEFORE the Next.js router actually navigated — its effect saw
 * "no user on a protected page" and pushed
 * {@code /careers/login?returnTo=&lt;current-page&gt;}, racing the
 * sign-out replace and winning. The next login then honoured the stale
 * {@code returnTo} and flashed the previous role's page before the
 * ProtectedRoute on that page kicked the wrong-role user to their
 * proper dashboard. Hard navigation tears the React tree down so no
 * effect can fire after logout().</p>
 *
 * <p>Also broadcasts to sibling tabs so they end their sessions in
 * lock-step. Visual variant kept consistent across the role sidebars.</p>
 */
export default function SignOutButton({
  variant = 'sidebar',
  onAfter,
  reason,
}: {
  variant?: 'sidebar' | 'menu';
  onAfter?: () => void;
  /** Optional reason appended to the login URL (e.g. 'idle'). */
  reason?: 'idle';
}) {
  const { logout } = useAuth();

  function handle() {
    try {
      broadcastSessionEvent({ type: 'logout', reason });
    } catch {
      // BroadcastChannel may be unsupported (SSR / old browsers) — ignore.
    }
    logout();
    onAfter?.();
    const dest = reason
      ? `/careers/login?reason=${encodeURIComponent(reason)}`
      : '/careers/login';
    // Hard navigation — see class doc. Window will not be undefined here
    // because SignOutButton is 'use client' and is only mounted in the
    // browser; guard for SSR just in case a future change paints it
    // server-side.
    if (typeof window !== 'undefined') {
      window.location.replace(dest);
    }
  }

  if (variant === 'menu') {
    return (
      <button
        type="button"
        onClick={handle}
        className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-left text-sm text-red-600 transition-colors hover:bg-red-50 hover:text-red-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
      >
        <LogOut className="h-[18px] w-[18px]" strokeWidth={2} />
        Sign out
      </button>
    );
  }

  return (
    <button
      type="button"
      onClick={handle}
      className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm font-medium text-slate-600 transition-colors hover:bg-red-50 hover:text-red-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
    >
      <LogOut className="h-[18px] w-[18px]" strokeWidth={2} />
      Sign out
    </button>
  );
}
