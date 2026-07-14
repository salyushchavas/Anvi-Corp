// ERM Phase 2 — shared types for the application inbox + detail surfaces.

export type ApplicationStage =
  | 'APPLIED'
  | 'HOLD'
  | 'INFO_REQUESTED'
  | 'SCREENING_SENT'
  | 'SCREENING_COMPLETED'
  | 'SHORTLISTED'
  | 'INTERVIEW_SCHEDULED'
  | 'INTERVIEWED'
  | 'SELECTED_CONDITIONAL'
  | 'OFFERED'
  | 'ACCEPTED'
  | 'HIRED'
  | 'REJECTED'
  | 'WITHDRAWN'
  | 'LAPSED'
  | 'NO_SHOW';

export type DecisionKind = 'SHORTLIST' | 'HOLD' | 'REQUEST_INFO' | 'REJECT';

export interface ApplicationRow {
  applicationId: string;
  applicantId: string | null;
  applicantName: string | null;
  applicantEmail: string | null;
  jobId: string;
  jobTitle: string | null;
  jobType: string | null;
  technology: string | null;
  stage: ApplicationStage;
  lastDecisionAt: string | null;
  ageDays: number;
  workAuthType: string | null;
  workAuthValidUntil: string | null;
  ermOwnerId: string | null;
  ermOwnerName: string | null;
  hasResume: boolean;
  urgentFlag: boolean;
}

export interface ApplicationListPage {
  items: ApplicationRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export interface ReasonCodeOption {
  code: string;
  label: string;
  requiresFreeText: boolean;
}

export interface ReasonCodeGroup {
  category: string;
  options: ReasonCodeOption[];
}

export interface DecisionLogEntry {
  id: string;
  decision: string;
  reasonCode: string | null;
  reasonCodeLabel: string | null;
  reasonText: string | null;
  previousStage: string;
  newStage: string;
  applicantVisibleMessage: string | null;
  decidedById: string;
  decidedByName: string | null;
  decidedAt: string;
}

export interface AvailableActions {
  canShortlist: boolean;
  canHold: boolean;
  canRequestInfo: boolean;
  canReject: boolean;
  canResumeFromHold: boolean;
}

export interface ApplicationDetail {
  application: {
    id: string;
    stage: ApplicationStage;
    appliedAt: string;
    statusUpdatedAt: string;
    ermOwnerId: string | null;
    ermOwnerName: string | null;
    internalNotes: string | null;
    lastDecisionReasonCode: string | null;
    lastDecisionReasonText: string | null;
    lastDecisionAt: string | null;
    lastDecisionById: string | null;
    lastDecisionByName: string | null;
    infoRequestedFieldsCsv: string | null;
    infoRequestedAt: string | null;
    statementOfInterest: string | null;
    applicantVisibleFeedback: string | null;
  };
  applicant: {
    userId: string;
    firstName: string;
    lastName: string;
    email: string;
    phone: string | null;
    applicantId: string | null;
    employeeId: string | null;
    emailVerified: boolean;
  } | null;
  applicantProfile: {
    education: string | null;       // legacy free-text
    school: string | null;
    degree: string | null;          // legacy free-text
    // Phase 1.5 — structured education. Backend already prefers these
    // for new registrations; ERM detail falls back to the legacy
    // free-text trio when these are null.
    degreeLevel: string | null;
    specialization: string | null;
    graduationYear: number | null;
    skillset: string | null;
    workAuthType: string | null;
    authorizedToWork: boolean | null;
    sponsorshipNeeded: boolean | null;
    workAuthValidUntil: string | null;
    workAuthValidFrom: string | null;
    statementOfInterest: string | null;
  } | null;
  resume: {
    documentId: string;
    fileName: string;
    fileSize: number | null;
    mimeType: string | null;
    downloadUrl: string;
  } | null;
  job: {
    id: string;
    title: string | null;
    jobType: string | null;
    technology: string | null;
    location: string | null;
    workModel: string | null;
    descriptionExcerpt: string | null;
  } | null;
  decisionHistory: DecisionLogEntry[];
  availableActions: AvailableActions;
}
