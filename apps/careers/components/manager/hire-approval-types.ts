// Mirrors com.skyzen.careers.manager.hire.ManagerHireApprovalDtos.
// Single source of truth for the Hire Approvals queue + detail.

export type HireApprovalRow = {
  interviewId: string;
  applicationId: string | null;
  candidateUserId: string | null;
  candidateName: string | null;
  candidateEmail: string | null;
  jobTitle: string | null;
  technology: string | null;
  scorecardSubmittedAt: string | null;
  hoursWaiting: number;
  technicalScore: number | null;
  communicationScore: number | null;
  culturalFitScore: number | null;
  overallRecommendation: string | null;
  /** PENDING | HOLD — queue filters out APPROVED/REJECTED. */
  managerHireDecision: 'PENDING' | 'HOLD' | null;
};

export type HireApprovalListPage = {
  items: HireApprovalRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
};

export type HireApprovalDetail = {
  interviewId: string;
  applicationId: string | null;
  candidateUserId: string | null;
  candidateName: string | null;
  candidateEmail: string | null;
  jobTitle: string | null;
  technology: string | null;
  interviewScheduledAt: string | null;
  scorecardSubmittedAt: string | null;
  scorecardSubmittedByName: string | null;
  technicalScore: number | null;
  communicationScore: number | null;
  culturalFitScore: number | null;
  overallRecommendation: string | null;
  internalNotes: string | null;
  applicantVisibleNotes: string | null;
  managerHireDecision: 'PENDING' | 'APPROVED' | 'REJECTED' | 'HOLD' | null;
  managerHireDecisionAt: string | null;
  managerHireDecisionNote: string | null;
  /** Future-phase placeholder. Null today; the detail page renders a
   *  "Recording will appear here once the integration lands" card. */
  zoomRecordingUrl: string | null;
  /** Resume on the application this interview was for. Null when the
   *  candidate applied without one. Shape parity with the ERM-side
   *  ResumeView so the shared ResumePreview component takes it
   *  directly. */
  resume: HireApprovalResume | null;
};

export type HireApprovalResume = {
  documentId: string;
  fileName: string;
  fileSize: number | null;
  mimeType: string | null;
  downloadUrl: string;
};

export type HireApprovalDecisionRequest = {
  note?: string | null;
};
