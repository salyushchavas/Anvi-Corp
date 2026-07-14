'use client';

import {
  ReactNode,
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react';
import { useRouter } from 'next/navigation';
import { Clock } from 'lucide-react';
import { useAuth } from '@/lib/auth-context';
import {
  broadcastSessionEvent,
  subscribeSessionEvents,
} from '@/lib/session-broadcast';
import Modal from '@/components/ui/Modal';

/**
 * App-wide idle auto-logout. Mounted once under {@code AuthProvider} in
 * the root layout so every authenticated route — current ERM / Trainer /
 * Evaluator / Intern dashboards and any future role — gets the same
 * inactivity teardown without per-dashboard wiring.
 *
 * <p>Behavior:</p>
 * <ul>
 *   <li>Inert when {@code useAuth().user == null} (login / public pages).</li>
 *   <li>Tracks mouse / key / click / scroll / touch; resets countdown on
 *       any activity, throttled to one reset per 5s.</li>
 *   <li>At {@code idle - warningLeadSeconds} opens a Radix modal with a
 *       live countdown. The user can click <em>Stay signed in</em>
 *       (resets) or <em>Log out now</em> (immediate teardown).</li>
 *   <li>When the countdown finishes the same shared teardown runs and
 *       the user is sent to {@code /careers/login?reason=idle}.</li>
 *   <li>{@code BroadcastChannel('skyzen.session')} keeps sibling tabs
 *       in lock-step — activity in one tab resets every tab's timer;
 *       an explicit / idle logout in one tab tears the others down.</li>
 * </ul>
 *
 * <p>Defaults can be overridden at build time via:</p>
 * <ul>
 *   <li>{@code NEXT_PUBLIC_IDLE_TIMEOUT_MINUTES} (default 30)</li>
 *   <li>{@code NEXT_PUBLIC_IDLE_WARNING_SECONDS} (default 60)</li>
 * </ul>
 */

const IDLE_MINUTES = Math.max(
  1,
  parseInt(process.env.NEXT_PUBLIC_IDLE_TIMEOUT_MINUTES ?? '30', 10) || 30,
);
const WARNING_SECONDS = Math.max(
  5,
  parseInt(process.env.NEXT_PUBLIC_IDLE_WARNING_SECONDS ?? '60', 10) || 60,
);
const IDLE_MS = IDLE_MINUTES * 60 * 1000;
const WARNING_MS = WARNING_SECONDS * 1000;
const ACTIVITY_THROTTLE_MS = 5_000;

const ACTIVITY_EVENTS: (keyof DocumentEventMap)[] = [
  'mousemove',
  'mousedown',
  'keydown',
  'touchstart',
  'scroll',
  'wheel',
];

export default function IdleTimeoutProvider({ children }: { children: ReactNode }) {
  const { user, logout } = useAuth();
  const router = useRouter();

  const [warningOpen, setWarningOpen] = useState(false);
  const [countdown, setCountdown] = useState(WARNING_SECONDS);

  // Refs so the activity listeners don't churn on every render.
  const idleTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const warningTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const countdownIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastActivityRef = useRef<number>(Date.now());

  const clearTimers = useCallback(() => {
    if (idleTimerRef.current) clearTimeout(idleTimerRef.current);
    if (warningTimerRef.current) clearTimeout(warningTimerRef.current);
    if (countdownIntervalRef.current) clearInterval(countdownIntervalRef.current);
    idleTimerRef.current = null;
    warningTimerRef.current = null;
    countdownIntervalRef.current = null;
  }, []);

  const performLogout = useCallback(
    (broadcast: boolean) => {
      clearTimers();
      setWarningOpen(false);
      if (broadcast) {
        try { broadcastSessionEvent({ type: 'logout', reason: 'idle' }); }
        catch { /* ignore */ }
      }
      logout();
      // Hard navigation — same rationale as SignOutButton: clearing the
      // React user state on the same task as a Next router replace lets
      // the source page's ProtectedRoute re-render with user=null and
      // stamp `?returnTo=<previous-page>` onto the login URL before the
      // navigation commits. A full-page replace tears the React tree
      // down before any effect can fire.
      if (typeof window !== 'undefined') {
        window.location.replace('/careers/login?reason=idle');
      } else {
        router.replace('/careers/login?reason=idle');
      }
    },
    [clearTimers, logout, router],
  );

  const openWarning = useCallback(() => {
    setCountdown(WARNING_SECONDS);
    setWarningOpen(true);
    if (countdownIntervalRef.current) clearInterval(countdownIntervalRef.current);
    countdownIntervalRef.current = setInterval(() => {
      setCountdown((s) => (s > 0 ? s - 1 : 0));
    }, 1000);
    if (warningTimerRef.current) clearTimeout(warningTimerRef.current);
    warningTimerRef.current = setTimeout(() => performLogout(true), WARNING_MS);
  }, [performLogout]);

  const armIdleTimer = useCallback(() => {
    if (idleTimerRef.current) clearTimeout(idleTimerRef.current);
    idleTimerRef.current = setTimeout(openWarning, IDLE_MS - WARNING_MS);
  }, [openWarning]);

  /**
   * Reset the full pipeline — clear any open warning, re-arm the idle
   * timer. Called from local activity, from "Stay signed in", and from
   * cross-tab activity broadcasts.
   */
  const resetAll = useCallback(
    (opts?: { broadcast?: boolean }) => {
      setWarningOpen(false);
      if (warningTimerRef.current) clearTimeout(warningTimerRef.current);
      if (countdownIntervalRef.current) clearInterval(countdownIntervalRef.current);
      warningTimerRef.current = null;
      countdownIntervalRef.current = null;
      armIdleTimer();
      if (opts?.broadcast) {
        try {
          broadcastSessionEvent({ type: 'activity', ts: Date.now() });
        } catch { /* ignore */ }
      }
    },
    [armIdleTimer],
  );

  // Main effect — only active while a user is signed in.
  useEffect(() => {
    if (!user) {
      clearTimers();
      setWarningOpen(false);
      return;
    }

    armIdleTimer();

    function onActivity() {
      const now = Date.now();
      if (now - lastActivityRef.current < ACTIVITY_THROTTLE_MS) return;
      lastActivityRef.current = now;
      // Don't broadcast every micro-move; siblings reset on their own
      // local activity. Cross-tab piggybacks on the explicit "Stay
      // signed in" click below.
      armIdleTimer();
    }

    for (const ev of ACTIVITY_EVENTS) {
      document.addEventListener(ev, onActivity, { passive: true });
    }
    const unsubscribe = subscribeSessionEvents((e) => {
      if (e.type === 'activity') {
        resetAll();
      } else if (e.type === 'logout') {
        // Sibling tab logged out — match it locally without rebroadcasting.
        // Hard navigation for the same reason performLogout uses one.
        clearTimers();
        setWarningOpen(false);
        logout();
        const dest = e.reason === 'idle'
          ? '/careers/login?reason=idle'
          : '/careers/login';
        if (typeof window !== 'undefined') {
          window.location.replace(dest);
        } else {
          router.replace(dest);
        }
      }
    });

    return () => {
      for (const ev of ACTIVITY_EVENTS) {
        document.removeEventListener(ev, onActivity);
      }
      unsubscribe();
      clearTimers();
    };
  }, [user, armIdleTimer, clearTimers, resetAll, logout, router]);

  return (
    <>
      {children}
      <Modal
        open={warningOpen}
        onOpenChange={(o) => {
          // Don't allow dismiss-by-overlay-click — must explicitly choose.
          if (!o) resetAll({ broadcast: true });
        }}
        title={(
          <span className="inline-flex items-center gap-2">
            <Clock className="h-4 w-4 text-amber-600" />
            Still there?
          </span>
        )}
        description={`You'll be signed out in ${countdown}s due to inactivity.`}
        size="sm"
        footer={(
          <>
            <button
              type="button"
              onClick={() => performLogout(true)}
              className="rounded-md border border-slate-200 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
            >
              Log out now
            </button>
            <button
              type="button"
              onClick={() => resetAll({ broadcast: true })}
              className="rounded-md bg-brand-700 px-4 py-1.5 text-sm font-semibold text-white hover:bg-brand-800"
            >
              Stay signed in
            </button>
          </>
        )}
      >
        <div className="text-sm text-slate-700">
          <p>
            For your security we sign you out after{' '}
            <span className="font-semibold">{IDLE_MINUTES} minutes</span> of
            inactivity. Click <strong>Stay signed in</strong> to keep working,
            or <strong>Log out now</strong> to sign out immediately.
          </p>
          <p className="mt-3 text-2xl font-bold tabular-nums text-amber-700">
            {countdown}s
          </p>
        </div>
      </Modal>
    </>
  );
}
