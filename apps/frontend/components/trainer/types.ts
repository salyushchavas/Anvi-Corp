// Trainer Phase 1 — DTO mirrors for Home dashboard + Active Interns
// + right-side panel responses.

export type TrainerKpiKey =
  | 'ACTIVE_INTERNS'
  | 'PROJECTS_DUE_THIS_WEEK'
  | 'SUBMISSIONS_PENDING_REVIEW'
  | 'MEETINGS_DUE'
  | 'OVERDUE_FEEDBACK'
  | 'REVISION_REQUESTS';

export interface KpiSnapshot {
  key: TrainerKpiKey;
  label: string;
  count: number;
  urgentCount: number;
  helperText: string | null;
  actionUrl: string;
}

export interface TodayMeetingRow {
  meetingId: string;
  internLifecycleId: string;
  internName: string | null;
  scheduledFor: string;
  durationMinutes: number | null;
  topic: string | null;
  zoomStartUrl: string | null;
  zoomJoinUrl: string | null;
}

export interface RecentActivityRow {
  at: string | null;
  entityType: string | null;
  entityId: string | null;
  action: string | null;
  actorUserId: string | null;
  actorName: string | null;
  subjectUserId: string | null;
  subjectName: string | null;
  deepLink: string | null;
}

export interface FocusItem {
  key: string;
  label: string;
  count: number;
  actionUrl: string;
}

export interface TrainerDashboardResponse {
  caller: { firstName: string; lastName: string; role: string };
  asOf: string;
  kpis: Partial<Record<TrainerKpiKey, KpiSnapshot>>;
  /** "This week" focus strip — ≤5 actionable items. Zero-count items
   *  are returned and hidden client-side; all-zero renders "all caught up". */
  focusItems: FocusItem[];
  todayMeetings: TodayMeetingRow[];
  recentActivity: RecentActivityRow[];
  unreadNotifications: number;
}

// ── Active Interns ────────────────────────────────────────────────────

/** Roster-wide month summary returned alongside the row page. */
export interface MonthRosterSummary {
  totalActive: number;
  projectsUnassigned: number;
  ktNotDone: number;
  timesheetsIncomplete: number;
  evaluationsOverdue: number;
  attentionNeeded: number;
  /** Phase C — interns with no manager assigned. Only meaningful on
   *  the ERM roster (Manager roster is filtered to manager_id == caller). */
  noManager: number;
}

export type ProjectSlotState =
  | 'NOT_ASSIGNED'
  | 'ASSIGNED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'OVERDUE';

export type ProjectsOverallState =
  | 'NO_PROJECTS'
  | 'PARTIAL'
  | 'BOTH_ASSIGNED'
  | 'OVERDUE'
  | 'COMPLETE';

export type MeetingDocState =
  | 'SCHEDULED'
  | 'COMPLETED'
  | 'MISSED'
  | 'RESCHEDULED'
  | 'NONE';

export type EvaluationState =
  | 'SCHEDULED'
  | 'COMPLETED'
  | 'OVERDUE'
  | 'NONE';

export type TimesheetState =
  | 'SUBMITTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'MISSING'
  | 'DRAFT';

export interface ProjectSlot {
  id: string | null;
  title: string | null;
  status: string | null;
  dueDate: string | null;
  state: ProjectSlotState;
  /** Phase 0 KT — NOT_DONE | DONE. */
  ktStatus: 'NOT_DONE' | 'DONE' | null;
  ktCompletedAt: string | null;
}

export interface CurrentMonthProjectsBlock {
  monthYear: string;
  project1: ProjectSlot | null;
  project2: ProjectSlot | null;
  overallState: ProjectsOverallState;
}

export interface MeetingStateBlock {
  lastMeetingAt: string | null;
  lastMeetingStatus: string | null;
  nextMeetingAt: string | null;
  state: MeetingDocState;
}

export interface EvaluationStateBlock {
  lastPublishedAt: string | null;
  lastEvaluationType: string | null;
  nextScheduledAt: string | null;
  state: EvaluationState;
}

