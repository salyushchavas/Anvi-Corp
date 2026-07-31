'use client';

import Link from 'next/link';
import {
  AlertTriangle,
  Bell,
  CalendarDays,
  CalendarPlus,
  CheckSquare,
  Clock,
  FileText,
  UserCheck,
  Video,
  X,
  type LucideIcon,
} from 'lucide-react';
import { useTrainerDashboard } from './TrainerDashboardContext';
import type { Alert, QuickAction } from './types';
import { useTodoPanel } from '@/components/todos/useTodoPanel';
import type { TodoBucket, TodoItem } from '@/components/todos/todo-types';

/**
 * Trainer Phase 1 — right-side panel: to-do buckets (top card, pinned
 * to NOW — never rescopes to the sticky month), then 5 doc §4 quick
 * actions + 2 alerts + today's meeting count + unread notification
 * count. Renders Phase 2/3 destinations as "Coming soon" until those
 * phases ship.
 */
export default function TrainerRightSidePanel() {
  const { rightPanel, rightPanelError } = useTrainerDashboard();

  return (
    <aside className="hidden h-full w-64 shrink-0 flex-col overflow-y-auto border-l border-slate-200 bg-white xl:flex">
      <div className="border-b border-slate-100 px-4 py-3">
        <TrainerTodoBucketsCard />
      </div>

      {rightPanelError && (
        <p className="border-b border-red-100 bg-red-50 px-4 py-2 text-xs text-red-700">
          {rightPanelError}
        </p>
      )}

      {!rightPanel ? (
        <>
          <div className="border-b border-slate-100 px-4 py-3">
            <div className="h-3 w-32 animate-pulse rounded bg-slate-100" />
          </div>
          <div className="space-y-2 p-4">
            {Array.from({ length: 5 }).map((_, i) => (
              <div
                key={i}
                className="h-9 w-full animate-pulse rounded bg-slate-100"
              />
            ))}
          </div>
        </>
      ) : (
        <>
          <header className="border-b border-slate-100 px-4 py-3">
            <h3 className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
              Quick actions
            </h3>
          </header>
          <ul className="space-y-1 p-2">
            {rightPanel.quickActions.map((qa) => (
              <QuickActionRow key={qa.key} action={qa} />
            ))}
          </ul>

          <header className="border-y border-slate-100 px-4 py-3">
            <h3 className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
              Alerts
            </h3>
          </header>
          <ul className="space-y-1 p-2">
            {rightPanel.alerts.map((a) => (
              <AlertRow key={a.key} alert={a} />
            ))}
          </ul>

          <div className="mt-auto border-t border-slate-100 p-3 text-xs text-slate-600">
            <p className="flex items-center gap-2">
              <CalendarDays className="h-3.5 w-3.5 text-slate-400" strokeWidth={2} />
              <Link href="/careers/trainer" className="hover:text-slate-900">
                Today's meetings:{' '}
                <strong>{rightPanel.todayMeetingsCount}</strong>
              </Link>
            </p>
            <p className="mt-2 flex items-center gap-2">
              <Bell className="h-3.5 w-3.5 text-slate-400" strokeWidth={2} />
              Unread: <strong>{rightPanel.unreadNotifications}</strong>
            </p>
          </div>
        </>
      )}
    </aside>
  );
}

function QuickActionRow({ action }: { action: QuickAction }) {
  const tip = action.comingSoon ? 'Coming soon' : undefined;
  return (
    <li>
      <Link
        href={action.href}
        title={tip}
        className={
          'flex items-center justify-between rounded-md px-3 py-2 text-sm ' +
          (action.comingSoon
            ? 'text-slate-500 hover:bg-slate-50'
            : 'text-slate-800 hover:bg-slate-50')
        }
      >
        <span className="truncate">{action.label}</span>
        {action.badge > 0 && (
          <span
            className={
              'rounded-full px-1.5 py-0.5 text-[10px] font-semibold ' +
              (action.badge >= 5
                ? 'bg-red-100 text-red-700'
                : 'bg-slate-100 text-slate-600')
            }
          >
            {action.badge}
          </span>
        )}
      </Link>
    </li>
  );
}

function AlertRow({ alert }: { alert: Alert }) {
  const dotClass =
    alert.severity === 'URGENT'
      ? 'bg-red-500'
      : alert.severity === 'WARN'
        ? 'bg-amber-500'
        : 'bg-slate-500';
  return (
    <li className="flex items-center justify-between px-3 py-1.5 text-xs">
      <span className="flex items-center gap-2">
        <span className={'h-1.5 w-1.5 rounded-full ' + dotClass} />
        <span className="text-slate-700">{alert.label}</span>
      </span>
      <span className="tabular-nums text-slate-600">{alert.count}</span>
    </li>
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
 * Compact Trainer to-do panel — mirrors the ERM variant so the visual
 * language stays consistent across role dashboards. Buckets auto-drop
 * when the underlying pending-action query stops emitting the row; the
 * optional per-item dismiss reuses the shared
 * {@code /api/v1/todos/{key}/dismiss} endpoint. Always reflects NOW —
 * never scopes to the trainer's sticky month picker.
 */
function TrainerTodoBucketsCard() {
  const { data, loading, error, dismiss } = useTodoPanel('TRAINER');
  const visibleBuckets = (data?.buckets ?? []).filter((b) => b.count > 0);
  const totalActive = visibleBuckets.reduce((sum, b) => sum + b.count, 0);

  return (
    <section>
      <header className="flex items-baseline justify-between">
        <h3 className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
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
            <TrainerBucketRow key={b.bucketKey} bucket={b} onDismiss={dismiss} />
          ))}
        </ul>
      )}

      <p className="mt-3 border-t border-slate-100 pt-2 text-[10px] text-slate-500">
        Items disappear automatically when the underlying work is done.
      </p>
    </section>
  );
}

function TrainerBucketRow({
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
            <TrainerItemRow key={it.todoKey} item={it} onDismiss={onDismiss} />
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

function TrainerItemRow({
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
