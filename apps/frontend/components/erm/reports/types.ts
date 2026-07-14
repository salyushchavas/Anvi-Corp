// ERM Phase 7 — Reports DTO mirrors.

export interface ReportFilters {
  from: string;
  to: string;
  jobType?: string;
  jobId?: string;
  ermOwnerId?: string;
  trainerId?: string;
  evaluatorId?: string;
  managerId?: string;
  scope?: 'mine' | 'all';
}

export interface FunnelStage {
  stage: string;
  count: number;
  conversionFromPrevious: number | null;
  avgDaysFromPrevious: number | null;
}

export interface PipelineFunnelData {
  from: string;
  to: string;
  stages: FunnelStage[];
  uniqueApplicants: number;
}

export interface TimeToHireBucket {
  label: string;
  count: number;
  avgDays: number | null;
  medianDays: number | null;
  p90Days: number | null;
}

export interface TimeToHireData {
  from: string;
  to: string;
  avgDays: number | null;
  medianDays: number | null;
  p90Days: number | null;
  signedCount: number;
  byJobType: TimeToHireBucket[];
  byMonth: TimeToHireBucket[];
}

export interface DecisionSlice {
  decision: string;
  count: number;
  pct: number | null;
}

export interface ReasonCount {
  reasonCode: string;
  humanLabel: string | null;
  count: number;
}

export interface DecisionFunnelData {
  from: string;
  to: string;
  closedTotal: number;
  decisions: DecisionSlice[];
  topReasons: ReasonCount[];
}

export interface CompletionBucket {
  mentorRole: string;
  mentorId: string | null;
  mentorName: string | null;
  activated: number;
  completed: number;
  resigned: number;
  terminated: number;
  inProgress: number;
}

export interface CompletionRateData {
  from: string;
  to: string;
  totalActivated: number;
  totalCompleted: number;
  totalResigned: number;
  totalTerminated: number;
  totalInProgress: number;
  byTrainer: CompletionBucket[];
  byEvaluator: CompletionBucket[];
  byManager: CompletionBucket[];
}

export interface AttritionByType {
  exitType: string;
  count: number;
  pct: number | null;
}

export interface AttritionData {
  from: string;
  to: string;
  totalExited: number;
  byType: AttritionByType[];
  topReasons: ReasonCount[];
}

export interface ScoreBucket { score: number; count: number; }

export interface EvaluatorBucket {
  evaluatorId: string | null;
  evaluatorName: string | null;
  evaluations: number;
  avgScore: number | null;
}

export interface EvaluationDistributionData {
  from: string;
  to: string;
  totalEvaluations: number;
  avgScore: number | null;
  histogram: ScoreBucket[];
  byEvaluator: EvaluatorBucket[];
}

export interface InternTimesheetCompliance {
  internUserId: string | null;
  internName: string | null;
  weeksTracked: number;
  onTimeSubmitted: number;
  approvedFirstTry: number;
  everRejected: number;
  onTimePct: number | null;
  firstTryPct: number | null;
}

export interface TimesheetComplianceData {
  from: string;
  to: string;
  totalWeeks: number;
  aggregateOnTimePct: number | null;
  aggregateFirstTryPct: number | null;
  perIntern: InternTimesheetCompliance[];
}

export type ReportType =
  | 'pipeline-funnel'
  | 'time-to-hire'
  | 'decision-funnel'
  | 'completion-rate'
  | 'attrition'
  | 'evaluation-distribution'
  | 'timesheet-compliance';
