// ERM onboarding tracker — wire shape mirrors
// backend/.../erm/newhire/OnboardingTrackerDtos.java. Step ids are part of
// the API contract; the switch in OnboardingStepTracker.tsx maps each id to
// the right action component (modal / redirect / waiting+reminder / gated).

export type OnboardingStepId =
  | 'DOCS_ASSIGNED'
  | 'DOCS_VERIFIED'
  | 'TEAM_NOTIFIED'
  | 'MAIL_AND_JOINING'
  | 'ACTIVATE';

export type OnboardingStepStatus =
  | 'DONE'
  | 'CURRENT'
  | 'WAITING_INTERN'
  | 'PENDING'
  | 'LOCKED';

export type OnboardingStepActor = 'ERM' | 'INTERN' | 'SYSTEM';

export type OnboardingActionType =
  | 'MODAL'
  | 'REDIRECT'
  | 'WAIT_REMINDER'
  | 'GATED'
  | 'NONE';

export interface OnboardingSubTask {
  label: string;
  done: boolean;
}

export interface OnboardingStep {
  id: OnboardingStepId;
  label: string;
  status: OnboardingStepStatus;
  actor: OnboardingStepActor;
  actionType: OnboardingActionType;
  completedAt: string | null;
  helpText: string | null;
  redirectHref: string | null;
  subTasks: OnboardingSubTask[];
}

export interface OnboardingTracker {
  internLifecycleId: string;
  steps: OnboardingStep[];
  currentStepId: OnboardingStepId | null;
  stepsCompleted: number;
  stepsTotal: number;
  stepsRemaining: number;
  nextStepLabel: string | null;
  canActivate: boolean;
}
