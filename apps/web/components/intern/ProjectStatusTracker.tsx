'use client';

import StepperHorizontal from '@/components/ui/StepperHorizontal';
import type {
  AssignmentSummary,
  ProjectAssignmentStatus,
  TrainerDecision,
} from '@/app/careers/(dashboard)/intern/projects/types';
import { AlertTriangle } from 'lucide-react';

/**
 * Project status tracker — 5-step pipeline that mirrors the application
 * stepper visual (StepperHorizontal).
 *
 * <pre>
 *   0 Submitted        SUBMITTED before trainer review
 *   1 Trainer review   SUBMITTED while trainer reviews
 *   2 Approved         TECH_APPROVED | PENDING_VIVA (trainer approved)
 *   3 Q&A session      PENDING_VIVA (evaluator's Q&A session live)
 *   4 Completed        COMPLETED
 * </pre>
 *
 * <p>The tracker reads from {@link effectiveStatusFor} — a derived
 * canonical state that consolidates {@link ProjectAssignmentStatus},
 * the trainer's latest decision, and the QA session state. This is the
 * SINGLE SOURCE OF TRUTH the tracker AND the top status pills should
 * both feed off, so they always agree even when the backend mirror lags
 * (e.g. legacy data where ProjectAssignment.status is still SUBMITTED
 * but trainerDecision = ACCEPT).</p>
 */

export const PROJECT_TRACKER_STEPS = [
  { key: 'submitted', label: 'Submitted' },
  { key: 'trainer-review', label: 'Trainer review' },
  { key: 'approved', label: 'Approved' },
  { key: 'qa', label: 'Q&A session' },
  { key: 'completed', label: 'Completed' },
] as const;

/**
 * Canonical effective state — derived from the assignment row, the
 * trainer's latest decision, and the Q&A session. Both the tracker
 * and the top status pills should derive from this so they never
 * contradict each other.
 */
export type EffectiveStatus =
  | 'ASSIGNED'
  | 'IN_PROGRESS'
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'RETURNED'
  | 'APPROVED'
  | 'QA_SCHEDULED'
  | 'QA_CONDUCTED'
  | 'COMPLETED';

export function effectiveStatusFor(a: AssignmentSummary): EffectiveStatus {
  const status: ProjectAssignmentStatus = a.status;
  const decision: TrainerDecision | null =
    a.latestSubmission?.trainerDecision ?? null;
  const qa = a.qaSession;

  // COMPLETED dominates everything.
  if (status === 'COMPLETED') return 'COMPLETED';

  // Q&A live? The evaluator's session beats the trainer decision in
  // labelling — once we're at the viva step, the trainer's accept is
  // assumed.
  if (qa?.status === 'CONDUCTED') return 'QA_CONDUCTED';
  if (qa?.status === 'SCHEDULED' || status === 'PENDING_VIVA') {
    return qa ? 'QA_SCHEDULED' : 'APPROVED';
  }

  // Trainer-decision dominates the assignment row for the
  // SUBMITTED-but-decided case (the bug fix — backend mirror keeps these
  // in sync for new rows, but legacy/stale rows still hit this branch).
  if (decision === 'ACCEPT' || status === 'TECH_APPROVED') return 'APPROVED';
  if (decision === 'REQUEST_REVISION' || status === 'RETURNED') return 'RETURNED';

  // Submitted but no decision yet → still under review.
  if (status === 'SUBMITTED') return 'UNDER_REVIEW';
  if (status === 'IN_PROGRESS') return 'IN_PROGRESS';
  return 'ASSIGNED';
}

export interface ProjectTrackerState {
  currentIndex: number;
  revisionRequested: boolean;
  nowLine: string;
  effective: EffectiveStatus;
}

