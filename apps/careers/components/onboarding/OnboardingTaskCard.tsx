'use client';

import { useState } from 'react';
import Link from 'next/link';
import {
  AlertCircle,
  CheckCircle2,
  Circle,
  Clock,
  ExternalLink,
  MinusCircle,
} from 'lucide-react';
import toast from 'react-hot-toast';
import api from '@/lib/api';
import ConfirmDialog from '@/components/ConfirmDialog';
import { formatDueDate, formatRelative } from '@/lib/format-date';
import type {
  OnboardingTaskResponse,
  OnboardingTaskStatus,
} from '@/types';

interface Props {
  task: OnboardingTaskResponse;
  onUpdated: (updated: OnboardingTaskResponse) => void;
}

const STATUS_ICON: Record<
  OnboardingTaskStatus,
  { bg: string; iconClass: string; Icon: typeof Circle }
> = {
  PENDING: { bg: 'bg-gray-100', iconClass: 'text-gray-400', Icon: Circle },
  IN_PROGRESS: { bg: 'bg-amber-100', iconClass: 'text-amber-600', Icon: Clock },
  COMPLETED: {
    bg: 'bg-green-100',
    iconClass: 'text-green-600',
    Icon: CheckCircle2,
  },
  BLOCKED: { bg: 'bg-red-50', iconClass: 'text-red-500', Icon: AlertCircle },
  NOT_APPLICABLE: {
    bg: 'bg-gray-100',
    iconClass: 'text-gray-300',
    Icon: MinusCircle,
  },
};

const SECONDARY_BADGE: Partial<Record<OnboardingTaskStatus, { label: string; color: string }>> = {
  IN_PROGRESS: { label: 'In progress', color: 'bg-amber-50 text-amber-700' },
  BLOCKED: { label: 'Blocked', color: 'bg-red-50 text-red-700' },
  NOT_APPLICABLE: { label: 'Skipped', color: 'bg-gray-100 text-gray-500' },
};

function isExternal(url: string): boolean {
  return /^https?:\/\//i.test(url);
}

export default function OnboardingTaskCard({ task, onUpdated }: Props) {
  const [busy, setBusy] = useState(false);
  const [reopenOpen, setReopenOpen] = useState(false);

  const meta = STATUS_ICON[task.status];
  const Icon = meta.Icon;
  const badge = SECONDARY_BADGE[task.status];
  const isCompleted = task.status === 'COMPLETED';

  async function patchStatus(next: OnboardingTaskStatus): Promise<boolean> {
    setBusy(true);
    const optimistic: OnboardingTaskResponse = {
      ...task,
      status: next,
      completedAt: next === 'COMPLETED' ? new Date().toISOString() : undefined,
      overdue: next === 'COMPLETED' ? false : task.overdue,
    };
    onUpdated(optimistic);
    try {
      const res = await api.patch<OnboardingTaskResponse>(
        `/api/v1/onboarding/tasks/${task.id}`,
        { status: next }
      );
      onUpdated(res.data);
      return true;
    } catch (err: any) {
      onUpdated(task); // rollback
      toast.error(
        err?.response?.data?.error ?? "Couldn't update task. Try again."
      );
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function markComplete() {
    const ok = await patchStatus('COMPLETED');
    if (ok) toast.success(`"${task.title}" marked complete`);
  }

  async function reopen() {
    const ok = await patchStatus('PENDING');
    if (ok) toast.success(`"${task.title}" reopened`);
  }

  const cardClass =
    'flex items-start gap-4 rounded-lg border bg-white p-5 transition-shadow hover:shadow-sm ' +
    (isCompleted ? 'border-gray-200 bg-gray-50 opacity-90 ' : 'border-gray-200 ') +
    (task.overdue && !isCompleted ? 'border-l-4 border-l-red-400 ' : '');

  return (
    <>
      <div className={cardClass}>
        {/* Status icon */}
        <div
          className={
            'flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-full ' +
            meta.bg
          }
        >
          <Icon className={'h-5 w-5 ' + meta.iconClass} strokeWidth={2} />
        </div>

        {/* Body */}
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h3
                  className={
                    'text-base font-medium text-gray-900 ' +
                    (isCompleted ? 'line-through' : '')
                  }
                >
                  {task.title}
                </h3>
                {badge && (
                  <span
                    className={
                      'inline-block rounded-full px-2 py-0.5 text-xs font-medium ' +
                      badge.color
                    }
                  >
                    {badge.label}
                  </span>
                )}
              </div>
            </div>
          </div>

          {task.description && (
            <p className="mt-1 line-clamp-3 text-sm text-gray-600">
              {task.description}
            </p>
          )}

          <div className="mt-2 flex flex-wrap items-center gap-3 text-xs text-gray-500">
            {task.dueDate && !isCompleted && (
              <span
                className={
                  task.overdue
                    ? 'font-medium text-red-700'
                    : 'text-gray-500'
                }
              >
                {task.overdue
                  ? `Overdue — was due ${formatDueDate(task.dueDate)}`
                  : `Due ${formatDueDate(task.dueDate)}`}
              </span>
            )}
            {isCompleted && task.completedAt && (
              <span className="text-gray-500">
                Completed {formatRelative(task.completedAt)}
              </span>
            )}
          </div>
        </div>

        {/* Actions */}
        <div className="flex flex-shrink-0 flex-col items-end gap-2">
          {(task.status === 'PENDING' || task.status === 'IN_PROGRESS') && (
            <>
              {task.linkUrl && (
                isExternal(task.linkUrl) ? (
                  <a
                    href={task.linkUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="inline-flex items-center gap-1 rounded-md bg-accent px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-accent-dark"
                  >
                    Open
                    <ExternalLink className="h-3 w-3" strokeWidth={2} />
                  </a>
                ) : (
                  <Link
                    href={task.linkUrl}
                    className="inline-flex items-center gap-1 rounded-md bg-accent px-3 py-1.5 text-xs font-semibold text-white transition-colors hover:bg-accent-dark"
                  >
                    Open
                  </Link>
                )
              )}
              <button
                type="button"
                onClick={() => void markComplete()}
                disabled={busy}
                className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
              >
                {busy ? 'Saving…' : 'Mark as Done'}
              </button>
            </>
          )}

          {task.status === 'COMPLETED' && (
            <button
              type="button"
              onClick={() => setReopenOpen(true)}
              className="text-xs text-gray-500 hover:text-gray-700"
            >
              Mark incomplete
            </button>
          )}

          {task.status === 'BLOCKED' && (
            <span className="text-xs text-red-600">Blocked</span>
          )}
          {task.status === 'NOT_APPLICABLE' && (
            <span className="text-xs text-gray-400">Skipped</span>
          )}
        </div>
      </div>

      <ConfirmDialog
        open={reopenOpen}
        onClose={() => setReopenOpen(false)}
        onConfirm={async () => {
          await reopen();
          setReopenOpen(false);
        }}
        title="Reopen this task?"
        description={`"${task.title}" will be reset to Pending. You can mark it complete again at any time.`}
        confirmLabel="Reopen"
        cancelLabel="Keep complete"
        variant="primary"
      />
    </>
  );
}