export interface TimesheetStateBlock {
  currentWeekStart: string | null;
  currentWeekStatus: string | null;
  lastApprovedAt: string | null;
  state: TimesheetState;
  /** Phase B2 — per-status counts for the requested month. */
  submittedCount: number;
  verifiedCount: number;
  approvedCount: number;
  rejectedCount: number;
  missingCount: number;
  expectedWeeks: number;
}

export interface ReportingStructure {
  trainerName: string | null;
  evaluatorName: string | null;
  managerName: string | null;
  ermName: string | null;
  /** Phase C — IDs alongside names so the ERM roster can detect
   *  "no manager" and link the AssignManager flow. */
  managerId: string | null;
  trainerId: string | null;
  evaluatorId: string | null;
  ermId: string | null;
}

export interface ActiveInternRow {
  internLifecycleId: string;
  employeeId: string | null;
  fullName: string | null;
  email: string | null;
  phone: string | null;
  technologyTitle: string | null;
  startDate: string | null;
  daysActive: number;
  currentMonthProjects: CurrentMonthProjectsBlock;
  weeklyMeeting: MeetingStateBlock;
  evaluation: EvaluationStateBlock;
  timesheet: TimesheetStateBlock;
  reportingStructure: ReportingStructure;
}

export interface ActiveInternListPage {
  items: ActiveInternRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  monthYear: string;
  summary: MonthRosterSummary;
}

export interface InternProfile {
  userId: string | null;
  fullName: string | null;
  email: string | null;
  phone: string | null;
  employeeId: string | null;
  technologyTitle: string | null;
  startDate: string | null;
}

export interface SignedOfferSummary {
  roleTitle: string | null;
  compensationSummary: string;
  tentativeStartDate: string | null;
  signedAt: string | null;
}

export interface RecentProjectRow {
  id: string;
  title: string | null;
  status: string;
  projectNumber: number | null;
  monthYear: string | null;
  dueDate: string | null;
  reviewedAt: string | null;
  /** KT (Knowledge Transfer) — Trainer-marked, per monthly project. */
  ktStatus: 'NOT_DONE' | 'DONE' | null;
  ktCompletedAt: string | null;
  ktMeetingLink: string | null;
  /** Live KT Zoom session — populated when the trainer scheduled one.
   *  Null when no session is on the calendar. The session and the
   *  ktStatus=DONE mark are INDEPENDENT — trainer can mark done without
   *  ever scheduling, or schedule but not mark done. */
  ktZoomMeetingId?: string | null;
  ktZoomJoinUrl?: string | null;
  ktZoomStartUrl?: string | null;
  ktScheduledFor?: string | null;
  ktDurationMinutes?: number | null;
  ktTimezone?: string | null;
}

export interface RecentMeetingRow {
  id: string;
  scheduledFor: string | null;
  status: string;
  topic: string | null;
  notesExcerpt: string | null;
}

export interface RecentSubmissionRow {
  id: string;
  projectId: string | null;
  projectTitle: string | null;
  submittedAt: string | null;
  technicalScore: number | null;
  communicationScore: number | null;
  nextAction: string | null;
}

export interface RecentTimesheetRow {
  id: string;
  weekStart: string | null;
  status: string;
  hours: string;
}

export interface ActivityEntry {
  at: string | null;
  entityType: string | null;
  entityId: string | null;
  action: string | null;
  actorUserId: string | null;
  actorName: string | null;
}

export interface ActiveInternDetail {
  internLifecycleId: string;
  intern: InternProfile;
  summary: ActiveInternRow;
  signedOffer: SignedOfferSummary;
  recentProjects: RecentProjectRow[];
  recentMeetings: RecentMeetingRow[];
  recentSubmissions: RecentSubmissionRow[];
  recentTimesheets: RecentTimesheetRow[];
  recentActivity: ActivityEntry[];
  i983Required: boolean | null;
  i983StatusBadge: string | null;
}

// ── Right panel ───────────────────────────────────────────────────────

export interface QuickAction {
  key: string;
  label: string;
  href: string;
  badge: number;
  comingSoon: boolean;
}

export interface Alert {
  key: string;
  label: string;
  count: number;
  severity: 'INFO' | 'WARN' | 'URGENT';
}

export interface TrainerRightPanelResponse {
  quickActions: QuickAction[];
  alerts: Alert[];
  unreadNotifications: number;
  todayMeetingsCount: number;
}
