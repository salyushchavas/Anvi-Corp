/**
 * Status semantic mapping — the single source of truth for how every
 * workflow status string is colored and iconified across the dashboard.
 *
 * Five tones: success (green), info (blue), warning (amber), danger (red),
 * neutral (slate). Adding a new status string is one line below.
 *
 * Every consumer (StatusPill, Stepper, ActionRow, kanban cards, anywhere
 * else that paints a status) maps via {@link statusSemantic}. Never inline
 * a color lookup outside this module.
 */

export type Semantic = 'success' | 'info' | 'warning' | 'danger' | 'neutral';

export const STATUS_SEMANTIC: Record<string, Semantic> = {
  // ── success ───────────────────────────────────────────────────────────
  DONE: 'success',
  COMPLETED: 'success',
  APPROVED: 'success',
  ACTIVE: 'success',
  HIRED: 'success',
  AUTHORIZED: 'success',
  EMPLOYMENT_AUTHORIZED: 'success',
  READY_TO_START: 'success',
  TECH_APPROVED: 'success',
  ACCESS_ACCEPTED: 'success',
  ACCEPTED: 'success',
  CONDUCTED: 'success',
  DSO_APPROVED: 'success',
  EVERIFY_CLEARED: 'success',
  SIGNED_OFF: 'success',

  // ── info ──────────────────────────────────────────────────────────────
  IN_PROGRESS: 'info',
  SUBMITTED: 'info',
  SCHEDULED: 'info',
  ACCESS_GRANTED: 'info',
  PENDING_VIVA: 'info',
  PENDING_REVIEW: 'info',
  SCREENING_SENT: 'info',
  SCREENING_COMPLETED: 'info',
  INTERVIEW_SCHEDULED: 'info',
  INTERVIEWED: 'info',
  SHORTLISTED: 'info',
  OFFERED: 'info',
  SELECTED_CONDITIONAL: 'info',
  ONBOARDING: 'info',
  SUBMITTED_TO_DSO: 'info',
  OPEN: 'info',

  // ── warning ───────────────────────────────────────────────────────────
  WAITING: 'warning',
  PENDING_COMPLIANCE: 'warning',
  PENDING_USER_ACTION: 'warning',
  AWAITING_HR_ACTIVATION: 'warning',
  AWAITING_HR_I9: 'warning',
  AWAITING_REVIEW: 'warning',
  RETURNED: 'warning',
  PAUSED: 'warning',
  REOPENED: 'warning',
  AMENDMENT_REQUESTED: 'warning',
  TENTATIVE_NONCONFIRMATION: 'warning',
  PENDING_SUBMISSION: 'warning',
  SECTION_2_PENDING: 'warning',

  // ── danger ────────────────────────────────────────────────────────────
  REJECTED: 'danger',
  FAILED: 'danger',
  OVERDUE: 'danger',
  ERROR: 'danger',
  BLOCKED: 'danger',
  BLOCKED_NO_AUTHORIZATION: 'danger',
  TERMINATED: 'danger',
  FINAL_NONCONFIRMATION: 'danger',
  DSO_REJECTED: 'danger',
  DECLINED: 'danger',
  REVOKED: 'danger',
  EXPIRED: 'danger',
  WITHDRAWN: 'danger',
  LAPSED: 'danger',
  NO_SHOW: 'danger',
  CANCELLED: 'danger',

  // ── neutral ───────────────────────────────────────────────────────────
  NOT_STARTED: 'neutral',
  DRAFT: 'neutral',
  SKIPPED: 'neutral',
  INACTIVE: 'neutral',
  ASSIGNED: 'neutral',
  APPLIED: 'neutral',
  CLOSED: 'neutral',
  SENT: 'neutral',
  COMPLETE: 'neutral',
};

/**
 * Map a status string (case-insensitive) to a semantic tone. Unknown values
 * fall back to neutral so we never surface a default bright color for a
 * label we haven't classified yet.
 */
export function statusSemantic(status: string | null | undefined): Semantic {
  if (!status) return 'neutral';
  const key = status.toString().toUpperCase().trim();
  return STATUS_SEMANTIC[key] ?? 'neutral';
}

/** Human-readable label for a status — turns `IN_PROGRESS` into `In progress`. */
export function statusLabel(status: string | null | undefined): string {
  if (!status) return '—';
  const s = status.toString().toLowerCase().replace(/[_-]+/g, ' ');
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Tailwind class triplet for surface + text + border in a given tone. */
export const TONE_CLASSES: Record<Semantic, { bg: string; text: string; border: string; icon: string; ring: string }> = {
  success: {
    bg: 'bg-green-50',
    text: 'text-green-700',
    border: 'border-green-200',
    icon: 'text-green-600',
    ring: 'ring-green-200',
  },
  info: {
    bg: 'bg-slate-100',
    text: 'text-slate-700',
    border: 'border-slate-300',
    icon: 'text-slate-500',
    ring: 'ring-slate-200',
  },
  warning: {
    bg: 'bg-amber-50',
    text: 'text-amber-800',
    border: 'border-amber-200',
    icon: 'text-amber-600',
    ring: 'ring-amber-200',
  },
  danger: {
    bg: 'bg-red-50',
    text: 'text-red-700',
    border: 'border-red-200',
    icon: 'text-red-600',
    ring: 'ring-red-200',
  },
  neutral: {
    bg: 'bg-slate-50',
    text: 'text-slate-700',
    border: 'border-slate-200',
    icon: 'text-slate-500',
    ring: 'ring-slate-200',
  },
};