export function projectTrackerStateFor(a: AssignmentSummary): ProjectTrackerState {
  const effective = effectiveStatusFor(a);
  const revisionRequested = effective === 'RETURNED';

  let currentIndex = -1;
  let nowLine = '';
  switch (effective) {
    case 'ASSIGNED':
    case 'IN_PROGRESS':
      nowLine = 'Working on the project — submit when ready.';
      break;
    case 'UNDER_REVIEW':
      // SUBMITTED but the trainer hasn't published a decision yet.
      // Step 1 = "Trainer review" is current.
      currentIndex = 1;
      nowLine = 'Submitted and waiting for trainer review.';
      break;
    case 'RETURNED':
      // Loops back to "Submitted" so the intern's mental model is
      // "fix and re-submit". The conditional badge below the tracker
      // makes the revisions branch explicit.
      currentIndex = 0;
      nowLine = 'Your trainer returned the project — see the notes below, update, and re-submit.';
      break;
    case 'APPROVED':
      // Trainer accepted — Submitted + Trainer review both DONE,
      // "Approved" is the current step. Either the project is
      // PENDING_VIVA awaiting the evaluator, or TECH_APPROVED (only on
      // the legacy two-step path that doesn't auto-flip).
      currentIndex = 2;
      nowLine = 'Trainer approved — awaiting your Evaluator to schedule the Q&A session. You\'ll be notified when it\'s booked.';
      break;
    case 'QA_SCHEDULED':
      currentIndex = 3;
      nowLine = 'Q&A session scheduled — see the Q&A card on the right for the join link and time.';
      break;
    case 'QA_CONDUCTED':
      currentIndex = 3;
      nowLine = 'Q&A conducted — your Evaluator is preparing the final sign-off (marks + remarks).';
      break;
    case 'COMPLETED':
      currentIndex = 4;
      nowLine = 'Project completed and signed off. Nice work.';
      break;
  }
  return { currentIndex, revisionRequested, nowLine, effective };
}

/**
 * Single user-facing label per effective state. The project detail
 * page's StatusBar uses this so the top-of-page status pill matches
 * the tracker.
 */
export function effectiveStatusLabel(s: EffectiveStatus): string {
  switch (s) {
    case 'ASSIGNED':      return 'Assigned';
    case 'IN_PROGRESS':   return 'In progress';
    case 'SUBMITTED':     return 'Submitted';
    case 'UNDER_REVIEW':  return 'Submitted — awaiting review';
    case 'RETURNED':      return 'Returned for revisions';
    case 'APPROVED':      return 'Trainer approved — heading to Q&A';
    case 'QA_SCHEDULED':  return 'Q&A session scheduled';
    case 'QA_CONDUCTED':  return 'Q&A conducted — awaiting sign-off';
    case 'COMPLETED':     return 'Completed';
  }
}

export function effectiveStatusTone(s: EffectiveStatus): string {
  switch (s) {
    case 'RETURNED':      return 'bg-red-100 text-red-800';
    case 'UNDER_REVIEW':  return 'bg-slate-100 text-slate-700';
    case 'APPROVED':      return 'bg-green-100 text-green-800';
    case 'QA_SCHEDULED':  return 'bg-amber-100 text-amber-800';
    case 'QA_CONDUCTED':  return 'bg-emerald-100 text-emerald-800';
    case 'COMPLETED':     return 'bg-green-100 text-green-800';
    default:              return 'bg-slate-100 text-slate-700';
  }
}

interface Props {
  assignment: AssignmentSummary;
  className?: string;
}

export default function ProjectStatusTracker({ assignment, className }: Props) {
  const { currentIndex, revisionRequested, nowLine } =
    projectTrackerStateFor(assignment);
  return (
    <section
      aria-label="Project status tracker"
      className={
        (className ?? '')
        + ' overflow-x-auto rounded-lg border border-slate-200 bg-white p-4 shadow-sm'
      }
    >
      <div className="min-w-[40rem]">
        <StepperHorizontal
          steps={[...PROJECT_TRACKER_STEPS]}
          currentIndex={currentIndex}
        />
      </div>
      {(nowLine || revisionRequested) && (
        <div className="mt-3 flex flex-wrap items-center gap-2 border-t border-slate-100 pt-2">
          {revisionRequested && (
            <span className="inline-flex items-center gap-1 rounded-full bg-red-100 px-2 py-0.5 text-[11px] font-semibold text-red-800">
              <AlertTriangle className="h-3 w-3" />
              Revisions requested
            </span>
          )}
          {nowLine && (
            <p className="text-xs text-slate-700">
              <span className="font-semibold text-slate-900">Now:</span>{' '}
              {nowLine}
            </p>
          )}
        </div>
      )}
    </section>
  );
}
