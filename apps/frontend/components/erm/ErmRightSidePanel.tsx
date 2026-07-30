'use client';

import Link from 'next/link';
import {
  AlertTriangle,
  Bell,
  CalendarPlus,
  CheckSquare,
  Clock,
  FileText,
  UserCheck,
  Video,
  X,
  type LucideIcon,
} from 'lucide-react';
import { useErmDashboard } from './ErmDashboardContext';
import QuickActionsPanel from './QuickActionsPanel';
import { useTodoPanel } from '@/components/todos/useTodoPanel';
import type { TodoBucket, TodoItem } from '@/components/todos/todo-types';

/**
 * Phase 1 — ERM-variant of the right-side panel. Distinct shape from
 * the intern's contact panel: quick actions + today's interview count
 * + unread notification bell shortcut.
 *
 * <p>To-do buckets (feat/erm-todo-panel) are surfaced as a new card at
 * the top so ERM gets the same role-aware, auto-resolving to-do list
 * that Manager/Evaluator have — sourced from
 * {@code GET /api/v1/erm/todos}, which reuses the existing
 * {@code ErmDashboardService} pending-action queries.</p>
 */
export default function ErmRightSidePanel() {
  const { rightPanel, loading } = useErmDashboard();

  return (
    <aside className="space-y-4">
      <ErmTodoBucketsCard />

      <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
          Quick actions
        </h3>
        <div className="mt-3">
          <QuickActionsPanel
            actions={rightPanel?.quickActions ?? null}
            loading={loading}
          />
        </div>
      </section>

      <Link
        href="/careers/erm/interviews"
        className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm text-slate-700 shadow-sm hover:bg-slate-50"
      >
        <Video className="h-4 w-4 text-slate-500" />
        <span className="flex-1">Interviews today</span>
        <span className="rounded-full bg-brand-100 px-2 py-0.5 text-[11px] font-semibold text-brand-800 tabular-nums">
          {rightPanel?.todayInterviewsCount ?? 0}
        </span>
      </Link>

      <Link
        href="/careers/messages"
        className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm text-slate-700 shadow-sm hover:bg-slate-50"
      >
        <Bell className="h-4 w-4 text-slate-500" />
        <span className="flex-1">Notifications</span>
        {rightPanel && rightPanel.unreadNotifications > 0 && (
          <span className="rounded-full bg-red-600 px-2 py-0.5 text-[11px] font-semibold text-white tabular-nums">
            {rightPanel.unreadNotifications}
          </span>
        )}
      </Link>
    </aside>
  );
}

// ─── To-do buckets card ──────────────────────────────────────────────────

const ICON_MAP: Record<string, LucideIcon> = {
  'user-check': UserCheck,
  'clock': Clock,
  'file-text': FileText,
  'video': Video,
  'calendar-plus': CalendarPlus,
  'check-square': CheckSquare,
  'alert-triangle': AlertTriangle,
};

/**
 * Compact ERM to-do panel — mirrors {@code components/todos/TodoPanel.tsx}
 * but rendered as a card inside the ERM right-side stack (matches the
 * ERM panel visual language). Buckets auto-drop when the underlying
 * pending-action query stops emitting the row; the optional per-item
 * dismiss reuses the shared {@code /api/v1/todos/{key}/dismiss} endpoint.
 */
function ErmTodoBucketsCard() {
  const { data, loading, error, dismiss } = useTodoPanel('ERM');
  const visibleBuckets = (data?.buckets ?? []).filter((b) => b.count > 0);
  const totalActive = visibleBuckets.reduce((sum, b) => sum + b.count, 0);

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <header className="flex items-baseline justify-between">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
          To-do
        </h3>
        <span className="text-[10px] text-slate-500">
          {loading && !data
            ? 'Loading…'
            : totalActive === 0
              ? 'All caught up'
              : `${totalActive} waiting`}
        </span>
      </header>

      {error && (
        <p className="mt-2 rounded border border-red-100 bg-red-50 px-2 py-1 text-[11px] text-red-700">
          {error}
        </p>
      )}

      {loading && !data ? (
        <div className="mt-3 space-y-2">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="h-9 w-full animate-pulse rounded bg-slate-100" />
          ))}
        </div>
      ) : visibleBuckets.length === 0 ? (
        <p className="mt-3 text-center text-[11px] text-slate-500">
          Nothing needs your review right now.
        </p>
      ) : (
        <ul className="mt-3 space-y-2">
          {visibleBuckets.map((b) => (
            <ErmBucketRow key={b.bucketKey} bucket={b} onDismiss={dismiss} />
          ))}
        </ul>
      )}

      <p className="mt-3 border-t border-slate-100 pt-2 text-[10px] text-slate-500">
        Items disappear automatically when the underlying work is done.
      </p>
    </section>
  );
}

function ErmBucketRow({
  bucket,
  onDismiss,
}: {
  bucket: TodoBucket;
  onDismiss: (todoKey: string) => void;
}) {
  const Icon = ICON_MAP[bucket.icon] ?? CheckSquare;
  const badgeClass =
    bucket.severity === 'URGENT'
      ? 'bg-red-100 text-red-700'
      : bucket.severity === 'WARN'
        ? 'bg-amber-100 text-amber-800'
        : 'bg-slate-100 text-slate-700';
  return (
    <li className="rounded-md border border-slate-200 bg-white">
      <Link
        href={bucket.actionUrl}
        className="flex items-center justify-between gap-2 px-2.5 py-1.5 hover:bg-slate-50"
      >
        <span className="inline-flex items-center gap-2 text-xs font-medium text-slate-800">
          <Icon className="h-3.5 w-3.5 text-slate-500" strokeWidth={2} />
          {bucket.label}
        </span>
        <span
          className={
            'inline-flex min-w-[22px] items-center justify-center rounded-full px-1.5 py-0.5 text-[10px] font-semibold ' +
            badgeClass
          }
        >
          {bucket.count}
        </span>
      </Link>
      {bucket.topItems.length > 0 && (
        <ul className="border-t border-slate-100 bg-slate-50/60">
          {bucket.topItems.map((it) => (
            <ErmItemRow key={it.todoKey} item={it} onDismiss={onDismiss} />
          ))}
          {bucket.count > bucket.topItems.length && (
            <li className="px-2.5 py-1 text-[10px]">
              <Link
                href={bucket.actionUrl}
                className="text-brand-700 hover:underline"
              >
                Review all {bucket.count} →
              </Link>
            </li>
          )}
        </ul>
      )}
    </li>
  );
}

function ErmItemRow({
  item,
  onDismiss,
}: {
  item: TodoItem;
  onDismiss: (todoKey: string) => void;
}) {
  return (
    <li className="group flex items-center justify-between gap-1 px-2.5 py-1 text-[11px]">
      <Link
        href={item.actionUrl}
        className={
          'flex-1 truncate ' +
          (item.dismissed
            ? 'text-slate-400 line-through'
            : 'text-slate-700 hover:text-slate-900')
        }
        title={item.subLabel ?? undefined}
      >
        <span className="font-medium">{item.label}</span>
        {item.subLabel && (
          <span className="ml-1 text-slate-500">· {item.subLabel}</span>
        )}
      </Link>
      {!item.dismissed && (
        <button
          type="button"
          aria-label="Dismiss"
          className="rounded p-0.5 text-slate-400 opacity-0 transition-opacity hover:bg-slate-200 hover:text-slate-700 group-hover:opacity-100"
          onClick={(e) => {
            e.preventDefault();
            onDismiss(item.todoKey);
          }}
        >
          <X className="h-3 w-3" />
        </button>
      )}
    </li>
  );
}
