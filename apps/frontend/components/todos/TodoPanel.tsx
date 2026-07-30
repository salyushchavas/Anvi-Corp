'use client';

import Link from 'next/link';
import { useMemo } from 'react';
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
import { useTodoPanel } from './useTodoPanel';
import type { TodoBucket, TodoItem, TodoRole } from './todo-types';

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
 * Persistent role-aware to-do panel. Mirrors the shape of
 * TrainerRightSidePanel — a slim right-hand column at xl. The list is
 * DERIVED from live pending-action queries: an item auto-drops when the
 * underlying entity's status flips. Users can OPTIONALLY dismiss an item
 * (a decorated {@code dismissed=true} tag); auto-resolve remains the
 * source of truth.
 */
export default function TodoPanel({ role }: { role: TodoRole }) {
  const { data, loading, error, dismiss } = useTodoPanel(role);

  const visibleBuckets = useMemo(
    () => (data?.buckets ?? []).filter((b) => b.count > 0),
    [data],
  );
  const totalActive = visibleBuckets.reduce((sum, b) => sum + b.count, 0);

  return (
    <aside className="hidden h-full w-72 shrink-0 flex-col border-l border-slate-200 bg-white xl:flex">
      <header className="border-b border-slate-100 px-4 py-3">
        <p className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
          To-do
        </p>
        <p className="mt-0.5 text-sm font-semibold text-slate-900">
          {loading
            ? 'Loading…'
            : totalActive === 0
              ? "You're all caught up"
              : `${totalActive} thing${totalActive === 1 ? '' : 's'} waiting`}
        </p>
      </header>

      {error && (
        <p className="border-b border-red-100 bg-red-50 px-4 py-2 text-xs text-red-700">
          {error}
        </p>
      )}

      <div className="flex-1 overflow-y-auto p-2">
        {loading && !data && (
          <div className="space-y-2 p-2">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="h-14 w-full animate-pulse rounded bg-slate-100" />
            ))}
          </div>
        )}

        {!loading && visibleBuckets.length === 0 && (
          <p className="px-3 py-6 text-center text-xs text-slate-500">
            Nothing needs your review right now.
          </p>
        )}

        <ul className="space-y-2">
          {visibleBuckets.map((b) => (
            <BucketCard key={b.bucketKey} bucket={b} onDismiss={dismiss} />
          ))}
        </ul>
      </div>

      <div className="border-t border-slate-100 px-3 py-2 text-[11px] text-slate-500">
        Items disappear automatically when the underlying work is done.
      </div>
    </aside>
  );
}

function BucketCard({
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
        className="flex items-center justify-between gap-2 px-3 py-2 hover:bg-slate-50"
      >
        <span className="inline-flex items-center gap-2 text-sm font-medium text-slate-800">
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
            <ItemRow key={it.todoKey} item={it} onDismiss={onDismiss} />
          ))}
          {bucket.count > bucket.topItems.length && (
            <li className="px-3 py-1.5 text-[11px]">
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

function ItemRow({
  item,
  onDismiss,
}: {
  item: TodoItem;
  onDismiss: (todoKey: string) => void;
}) {
  return (
    <li className="group flex items-center justify-between gap-1 px-3 py-1.5 text-xs">
      <Link
        href={item.actionUrl}
        className={
          'flex-1 truncate ' +
          (item.dismissed ? 'text-slate-400 line-through' : 'text-slate-700 hover:text-slate-900')
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
