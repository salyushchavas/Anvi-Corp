// ERM Phase 3 — shared types for the interview scheduler + decision center.

export type InterviewStatus = 'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';

export type Decision = 'SELECTED' | 'HOLD' | 'REJECTED';

export type ReasonFamily = 'DECISION' | 'RESCHEDULE' | 'CANCEL';

export interface InterviewRow {
  interviewId: string;
  applicationId: string;
  applicantName: string | null;
  applicantId: string | null;
  jobTitle: string | null;
  jobType: string | null;
  scheduledAt: string;
  durationMinutes: number | null;
  timezone: string | null;
  status: InterviewStatus;
  decision: string | null;
  interviewerId: string | null;
  interviewerName: string | null;
  rescheduleCount: number;
}

export interface InterviewListPage {
  items: InterviewRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export interface CalendarEntry {
  interviewId: string;
  applicationId: string;
  applicantName: string | null;
  jobTitle: string | null;
  scheduledAt: string;
  durationMinutes: number | null;
  timezone: string | null;
  status: InterviewStatus;
  decision: string | null;
  interviewerName: string | null;
}

export interface ReasonOption {
  code: string;
  label: string;
  requiresFreeText: boolean;
}

export interface ReasonGroup {
  category: string;
  options: ReasonOption[];
}

export interface InterviewerView {
  userId: string;
  fullName: string | null;
  email: string | null;
  role: string | null;
  zoomEmail: string | null;
  hasZoomEmail: boolean;
}

export interface EventLogEntry {
  id: string;
  eventType: string;
  reasonCode: string | null;
  reasonText: string | null;
  payloadJson: string | null;
  actorUserId: string | null;
  actorName: string | null;
  createdAt: string;
}

export interface AvailableActions {
  canComplete: boolean;
  canReschedule: boolean;
  canChangeInterviewer: boolean;
  canCancel: boolean;
  canEditNotes: boolean;
  canSendReminder: boolean;
}

/**
 * Outcome of the most recent Zoom call attempt for the interview.
 * Mirrors `ErmInterviewDtos.ZoomStatus` on the backend; drives the ERM
 * detail page's status banner + Regenerate button.
 */
export type ZoomStatus =
  | 'OK'
  | 'MANUAL_LINK'
  | 'NOT_CONFIGURED'
  | 'DISABLED'
  | 'CREATE_FAILED'
  | 'UPDATE_FAILED'
  | 'UNKNOWN';

export interface InterviewDetail {
  id: string;
  status: InterviewStatus;
  type: string | null;
  scheduledAt: string;
  durationMinutes: number | null;
  timezone: string | null;
  prepInstructions: string | null;
  zoomJoinUrl: string | null;
  zoomStartUrl: string | null;
  zoomPassword: string | null;
  zoomMeetingId: string | null;
  zoomStatus: ZoomStatus;
  /** Truncated Zoom error message — null for non-staff or when no error. */
  zoomErrorMessage: string | null;
  decision: string | null;
  overallRecommendation: string | null;
  technicalScore: number | null;
  communicationScore: number | null;
  culturalFitScore: number | null;
  applicantVisibleNotes: string | null;
  internalNotes: string | null;
  decisionReasonCode: string | null;
  decisionReasonText: string | null;
  managerHireDecision: string | null;
  managerHireDecisionAt: string | null;
  managerHireDecisionNote: string | null;
  rescheduleCount: number;
  lastRescheduleReasonCode: string | null;
  lastRescheduleReasonText: string | null;
  lastRescheduledAt: string | null;
  cancellationReasonCode: string | null;
  cancellationReasonText: string | null;
  cancelledAt: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  applicant: {
    userId: string;
    firstName: string;
    lastName: string;
    email: string;
    applicantId: string | null;
    applicationId: string;
    applicationStatus: string;
  } | null;
  job: {
    id: string;
    title: string | null;
    jobType: string | null;
    location: string | null;
  } | null;
  interviewer: InterviewerView | null;
  panel: InterviewerView[];
  history: EventLogEntry[];
  availableActions: AvailableActions;
  callerRole: string;
}
