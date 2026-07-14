'use client';

import type { ProjectSlot, ProjectSlotState } from './types';

const SLOT_TONE: Record<ProjectSlotState, string> = {
  NOT_ASSIGNED: 'bg-slate-200',
  ASSIGNED: 'bg-slate-500',
  IN_PROGRESS: 'bg-amber-500',
  COMPLETED: 'bg-green-500',
  OVERDUE: 'bg-red-500',
};

const SLOT_LABEL: Record<ProjectSlotState, string> = {
  NOT_ASSIGNED: 'Not assigned',
  ASSIGNED: 'Assigned',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Completed',
  OVERDUE: 'Overdue',
};

/** Two-slot horizontal indicator for the doc §5 "Current month projects"
 *  column. Hover surfaces the project title + due date. */
export default function ProjectSlotIndicator({
  project1,
  project2,
  monthYear,
}: {
  project1: ProjectSlot | null;
  project2: ProjectSlot | null;
  monthYear: string;
}) {
  return (
    <div className="flex items-center gap-1.5" title={'Month ' + monthYear}>
      <Slot slot={project1} index={1} />
      <Slot slot={project2} index={2} />
    </div>
  );
}

function Slot({ slot, index }: { slot: ProjectSlot | null; index: number }) {
  const state: ProjectSlotState = slot?.state ?? 'NOT_ASSIGNED';
  const tooltip = slot
    ? `Project ${index}: ${slot.title ?? '(no title)'} — ${SLOT_LABEL[state]}` +
      (slot.dueDate ? ` · due ${slot.dueDate}` : '')
    : `Project ${index}: not assigned`;
  return (
    <span
      title={tooltip}
      className={
        'inline-block h-3 w-8 rounded-full ring-1 ring-inset ring-slate-200 ' +
        SLOT_TONE[state]
      }
      aria-label={tooltip}
    />
  );
}
