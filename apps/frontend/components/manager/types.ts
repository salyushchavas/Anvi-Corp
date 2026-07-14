// Manager Phase 1 — TS mirrors of ManagerDtos. Read-only payloads:
// Executive Overview headline + the filterable applicant pipeline.

export interface CallerView {
  userId: string;
  fullName: string;
  email: string;
  role: 'MANAGER' | 'SUPER_ADMIN';
  superAdmin: boolean;
}

export interface HeadlineBuckets {
  totalApplications: number;
  applicantsInPipeline: number;
  offersAwaitingSignature: number;
  prospectiveNewHires: number;
  activeInterns: number;
  inactiveInterns: number;
}

export interface ConversionKpis {
  shortlistConversionPct: number | null;
  interviewCompletionPct: number | null;
  offerSignaturePct: number | null;
  offersPendingOver7Days: number;
}

export interface OverviewResponse {
  caller: CallerView;
  lifecycleCounts: Record<string, number>;
  applicationCounts: Record<string, number>;
  buckets: HeadlineBuckets;
  kpis: ConversionKpis;
  generatedAt: string;
}

export interface PipelineRow {
  applicationId: string;
  applicantName: string | null;
  applicantId: string | null;
  applicantEmail: string | null;
  jobTitle: string | null;
  jobType: string | null;
  technology: string | null;
  stage: string;
  latestInterviewStatus: string | null;
  lastDecisionAt: string | null;
  ageDays: number;
  ermOwnerId: string | null;
  ermOwnerName: string | null;
  expectedStartDate: string | null;
}

export interface ErmOwnerOption {
  userId: string;
  fullName: string;
}

export interface FilterOptions {
  stages: string[];
  jobTypes: string[];
  ermOwners: ErmOwnerOption[];
}

export interface PipelineResponse {
  items: PipelineRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  filters: FilterOptions;
}

// ── Onboarding Health (Phase 2) ──────────────────────────────────────────

export interface DocumentSummary {
  packetStatus: string | null;
  totalTasks: number;
  acceptedTasks: number;
  submittedTasks: number;
  pendingTasks: number;
  rejectedTasks: number;
  waivedTasks: number;
  hasRejected: boolean;
  lastReviewedAt: string | null;
}

export interface ComplianceSummary {
  i9Status: string | null;
  i9Section2DueDate: string | null;
  i9Overdue: boolean;
  everifyStatus: string | null;
  everifyDueBy: string | null;
  everifyOverdue: boolean;
  workAuthValidUntil: string | null;
  workAuthExpiringSoon: boolean;
}

export interface OnboardingRow {
  internLifecycleId: string;
  internUserId: string;
  internName: string | null;
  internEmail: string | null;
  employeeId: string | null;
  workAuthType: string | null;
  lifecycleStatus: string;
  tentativeStartDate: string | null;
  daysUntilStart: number | null;
  startDateAtRisk: boolean;
  documents: DocumentSummary | null;
  compliance: ComplianceSummary | null;
  ermOwnerId: string | null;
  ermOwnerName: string | null;
  managerId: string | null;
  managerName: string | null;
}

