// Mirrors backend PerProjectDtos. See:
// apps/backend/src/main/java/com/anvicorp/api/evaluator/perproject/PerProjectDtos.java

export interface AwaitingEvaluationRow {
  evaluationId: string;
  evaluationStatus: string; // DRAFT | SCHEDULED | IN_PROGRESS
  linkedProjectId: string;
  internLifecycleId: string;
  internUserId: string;
  internName: string | null;
  employeeId: string | null;
  projectTitle: string | null;
  techStack: string | null;
  projectSequence: number;
  projectDueDate: string | null;
  projectCompletedAt: string | null;
  projectSubmittedAt: string | null;
  evaluationScheduledFor: string | null;
  hoursWaitingSinceCompletion: number | null;
}

export interface AwaitingEvaluationResponse {
  items: AwaitingEvaluationRow[];
  totalCount: number;
}

export type ProjectTimelineUiState =
  | 'IN_PROGRESS'
  | 'AWAITING_EVAL'
  | 'EVALUATED'
  | 'AMENDED';

export interface ProjectTimelineEntry {
  projectId: string;
  projectSequence: number;
  title: string | null;
  techStack: string | null;
  projectStatus: string; // ProjectStatus name
  assignmentDate: string | null;
  dueDate: string | null;
  submittedAt: string | null;
  completedAt: string | null;
  evaluationId: string | null;
  evaluationStatus: string | null;
  evaluationScheduledFor: string | null;
  evaluationTimezone: string | null;
  evaluationPublishedAt: string | null;
  evaluationOverallScore: number | null;
  evaluationRecommendation: string | null;
  uiState: ProjectTimelineUiState;
}

export interface ProjectTimelineResponse {
  internLifecycleId: string;
  internUserId: string | null;
  internName: string | null;
  employeeId: string | null;
  totalProjects: number;
  evaluatedCount: number;
  awaitingEvaluationCount: number;
  inProgressCount: number;
  entries: ProjectTimelineEntry[];
}

export interface ProjectContext {
  projectId: string;
  title: string | null;
  description: string | null;
  deliverables: string | null;
  requirements: string | null;
  objectives: string | null;
  instructions: string | null;
  techStack: string | null;
  difficulty: string | null;
  projectNumber: number | null;
  monthYear: string | null;
  status: string | null;
  startDate: string | null;
  dueDate: string | null;
  startedAt: string | null;
  submittedAt: string | null;
  completedAt: string | null;
  reviewNotes: string | null;
  reviewerName: string | null;
}

export interface WeeklyReportSummary {
  reportId: string;
  weekStart: string | null;
  status: string | null;
  submittedAt: string | null;
  reviewedAt: string | null;
  completedWork: string | null;
  blockers: string | null;
  attachmentDocumentId?: string | null;
  attachmentFileName?: string | null;
  attachmentMimeType?: string | null;
}

export interface WeeklyReportsResponse {
  internUserId: string | null;
  items: WeeklyReportSummary[];
}

export interface SchedulePostProjectRequest {
  scheduledFor: string; // UTC ISO
  durationMinutes?: number;
  timezone?: string;
  topic?: string | null;
  agenda?: string | null;
}

export interface BulkScheduleRequest {
  evaluationIds: string[];
  scheduledFor: string; // UTC ISO
  durationMinutes?: number;
  timezone?: string;
  topic?: string | null;
  agenda?: string | null;
  /** When true, transitions to IN_PROGRESS instead of SCHEDULED —
   *  used when the meeting already happened and the evaluator is
   *  recording it retroactively across N projects. */
  markConducted?: boolean;
}

export interface BulkScheduleResponse {
  updatedCount: number;
  zoomMeetingId: string | null;
  zoomJoinUrl: string | null;
  evaluationIds: string[];
}
