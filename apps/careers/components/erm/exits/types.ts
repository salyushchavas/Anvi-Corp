// ERM Phase 7 — Exit operations DTO mirrors.

export type ExitType = 'COMPLETED' | 'RESIGNED' | 'TERMINATED' | 'EXTENDED';
export type ChecklistStatus = 'PENDING' | 'COMPLETE' | 'NOT_APPLICABLE' | 'WAIVED';
export type OverallState = 'ACTIVE' | 'READY_TO_CLOSE' | 'CLOSED';

export interface ErmExitRow {
  exitRecordId: string;
  internLifecycleId: string;
  internUserId: string;
  internName: string | null;
  employeeId: string | null;
  exitType: ExitType;
  exitDate: string | null;
  lastWorkingDay: string | null;
  totalItems: number;
  completeItems: number;
  pendingItems: number;
  waivedItems: number;
  notApplicableItems: number;
  daysSinceInitiate: number;
  managerOverridden: boolean;
  overallState: OverallState;
}

export interface ErmExitListPage {
  items: ErmExitRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export interface ReadyToExitRow {
  internLifecycleId: string;
  internUserId: string;
  internName: string | null;
  employeeId: string | null;
  daysActive: number;
  suggestedExitType: ExitType;
  signals: string[];
}

export interface ReadyToExitListPage {
  items: ReadyToExitRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export interface ChecklistItemRow {
  id: string;
  itemKey: string;
  status: ChecklistStatus;
  completedAt: string | null;
  completedById: string | null;
  completedByName: string | null;
  note: string | null;
  updatedAt: string;
}

export interface AssetStatus {
  laptopReturned: boolean | null;
  badgeReturned: boolean | null;
  buildingAccessRemoved: boolean | null;
  parkingPassReturned: boolean | null;
  keysReturned: boolean | null;
  otherNotes: string | null;
}

export interface ErmExitDetail {
  exitRecordId: string;
  internLifecycleId: string;
  internUserId: string;
  internName: string | null;
  internEmail: string | null;
  employeeId: string | null;
  exitType: ExitType;
  exitDate: string | null;
  lastWorkingDay: string | null;
  reasonCode: string | null;
  exitReason: string | null;
  internVisibleSummary: string | null;
  internalNotes: string | null;
  assetStatusJson: string | null;
  finalTimesheetStatus: string | null;
  rehireEligible: boolean | null;
  accessRevocationDone: boolean | null;
  accessRevocationSummary: string | null;
  accessRevocationCompletedAt: string | null;
  finalDocumentsArchived: boolean | null;
  finalDocumentsArchivedAt: string | null;
  finalEvaluationId: string | null;
  managerOverrideId: string | null;
  managerOverrideReason: string | null;
  managerOverrideAt: string | null;
  initiatedById: string | null;
  initiatedByName: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  daysSinceInitiate: number;
  checklist: ChecklistItemRow[];
  readyToClose: boolean;
  feedbackSubmitted: boolean;
}

export interface InitiateExitRequest {
  internLifecycleId: string;
  exitType: ExitType;
  exitDate: string;
  lastWorkingDay?: string;
  reasonCode?: string;
  reasonText?: string;
  internVisibleSummary?: string;
  rehireEligible?: boolean;
  finalTimesheetStatus?: string;
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

export const CHECKLIST_LABEL: Record<string, string> = {
  FINAL_EVALUATION: 'Final evaluation linked',
  OUTSTANDING_TIMESHEETS: 'No outstanding timesheets',
  OUTSTANDING_PROJECTS: 'No outstanding projects',
  GITHUB_REVOKED: 'GitHub access revoked',
  ASSETS_RETURNED: 'Assets returned',
  DOCUMENTS_ARCHIVED: 'Final documents archived',
  EXIT_FEEDBACK_SUBMITTED: 'Intern exit feedback submitted',
  FINAL_PAYROLL_CONFIRMED: 'Final payroll confirmed',
};

export const EXIT_TYPE_TONE: Record<ExitType, string> = {
  COMPLETED: 'bg-green-100 text-green-800',
  RESIGNED: 'bg-amber-100 text-amber-800',
  TERMINATED: 'bg-red-100 text-red-800',
  EXTENDED: 'bg-slate-100 text-slate-700',
};
