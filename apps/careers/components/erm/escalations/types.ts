// ERM Phase 6 — escalations queue DTO mirrors.

export type Severity = 'URGENT' | 'WARN' | 'INFO';

export type ExceptionStatus =
  | 'OPEN'
  | 'ASSIGNED'
  | 'IN_PROGRESS'
  | 'RESOLVED'
  | 'DISMISSED'
  | 'AUTO_RESOLVED';

export interface ExceptionRow {
  id: string;
  exceptionType: string;
  severity: Severity;
  status: ExceptionStatus;
  subjectUserId: string | null;
  subjectName: string | null;
  subjectEmployeeId: string | null;
  internLifecycleId: string | null;
  assignedToId: string | null;
  assignedToName: string | null;
  openedAt: string | null;
  lastSeenAt: string | null;
  assignedAt: string | null;
  resolvedAt: string | null;
  ageDays: number;
  subjectResourceType: string | null;
  subjectResourceId: string | null;
  payloadJson: string | null;
}

export interface ExceptionListPage {
  items: ExceptionRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export interface EventLogEntry {
  id: string;
  actorUserId: string | null;
  actorName: string | null;
  eventType: string;
  previousStatus: string | null;
  newStatus: string | null;
  reasonCode: string | null;
  note: string | null;
  createdAt: string;
}

export interface ExceptionDetail {
  id: string;
  exceptionType: string;
  severity: Severity;
  status: ExceptionStatus;
  subjectUserId: string | null;
  subjectName: string | null;
  subjectEmail: string | null;
  subjectEmployeeId: string | null;
  internLifecycleId: string | null;
  assignedToId: string | null;
  assignedToName: string | null;
  assignedById: string | null;
  resolvedById: string | null;
  resolutionReasonCode: string | null;
  resolutionNote: string | null;
  openedAt: string | null;
  lastSeenAt: string | null;
  assignedAt: string | null;
  resolvedAt: string | null;
  subjectResourceType: string | null;
  subjectResourceId: string | null;
  payloadJson: string | null;
  ageDays: number;
  history: EventLogEntry[];
}

export interface ReasonCodeOption {
  code: string;
  label: string;
  requiresFreeText: boolean;
}

export interface ReasonCodeGroup {
  family: string;
  options: ReasonCodeOption[];
}

export const EXCEPTION_TYPE_LABEL: Record<string, string> = {
  UNSIGNED_OFFER_OVERDUE: 'Unsigned offer overdue',
  ONBOARDING_DOC_REJECTED: 'Onboarding doc rejected',
  I9_EVERIFY_TIMING_RISK: 'I-9 / E-Verify timing risk',
  NO_PROJECT_ASSIGNED: 'No project assigned',
  TRAINER_MEETING_MISSING: 'Trainer meeting missing',
  EVALUATION_OVERDUE: 'Evaluation overdue',
  TIMESHEET_MISSING: 'Timesheet missing',
  EXIT_CHECKLIST_PENDING: 'Exit checklist pending',
  REPORTING_STRUCTURE_INCOMPLETE: 'Reporting structure incomplete',
  WORK_AUTH_EXPIRING_30: 'Work auth expiring ≤ 30 days',
  I983_EVALUATION_OVERDUE: 'I-983 evaluation overdue',
  EVERIFY_NONCONFIRMATION: 'E-Verify nonconfirmation',
  MISSED_TRAINER_MEETING: 'Missed trainer meeting',
  LOW_PROJECT_PROGRESS: 'Low project progress',
  REPEATED_TIMESHEET_REJECTION: 'Repeated timesheet rejection',
};
