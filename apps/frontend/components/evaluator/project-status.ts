/**
 * Evaluator-scoped display vocabulary for a single project.
 * Derives one label + one primary action from (ProjectStatus,
 * evaluationStatus). Shared between the Active Evaluees table cells
 * and the ProjectCardsSection card status bar so both surfaces speak
 * the exact same words for the same underlying state.
 *
 * This is a display-layer override — we do NOT change status.ts's
 * shared statusLabel(), so trainer/other surfaces keep their existing
 * "Pending viva" wording. Only the evaluator sees "Pending Evaluation".
 */

export type EvaluatorProjectDisplayState =
  | 'IN_PROGRESS'
  | 'PENDING_EVAL'
  | 'SCHEDULED'
  | 'SESSION_COMPLETED'
  | 'COMPLETED'
  | 'CANCELLED';

export type EvaluatorProjectActionKind =
  | 'NONE'
  | 'SCHEDULE'      // opens SchedulePostProjectDialog
  | 'START'         // POST /evaluations/{id}/start then navigate to compose
  | 'CONTINUE'      // link to compose page
  | 'OPEN'          // link to detail page
  | 'RESCHEDULE';   // opens SchedulePostProjectDialog

export interface EvaluatorProjectDisplay {
  state: EvaluatorProjectDisplayState;
  label: string;
  actionKind: EvaluatorProjectActionKind;
  actionLabel: string;
  /** Tailwind classes for the status pill. */
  pill: string;
}

/**
 * The one true mapping. Evaluation status wins when present; otherwise
 * "Pending Evaluation" iff the project is trainer-verified
 * (PENDING_VIVA) or fully completed (COMPLETED). Anything earlier
 * (NOT_STARTED / IN_PROGRESS / SUBMITTED / RETURNED / TECH_APPROVED)
 * counts as "In Progress" with no evaluator action available yet.
 */
export function deriveEvaluatorProjectState(
  projectStatus: string | null | undefined,
  evaluationStatus: string | null | undefined,
): EvaluatorProjectDisplayState {
  if (evaluationStatus === 'CANCELLED') return 'CANCELLED';
  if (evaluationStatus === 'PUBLISHED'
      || evaluationStatus === 'ACKNOWLEDGED'
      || evaluationStatus === 'AMENDED') return 'COMPLETED';
  if (evaluationStatus === 'IN_PROGRESS') return 'SESSION_COMPLETED';
  if (evaluationStatus === 'SCHEDULED') return 'SCHEDULED';
  if (projectStatus === 'PENDING_VIVA' || projectStatus === 'COMPLETED') {
    return 'PENDING_EVAL';
  }
  return 'IN_PROGRESS';
}

const CONFIG: Record<EvaluatorProjectDisplayState, {
  label: string;
  actionKind: EvaluatorProjectActionKind;
  actionLabel: string;
  pill: string;
}> = {
  IN_PROGRESS: {
    label: 'In Progress',
    actionKind: 'NONE',
    actionLabel: '',
    pill: 'bg-slate-100 text-slate-700',
  },
  PENDING_EVAL: {
    label: 'Pending Evaluation',
    actionKind: 'SCHEDULE',
    actionLabel: 'Schedule Session',
    pill: 'bg-amber-100 text-amber-900',
  },
  SCHEDULED: {
    label: 'Scheduled',
    actionKind: 'START',
    actionLabel: 'Start Session',
    pill: 'bg-brand-100 text-brand-800',
  },
  SESSION_COMPLETED: {
    label: 'Session Completed',
    actionKind: 'CONTINUE',
    actionLabel: 'Continue Evaluation',
    pill: 'bg-amber-100 text-amber-900',
  },
  COMPLETED: {
    label: 'Completed',
    actionKind: 'OPEN',
    actionLabel: 'Open Evaluation',
    pill: 'bg-emerald-100 text-emerald-800',
  },
  CANCELLED: {
    label: 'Cancelled',
    actionKind: 'RESCHEDULE',
    actionLabel: 'Reschedule',
    pill: 'bg-red-100 text-red-700',
  },
};

export function evaluatorProjectDisplay(
  projectStatus: string | null | undefined,
  evaluationStatus: string | null | undefined,
): EvaluatorProjectDisplay {
  const state = deriveEvaluatorProjectState(projectStatus, evaluationStatus);
  const cfg = CONFIG[state];
  return {
    state,
    label: cfg.label,
    actionKind: cfg.actionKind,
    actionLabel: cfg.actionLabel,
    pill: cfg.pill,
  };
}

/**
 * Table-facing status label — finer-grained than the card label so a
 * dense row can show which pre-verification state the project is in
 * (Not Started vs In Progress vs Submitted vs Returned vs Tech
 * Approved). Post-verification states collapse to the same wording the
 * cards use (Pending Evaluation / Scheduled / Session Completed /
 * Completed / Cancelled). Always returns a real string — never blank,
 * never a dash — including when the project slot is empty ("Not
 * Assigned"), so the Active Evaluees table never renders empty cells.
 */
export function evaluatorProjectStatusLabel(args: {
  projectStatus: string | null | undefined;
  evaluationStatus: string | null | undefined;
  hasProject: boolean;
}): string {
  if (!args.hasProject) return 'Not Assigned';
  const s = args.evaluationStatus;
  if (s === 'CANCELLED') return 'Cancelled';
  if (s === 'PUBLISHED' || s === 'ACKNOWLEDGED' || s === 'AMENDED') return 'Completed';
  if (s === 'IN_PROGRESS') return 'Session Completed';
  if (s === 'SCHEDULED') return 'Scheduled';
  if (args.projectStatus === 'PENDING_VIVA' || args.projectStatus === 'COMPLETED') {
    return 'Pending Evaluation';
  }
  switch ((args.projectStatus ?? '').toUpperCase()) {
    case 'NOT_STARTED':   return 'Not Started';
    case 'IN_PROGRESS':   return 'In Progress';
    case 'SUBMITTED':     return 'Submitted';
    case 'RETURNED':      return 'Returned';
    case 'TECH_APPROVED': return 'Tech Approved';
    default:              return 'In Progress';
  }
}
