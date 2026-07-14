// Evaluator Phase 1 — shared types for dashboard, evaluees list/detail,
// and right-side panel. Mirrors EvaluatorDtos.java on the backend.

export interface CallerView {
  userId: string;
  fullName: string;
  email: string;
}

export interface KpiSnapshot {
  key: string;
  label: string;
  count: number;
  urgentCount: number;
  helperText: string | null;
  actionUrl: string;
}

export interface DashboardResponse {
  caller: CallerView;
  monthYearLabel: string;
  kpis: KpiSnapshot[];
}

export interface ActiveEvalueeRow {
  lifecycleId: string;
  internUserId: string;
  internName: string | null;
  employeeId: string | null;
  technology: string | null;
  workAuthType: string | null;
  startedAt: string | null;
  monthsInProgram: number;
  lastEvaluationAt: string | null;
  lastEvaluationStatus: string | null;
  lastEvaluationType: string | null;
  pendingAckCount: number;
  i983DueWithinDays: number | null;
  trainerName: string | null;
  ermName: string | null;
}

export interface ActiveEvalueesPage {
  items: ActiveEvalueeRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export interface EvalueeProfile {
  lifecycleId: string;
  internUserId: string;
  internName: string | null;
  internEmail: string | null;
  applicantId: string | null;
  employeeId: string | null;
  technology: string | null;
  workAuthType: string | null;
  startedAt: string | null;
  monthsInProgram: number;
  totalEvaluationsToDate: number;
  lastEvaluationAt: string | null;
}

export interface CurrentMonthCard {
  monthStatus: string;
  monthYearLabel: string;
  currentEvaluationId: string | null;
  publishedAt: string | null;
  daysSincePublish: number | null;
  actionNeeded: boolean;
}

export interface HistorySummaryCard {
  totalEvaluations: number;
  averageOverallScore: number | null;
  trend: string;
}

export interface I983StatusCard {
  planStatus: string;
  lastI983EvaluationAt: string | null;
  lastI983Status: string | null;
  nextDueDate: string | null;
  daysUntilNext: number | null;
}

export interface TrainerContextCard {
  currentProjectId: string | null;
  currentProjectTitle: string | null;
  currentProjectStatus: string | null;
  currentProjectDueDate: string | null;
  lastFeedbackDecision: string | null;
  lastFeedbackAt: string | null;
  lastMeetingScheduledFor: string | null;
  lastMeetingStatus: string | null;
  daysSinceLastMeeting: number | null;
  trainerName: string | null;
}

export interface EvaluationTimelineEntry {
  evaluationId: string;
  entryKind: 'INTERN_EVALUATION' | 'I983_EVALUATION';
  evaluationType: string | null;
  status: string | null;
  publishedAt: string | null;
  acknowledgedAt: string | null;
  overallScore: number | null;
  summary: string | null;
}

export interface EvalueeDetail {
  profile: EvalueeProfile;
  currentMonth: CurrentMonthCard;
  historySummary: HistorySummaryCard;
  i983Status: I983StatusCard | null;
  trainerContext: TrainerContextCard | null;
  timeline: EvaluationTimelineEntry[];
}

export interface HomeAggregate {
  activeEvaluees: number;
  evaluationsThisMonth: number;
  pendingAcknowledgments: number;
}

export interface EvalueePanelContext {
  lifecycleId: string;
  internName: string | null;
  employeeId: string | null;
  technology: string | null;
  workAuthType: string | null;
  monthsInProgram: number;
  lastEvaluationAt: string | null;
  lastEvaluationStatus: string | null;
  isStemOpt: boolean;
}

export interface RightPanelResponse {
  monthYearLabel: string;
  homeAggregate: HomeAggregate | null;
  evalueeContext: EvalueePanelContext | null;
}

// ── Phase 2 — workflow ────────────────────────────────────────────────

export interface AmendmentEntry {
  amendmentId: string;
  amendedByUserId: string;
  amendedByName: string | null;
  amendmentReason: string;
  previousVersion: number;
  newVersion: number;
  amendedAt: string;
}

export interface EvaluatorEvaluationDetail {
  evaluationId: string;
  internLifecycleId: string;
  internUserId: string;
  internName: string | null;
  internEmail: string | null;
  employeeId: string | null;
  technology: string | null;
  evaluationType: string;
  status: string;
  version: number;
  periodStart: string | null;
  periodEnd: string | null;
  scheduledFor: string | null;
  durationMinutes: number | null;
  timezone: string | null;
  zoomJoinUrl: string | null;
  zoomStartUrl: string | null;
  zoomMeetingId: string | null;
  technicalSkillsScore: number | null;
  communicationScore: number | null;
  professionalismScore: number | null;
  learningApplicationScore: number | null;
  averageScore: number | null;
  strengths: string | null;
  areasForImprovement: string | null;
  comments: string | null;
  recommendation: string | null;
  internalNotes: string | null;
  publishedAt: string | null;
  internAcknowledgedAt: string | null;
  internResponse: string | null;
  amendedAt: string | null;
  amendmentReason: string | null;
  amendments: AmendmentEntry[];
}

export interface InternEvaluationView {
  evaluationId: string;
  evaluatorName: string | null;
  evaluationType: string;
  status: string;
  version: number;
  periodStart: string | null;
  periodEnd: string | null;
  scheduledFor: string | null;
  zoomJoinUrl: string | null;
  technicalSkillsScore: number | null;
  communicationScore: number | null;
  professionalismScore: number | null;
  learningApplicationScore: number | null;
  averageScore: number | null;
  strengths: string | null;
  areasForImprovement: string | null;
  comments: string | null;
  recommendation: string | null;
  publishedAt: string | null;
  internAcknowledgedAt: string | null;
  internResponse: string | null;
  amendedAt: string | null;
}

export interface InternEvaluationRow {
  evaluationId: string;
  evaluatorName: string | null;
  evaluationType: string;
  status: string;
  version: number;
  publishedAt: string | null;
  internAcknowledgedAt: string | null;
  averageScoreInt: number | null;
  recommendation: string | null;
}

export interface ScheduledRow {
  evaluationId: string;
  internLifecycleId: string;
  internName: string | null;
  employeeId: string | null;
  evaluationType: string;
  status: string;
  scheduledFor: string | null;
  durationMinutes: number | null;
  zoomJoinUrl: string | null;
}

export interface AwaitingAckRow {
  evaluationId: string;
  internLifecycleId: string;
  internName: string | null;
  employeeId: string | null;
  evaluationType: string;
  publishedAt: string | null;
  daysPending: number;
}

export interface PendingEvaluationsResponse {
  scheduledAndInProgress: ScheduledRow[];
  awaitingAcknowledgment: AwaitingAckRow[];
}

export const RECOMMENDATIONS = [
  'EXCELLENT',
  'GOOD',
  'SATISFACTORY',
  'NEEDS_IMPROVEMENT',
  'UNSATISFACTORY',
] as const;
export type Recommendation = (typeof RECOMMENDATIONS)[number];

// Phase 4 — Final-only verdict; gated to FINAL evaluations in the Compose UI
// but accepted on the wire universally.
export const RECOMMENDATIONS_FINAL = [
  ...RECOMMENDATIONS,
  'REHIRE_ELIGIBLE',
] as const;
export type RecommendationFinal = (typeof RECOMMENDATIONS_FINAL)[number];

// ── Phase 3 — I-983 workflow ──────────────────────────────────────────

export const I983_TYPES = ['INITIAL_PLAN', 'ANNUAL_REVIEW', 'FINAL_REVIEW'] as const;
export type I983EvaluationType = (typeof I983_TYPES)[number];

export const DSO_SUBMISSION_METHODS = [
  'EMAIL_TO_DSO',
  'PORTAL_UPLOAD',
  'IN_PERSON',
  'MAIL',
] as const;
export type DsoSubmissionMethod = (typeof DSO_SUBMISSION_METHODS)[number];

export interface I983PlanContext {
  planId: string;
  status: string;
  trainingStartDate: string | null;
  trainingEndDate: string | null;
  universityName: string | null;
  trainingGoalsAndObjectives: string | null;
  performanceEvaluationMethod: string | null;
  supervisorName: string | null;
  supervisorEmail: string | null;
}

export interface EvaluatorI983Detail {
  evaluationId: string;
  internLifecycleId: string;
  internUserId: string;
  internName: string | null;
  internEmail: string | null;
  employeeId: string | null;
  evaluationType: string;
  status: string;
  version: number;
  periodStartDate: string | null;
  periodEndDate: string | null;
  trainingObjectivesProgress: string | null;
  trainingSupervisionProvided: string | null;
  trainingEvaluationOutcomes: string | null;
  objectivesAchieved: string | null;
  supervisorAssessment: string | null;
  evaluatorName: string | null;
  publishedAt: string | null;
  acknowledgedAt: string | null;
  studentTypedSignature: string | null;
  internResponse: string | null;
  dsoSubmittedAt: string | null;
  dsoSubmissionMethod: string | null;
  dsoSubmissionNotes: string | null;
  amendedAt: string | null;
  amendmentReason: string | null;
  planContext: I983PlanContext | null;
}

export interface InternI983View {
  evaluationId: string;
  evaluatorName: string | null;
  evaluationType: string;
  status: string;
  version: number;
  periodStartDate: string | null;
  periodEndDate: string | null;
  trainingObjectivesProgress: string | null;
  trainingSupervisionProvided: string | null;
  trainingEvaluationOutcomes: string | null;
  objectivesAchieved: string | null;
  supervisorAssessment: string | null;
  publishedAt: string | null;
  acknowledgedAt: string | null;
  studentTypedSignature: string | null;
  internResponse: string | null;
  dsoSubmittedAt: string | null;
  dsoSubmissionMethod: string | null;
  amendedAt: string | null;
  planContext: I983PlanContext | null;
}

export interface I983ListRow {
  entryKind: 'INTERN_ELIGIBLE' | 'EVALUATION';
  evaluationId: string | null;
  internLifecycleId: string;
  internName: string | null;
  employeeId: string | null;
  evaluationType: string;
  status: string | null;
  scheduledFor: string | null;
  publishedAt: string | null;
  acknowledgedAt: string | null;
  dsoSubmittedAt: string | null;
  trainingStartDate: string | null;
  trainingEndDate: string | null;
  nextDueDate: string | null;
  daysUntilDue: number | null;
  planExists: boolean;
}

export interface I983ListResponse {
  dueSoon: I983ListRow[];
  inProgress: I983ListRow[];
  completed: I983ListRow[];
}

export interface InternI983Row {
  evaluationId: string;
  evaluatorName: string | null;
  evaluationType: string;
  status: string;
  version: number;
  periodStartDate: string | null;
  periodEndDate: string | null;
  publishedAt: string | null;
  acknowledgedAt: string | null;
  dsoSubmittedAt: string | null;
}
