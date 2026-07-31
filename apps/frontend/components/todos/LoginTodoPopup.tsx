'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import {
  AlertTriangle,
  CalendarPlus,
  CheckSquare,
  Clock,
  FileText,
  UserCheck,
  Video,
  X,
  type LucideIcon,
} from 'lucide-react';
import { useAuth } from '@/lib/careers/auth-context';
import { useTodoPanel } from './useTodoPanel';
import type { TodoBucket, TodoRole } from './todo-types';

const ICON_MAP: Record<string, LucideIcon> = {
  'user-check': UserCheck,
  'clock': Clock,
  'file-text': FileText,
  'video': Video,
  'calendar-plus': CalendarPlus,
  'check-square': CheckSquare,
  'alert-triangle': AlertTriangle,
};

/** SessionStorage flag so we only fire the popup once per tab session. */
const SESSION_FLAG = 'todoPopupShown';

/**
 * Login summary pop-up. Fires ONCE per browser session on the first
 * dashboard mount, ONLY when {@code hasNewSinceLastSeen} is true from
 * the server. Auto-resolve owns the truth: when the user approves the
 * hire / completes the eval, the underlying pending-action query stops
 * emitting that item, so the pop-up's "new" flag naturally clears.
 *
 * <p>Roles without a dedicated {@code /api/v1/{role}/todos} endpoint
 * (Trainer, Intern) return null — they either use their existing
 * right-side panels or don't need the pop-up yet. Once those roles get
 * a todos endpoint, drop them into {@link #resolveRoleForCaller}.</p>
 */
export default function LoginTodoPopup() {
  const { user } = useAuth();
  const role = resolveRoleForCaller(user?.roles ?? []);

  // Hooks must run unconditionally — call the panel hook only for
  // supported roles. For unsupported roles we short-circuit render.
  if (!role) return null;
  return <PopupInner role={role} />;
}

function PopupInner({ role }: { role: TodoRole }) {
  const { user } = useAuth();
  const { data, markSeen } = useTodoPanel(role);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!data) return;
    if (!data.hasNewSinceLastSeen) return;
    if (typeof window === 'undefined') return;
    if (sessionStorage.getItem(SESSION_FLAG) === '1') return;
    sessionStorage.setItem(SESSION_FLAG, '1');
    setOpen(true);
  }, [data]);

  if (!open || !data) return null;

  const visibleBuckets = data.buckets.filter((b) => b.count > 0);
  const total = visibleBuckets.reduce((s, b) => s + b.count, 0);

  const close = () => {
    setOpen(false);
    void markSeen();
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="todo-popup-title"
    >
      <div className="w-full max-w-md overflow-hidden rounded-lg bg-white shadow-xl">
        <header className="flex items-start justify-between border-b border-slate-100 px-5 py-4">
          <div>
            <h2
              id="todo-popup-title"
              className="text-base font-semibold text-slate-900"
            >
              Welcome back{user?.fullName ? `, ${firstName(user.fullName)}` : ''}
            </h2>
            <p className="mt-0.5 text-sm text-slate-600">
              You have <strong>{total}</strong> thing{total === 1 ? '' : 's'} waiting for you.
            </p>
          </div>
          <button
            type="button"
            aria-label="Close"
            className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
            onClick={close}
          >
            <X className="h-4 w-4" />
          </button>
        </header>

        <ul className="max-h-[50vh] divide-y divide-slate-100 overflow-y-auto">
          {visibleBuckets.map((b) => (
            <PopupRow key={b.bucketKey} bucket={b} onNavigate={close} />
          ))}
        </ul>

        <footer className="flex items-center justify-between gap-2 border-t border-slate-100 bg-slate-50 px-5 py-3">
          <button
            type="button"
            className="rounded-md px-3 py-1.5 text-sm text-slate-600 hover:text-slate-900"
            onClick={close}
          >
            Dismiss
          </button>
          <Link
            href={visibleBuckets[0]?.actionUrl ?? '/careers'}
            onClick={close}
            className="rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-700"
          >
            View all in to-do
          </Link>
        </footer>
      </div>
    </div>
  );
}

function PopupRow({
  bucket,
  onNavigate,
}: {
  bucket: TodoBucket;
  onNavigate: () => void;
}) {
  const Icon = ICON_MAP[bucket.icon] ?? CheckSquare;
  const accent =
    bucket.severity === 'URGENT'
      ? 'text-red-700'
      : bucket.severity === 'WARN'
        ? 'text-amber-700'
        : 'text-slate-700';
  return (
    <li>
      <Link
        href={bucket.actionUrl}
        onClick={onNavigate}
        className="flex items-center justify-between gap-3 px-5 py-3 hover:bg-slate-50"
      >
        <span className="flex items-center gap-3">
          <Icon className={`h-4 w-4 ${accent}`} strokeWidth={2} />
          <span>
            <span className="block text-sm font-medium text-slate-900">
              {bucket.label}
            </span>
            <span className="block text-xs text-slate-500">
              {bucket.count} item{bucket.count === 1 ? '' : 's'} need your review
            </span>
          </span>
        </span>
        <span
          className={
            'inline-flex min-w-[24px] items-center justify-center rounded-full px-2 py-0.5 text-xs font-semibold ' +
            (bucket.severity === 'URGENT'
              ? 'bg-red-100 text-red-700'
              : bucket.severity === 'WARN'
                ? 'bg-amber-100 text-amber-800'
                : 'bg-slate-100 text-slate-700')
          }
        >
          {bucket.count}
        </span>
      </Link>
    </li>
  );
}

function resolveRoleForCaller(roles: readonly string[]): TodoRole | null {
  // Order matters: MANAGER before EVALUATOR before ERM before TRAINER if
  // a user holds multiple. SUPER_ADMIN falls through to MANAGER
  // (org-wide is the same view). TRAINER is the last role to gain a
  // dedicated /api/v1/{role}/todos endpoint.
  if (roles.includes('MANAGER')) return 'MANAGER';
  if (roles.includes('EVALUATOR')) return 'EVALUATOR';
  if (roles.includes('ERM')) return 'ERM';
  if (roles.includes('TRAINER')) return 'TRAINER';
  if (roles.includes('SUPER_ADMIN')) return 'MANAGER';
  return null;
}

function firstName(fullName: string): string {
  const trimmed = fullName.trim();
  const space = trimmed.indexOf(' ');
  return space > 0 ? trimmed.slice(0, space) : trimmed;
}