export interface OnboardingResponse {
  items: OnboardingRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export interface OnboardingSummary {
  offersAwaitingSignature: number;
  newHiresOnboarding: number;
  onboardingAccepted: number;
  i9Overdue: number;
  everifyOverdue: number;
  startDateAtRisk: number;
}

export interface OnboardingFilterOptions {
  lifecycleStages: string[];
  workAuthTypes: string[];
  ermOwners: ErmOwnerOption[];
}

// ── Active Interns (Phase 3A) ────────────────────────────────────────────

export interface ProjectState {
  status: string | null;
  projectTitle: string | null;
  dueDate: string | null;
  atRisk: boolean;
}

export interface MeetingState {
  lastMeetingStatus: string | null;
  lastMeetingAt: string | null;
  daysSinceLastMeeting: number | null;
  atRisk: boolean;
}

export interface EvaluationState {
  lastEvaluationStatus: string | null;
  lastPublishedAt: string | null;
  overallScore: number | null;
  recommendation: string | null;
  daysSinceLastPublished: number | null;
  atRisk: boolean;
}

export interface TimesheetState {
  currentWeekStatus: string | null;
  previousWeekStatus: string | null;
  recentRejections: number;
  atRisk: boolean;
}

export interface ActiveInternRow {
  internLifecycleId: string;
  internUserId: string;
  internName: string | null;
  internEmail: string | null;
  employeeId: string | null;
  technology: string | null;
  workAuthType: string | null;
  health: 'ACTIVE_ON_TRACK' | 'ACTIVE_AT_RISK';
  project: ProjectState;
  meeting: MeetingState;
  evaluation: EvaluationState;
  timesheet: TimesheetState;
  managerId: string | null;
  managerName: string | null;
  ermOwnerId: string | null;
  ermOwnerName: string | null;
  trainerId: string | null;
  trainerName: string | null;
  evaluatorId: string | null;
  evaluatorName: string | null;
  startedAt: string | null;
  monthsInProgram: number | null;
}

export interface ActiveInternResponse {
  items: ActiveInternRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export interface ActiveInternSummary {
  activeInternsTotal: number;
  onTrack: number;
  atRisk: number;
  noProjectAssigned: number;
  trainerMeetingMissing: number;
  evaluationOverdue: number;
  timesheetMissingThisWeek: number;
}

export interface UserOption {
  userId: string;
  fullName: string;
}

export interface ActiveInternFilterOptions {
  technologies: string[];
  trainers: UserOption[];
  evaluators: UserOption[];
  managers: UserOption[];
  ermOwners: ErmOwnerOption[];
}

// ── Timesheet Approvals (Phase 3B) ───────────────────────────────────────

export interface TimesheetDayBreakdown {
  dayOfWeek: string;
  hours: number | string;
  notes: string | null;
}

export interface TimesheetRow {
  timesheetId: string;
  internUserId: string;
  internName: string | null;
  employeeId: string | null;
  technology: string | null;
  managerId: string | null;
  managerName: string | null;
  ermOwnerId: string | null;
  ermOwnerName: string | null;
  weekStart: string | null;
  status: 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';
  approvedById: string | null;
  approvedByName: string | null;
  approvedAt: string | null;
  /** Server-authoritative — true iff this manager (or SUPER_ADMIN) can
   *  approve/reject this specific row. Drives whether the UI shows the
   *  action buttons + hours/detail. */
  canAct: boolean;
  hours: number | string | null;
  description: string | null;
  days: TimesheetDayBreakdown[] | null;
  reviewNote: string | null;
}

export interface TimesheetListResponse {
  items: TimesheetRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export interface TimesheetFilterOptions {
  statuses: string[];
  technologies: string[];
  managers: UserOption[];
  ermOwners: ErmOwnerOption[];
}

// ── Risk Center (Phase 4A) ───────────────────────────────────────────────

export interface RiskRow {
  id: string;
  exceptionType: string;
  severity: 'URGENT' | 'WARN' | 'INFO';
  status: 'OPEN' | 'ASSIGNED' | 'IN_PROGRESS' | 'RESOLVED' | 'DISMISSED' | 'AUTO_RESOLVED';
  subjectUserId: string | null;
  subjectName: string | null;
  subjectEmployeeId: string | null;
  internLifecycleId: string | null;
  assignedToId: string | null;
  assignedToName: string | null;
  managerId: string | null;
  managerName: string | null;
  ermOwnerId: string | null;
  ermOwnerName: string | null;
  openedAt: string | null;
  lastSeenAt: string | null;
  assignedAt: string | null;
  resolvedAt: string | null;
  ageDays: number;
  subjectResourceType: string | null;
  subjectResourceId: string | null;
  payloadJson: string | null;
}

export interface RiskListResponse {
  items: RiskRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export interface RiskTypeCount {
  exceptionType: string;
  severity: 'URGENT' | 'WARN' | 'INFO';
  openCount: number;
}

export interface RiskSummary {
  totalOpen: number;
  urgent: number;
  warn: number;
  info: number;
  assigned: number;
  resolvedLast30Days: number;
  byType: RiskTypeCount[];
}

export interface RiskFilterOptions {
  severities: string[];
  statuses: string[];
  exceptionTypes: string[];
  assignees: UserOption[];
  managers: UserOption[];
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

export interface RiskDetail {
  id: string;
  exceptionType: string;
  severity: 'URGENT' | 'WARN' | 'INFO';
  status: RiskRow['status'];
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
