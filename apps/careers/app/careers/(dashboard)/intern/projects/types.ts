// Mirrors ProjectAssignmentResponse on the backend. Only the fields the
// intern surface consumes are typed here.

export type ProjectAssignmentStatus =
  | 'ASSIGNED'
  | 'IN_PROGRESS'
  | 'SUBMITTED'
  | 'RETURNED'
  | 'TECH_APPROVED'
  | 'PENDING_VIVA'
  | 'COMPLETED';

export type TrainerDecision =
  | 'ACCEPT'
  | 'REQUEST_REVISION'
  | 'ESCALATE'
  | 'NO_ACTION_YET';

export interface KtSummary {
  status: 'NOT_DONE' | 'DONE';
  completedAt: string | null;
  meetingLink: string | null;
  notes: string | null;
  markedByName: string | null;
  /** Live KT session (Zoom) — null when the trainer hasn't scheduled
   *  a live session. zoomStartUrl is host-only and the intern DTO MUST
   *  strip it server-side; including the field here for type symmetry
   *  with the trainer-side DTO but rendering must not surface it. */
  zoomMeetingId?: string | null;
  zoomJoinUrl?: string | null;
  zoomStartUrl?: string | null;
  scheduledFor?: string | null;
  durationMinutes?: number | null;
  timezone?: string | null;
}

export interface ProjectFileRef {
  id: string;
  fileName: string;
  mimeType: string | null;
  fileSize: number | null;
}

export interface ProjectRef {
  id: string;
  name: string | null;
  techStack: string | null;
  difficulty: string | null;
  description: string | null;
  requirements: string | null;
  objectives: string | null;
  deliverables: string | null;
  instructions: string | null;
  expectedDurationDays: number | null;
  startDate: string | null;
  endDate: string | null;
  repository: { repositoryName: string | null; repositoryUrl: string | null } | null;
  kt: KtSummary | null;
  /** Trainer-uploaded brief/spec/starter files attached at project-assignment
   *  time. Downloadable by the assigned intern via
   *  GET /api/v1/project-assignments/{assignmentId}/file?documentId=... */
  files?: ProjectFileRef[] | null;
}

export interface LatestSubmission {
  id: string;
  version: number;
  links: string[];
  description: string | null;
  submittedAt: string;
  trainerDecision: TrainerDecision | null;
  trainerFeedback: string | null;
  reviewedAt: string | null;
  reviewedByName: string | null;
}

export interface UserRef {
  id: string;
  fullName: string | null;
  email: string | null;
  githubUsername: string | null;
}

/**
 * Active Q&A session summary — surfaced on the assignment row when the
 * evaluator has scheduled a session that hasn't completed/returned yet.
 * Host start_url is HOST-ONLY and is intentionally absent from this
 * intern-facing type.
 */
export interface QaSessionSummary {
  sessionId: string;
  status: 'SCHEDULED' | 'CONDUCTED' | string;
  scheduledAt: string | null;
  durationMinutes: number | null;
  timezone: string | null;
  meetingLink: string | null;
  zoomMeetingId: string | null;
  zoomJoinUrl: string | null;
  scheduledByName: string | null;
}

export interface AssignmentSummary {
  id: string;
  project: ProjectRef | null;
  /** Backend's UserRef intern — includes githubUsername so the intern UI
   *  can decide whether to prompt for one before the Start button enables. */
  intern: UserRef | null;
  status: ProjectAssignmentStatus;
  assignmentDate: string | null;
  dueDate: string | null;
  remarks: string | null;
  accessGranted: boolean | null;
  startedAt: string | null;
  submittedAt: string | null;
  submissionNotes: string | null;
  latestSubmission: LatestSubmission | null;
  /** Active Q&A session (SCHEDULED or CONDUCTED) on this project. Null
   *  when nothing is scheduled yet. */
  qaSession: QaSessionSummary | null;
}
