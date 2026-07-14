'use client';

import StepperHorizontal from '@/components/ui/StepperHorizontal';
import { AlertTriangle } from 'lucide-react';

/**
 * Timesheet status tracker — 3-step pipeline that mirrors the application
 * stepper visual (StepperHorizontal). Maps the real timesheet status to
 * the visible step:
 *
 * <pre>
 *   1 Submitted        SUBMITTED (by intern)
 *   2 ERM verified     VERIFIED
 *   3 Manager approved APPROVED
 * </pre>
 *
 * DRAFT = nothing submitted (currentIndex = -1; nothing highlighted).
 * REJECTED = returned for correction (badge + Now line).
 */

export type TimesheetStatus =
  | 'DRAFT' | 'SUBMITTED' | 'VERIFIED' | 'APPROVED' | 'REJECTED';

export const TIMESHEET_TRACKER_STEPS = [
  { key: 'submitted', label: 'Submitted' },
  { key: 'verified',  label: 'ERM verified' },
  { key: 'approved',  label: 'Manager approved' },
] as const;

interface State { currentIndex: number; rejected: boolean; nowLine: string }

export function timesheetTrackerStateFor(status: TimesheetStatus): State {
  switch (status) {
    case 'SUBMITTED':
      return { currentIndex: 0, rejected: false,
        nowLine: 'Submitted — waiting for ERM to verify.' };
    case 'VERIFIED':
      return { currentIndex: 1, rejected: false,
        nowLine: 'Verified by ERM — waiting for your Manager’s approval.' };
    case 'APPROVED':
      return { currentIndex: 2, rejected: false,
        nowLine: 'Approved. Nothing further required.' };
    case 'REJECTED':
      return { currentIndex: -1, rejected: true,
        nowLine: 'Returned for correction — see the note, fix, and re-submit.' };
    default:
      return { currentIndex: -1, rejected: false,
        nowLine: 'Not submitted yet.' };
  }
}

interface Props {
  status: TimesheetStatus;
  className?: string;
}

export default function TimesheetStatusTracker({ status, className }: Props) {
  const { currentIndex, rejected, nowLine } = timesheetTrackerStateFor(status);
  return (
    <section
      aria-label="Timesheet status tracker"
      className={
        (className ?? '')
        + ' overflow-x-auto rounded-md border border-slate-200 bg-slate-50 p-3'
      }
    >
      <div className="min-w-[28rem]">
        <StepperHorizontal
          steps={[...TIMESHEET_TRACKER_STEPS]}
          currentIndex={currentIndex}
        />
      </div>
      {(nowLine || rejected) && (
        <div className="mt-2 flex flex-wrap items-center gap-2">
          {rejected && (
            <span className="inline-flex items-center gap-1 rounded-full bg-red-100 px-2 py-0.5 text-[11px] font-semibold text-red-800">
              <AlertTriangle className="h-3 w-3" />
              Returned for correction
            </span>
          )}
          {nowLine && (
            <p className="text-[11px] text-slate-700">
              <span className="font-semibold text-slate-900">Now:</span>{' '}
              {nowLine}
            </p>
          )}
        </div>
      )}
    </section>
  );
}
