// ERM Phase 6 — active intern monitor DTO mirrors.

export type CardState = 'OK' | 'WARN' | 'URGENT';

export interface MonitorState {
  state: CardState;
  label: string;
  detail: string | null;
}

export interface ActiveInternRow {
  internLifecycleId: string;
  internUserId: string;
  internName: string | null;
  employeeId: string | null;
  trainerId: string | null;
  trainerName: string | null;
  evaluatorId: string | null;
  evaluatorName: string | null;
  managerId: string | null;
  managerName: string | null;
  ermId: string | null;
  ermName: string | null;
  startedAt: string | null;
  daysActive: number;
  project: MonitorState;
  trainerMeeting: MonitorState;
  evaluation: MonitorState;
  timesheet: MonitorState;
  compliance: MonitorState;
  escalations: MonitorState;
  openExceptionCount: number;
  urgentExceptionCount: number;
}

export interface ActiveInternListPage {
  items: ActiveInternRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export interface ProjectSummary {
  id: string;
  title: string | null;
  status: string;
  assignmentDate: string | null;
  dueDate: string | null;
  submittedAt: string | null;
  assignedById: string | null;
}

export interface MeetingSummary {
  id: string;
  topic: string | null;
  status: string;
  scheduledFor: string | null;
  hostUserId: string | null;
}

export interface EvaluationSummary {
  id: string;
  evaluationType: string;
  status: string;
  publishedAt: string | null;
  scheduledFor: string | null;
  evaluatorId: string | null;
}

export interface TimesheetSummary {
  id: string;
  weekStart: string | null;
  status: string;
  hours: string;
  approvedAt: string | null;
}

export interface ComplianceAlertSummary {
  label: string;
  date: string | null;
  severity: string;
}

export interface EscalationSummary {
  id: string;
  exceptionType: string;
  severity: string;
  status: string;
  openedAt: string | null;
  ageDays: number;
}

export interface ProjectCard {
  state: MonitorState;
  projects: ProjectSummary[];
  assignNewCta: boolean;
}

export interface TrainerMeetingCard {
  state: MonitorState;
  upcoming: MeetingSummary[];
  past: MeetingSummary[];
  scheduleNewCta: boolean;
}

export interface EvaluationCard {
  state: MonitorState;
  evaluations: EvaluationSummary[];
  scheduleNewCta: boolean;
}

export interface TimesheetCard {
  state: MonitorState;
  currentWeekStatus: string;
  currentWeekStart: string | null;
  lastFourWeeks: TimesheetSummary[];
  totalApprovedHours: string;
}

export interface ComplianceCard {
  state: MonitorState;
  workAuthType: string | null;
  workAuthExpiresOn: string | null;
  i9Status: string | null;
  everifyStatus: string | null;
  i983Status: string | null;
  alerts: ComplianceAlertSummary[];
}

export interface EscalationsCard {
  state: MonitorState;
  openExceptions: EscalationSummary[];
  pastExceptions: EscalationSummary[];
}

export interface InternProfile {
  userId: string;
  fullName: string | null;
  email: string | null;
  employeeId: string | null;
  workAuthType: string | null;
  signedRoleTitle: string | null;
  compensationSummary: string | null;
  signedAt: string | null;
  startedAt: string | null;
  hiredAt: string | null;
}

export interface ActivityEntry {
  at: string | null;
  entityType: string | null;
  entityId: string | null;
  action: string | null;
  actorUserId: string | null;
  actorName: string | null;
}

export interface InternMonitorView {
  internLifecycleId: string;
  intern: InternProfile;
  summary: ActiveInternRow;
  project: ProjectCard;
  trainerMeeting: TrainerMeetingCard;
  evaluation: EvaluationCard;
  timesheet: TimesheetCard;
  compliance: ComplianceCard;
  escalations: EscalationsCard;
  recentActivity: ActivityEntry[];
}
