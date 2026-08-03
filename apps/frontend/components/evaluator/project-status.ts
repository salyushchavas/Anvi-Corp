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
 * Project statuses at which the evaluator may schedule a Final Session
 * for a project that has NO POST_PROJECT eval row yet (self-heal path).
 * Deliberately narrower than "any status not-yet evaluated" — pre-viva
 * states (SUBMITTED, IN_PROGRESS, RETURNED, NOT_STARTED) are excluded
 * because scheduling a viva before the trainer has verified the code
 * inverts the workflow.
 */
const NO_EVAL_SCHEDULABLE_STATUSES: ReadonlySet<string> = new Set([
  'PENDING_VIVA',
  'TECH_APPROVED',
  'COMPLETED',
]);

/**
 * Statuses of an EXISTING POST_PROJECT eval row that keep it inside the
 * Final Session picker. Anything else — PUBLISHED / ACKNOWLEDGED /
 * AMENDED / IN_PROGRESS / CANCELLED — is either done or in-session and
 * should not be re-scheduled from this dialog.
 */
const OPEN_EVAL_STATUSES: ReadonlySet<string> = new Set([
  'DRAFT',
  'SCHEDULED',
]);

/**
 * Two-branch eligibility for the "Schedule Final Session" picker:
 * (a) POST_PROJECT eval exists in DRAFT/SCHEDULED — schedule the row, or
 * (b) NO eval exists AND project status is in the schedulable set
 *     (PENDING_VIVA / TECH_APPROVED / COMPLETED) — server will
 *     auto-draft on submit.
 *
 * Projects with an eval past the DRAFT/SCHEDULED window are excluded —
 * they've already been evaluated (or session is in progress) and
 * belong on other surfaces.
 */
export function isEligibleForFinalSession(entry: {
  evaluationId: string | null;
  evaluationStatus: string | null;
  projectStatus: string;
}): boolean {
  if (entry.evaluationId != null) {
    return entry.evaluationStatus != null
      && OPEN_EVAL_STATUSES.has(entry.evaluationStatus);
  }
  return NO_EVAL_SCHEDULABLE_STATUSES.has(entry.projectStatus);
}

/**
 * Which of the two eligibility branches this entry falls into — the
 * dialog needs this so it can route (a) entries as evaluationIds and
 * (b) entries as projectIds in the same bulk-schedule call.
 */
export type FinalSessionEligibility = 'DRAFT_OR_SCHEDULED_EVAL' | 'AUTO_DRAFT_ON_SUBMIT';

export function finalSessionEligibility(entry: {
  evaluationId: string | null;
  evaluationStatus: string | null;
  projectStatus: string;
}): FinalSessionEligibility | null {
  if (entry.evaluationId != null
      && entry.evaluationStatus != null
      && OPEN_EVAL_STATUSES.has(entry.evaluationStatus)) {
    return 'DRAFT_OR_SCHEDULED_EVAL';
  }
  if (entry.evaluationId == null
      && NO_EVAL_SCHEDULABLE_STATUSES.has(entry.projectStatus)) {
    return 'AUTO_DRAFT_ON_SUBMIT';
  }
  return null;
}

/**
 * Short, friendly hint the picker row shows next to the title so the
 * evaluator sees WHY a project is schedulable without decoding enum
 * names. Returns null when the row shouldn't be in the picker at all.
 */
export function finalSessionRowHint(entry: {
  evaluationId: string | null;
  evaluationStatus: string | null;
  projectStatus: string;
}): string | null {
  const kind = finalSessionEligibility(entry);
  if (kind == null) return null;
  if (kind === 'DRAFT_OR_SCHEDULED_EVAL') {
    return entry.evaluationStatus === 'SCHEDULED' ? 'Scheduled' : 'Draft ready';
  }
  switch (entry.projectStatus) {
    case 'PENDING_VIVA':   return 'Awaiting viva';
    case 'TECH_APPROVED':  return 'Tech approved';
    case 'COMPLETED':      return 'Completed';
    default:               return null;
  }
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
