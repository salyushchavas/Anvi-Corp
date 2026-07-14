// ERM Phase 5 — Compliance Tracker DTO mirrors.

export type AlertSeverity = 'URGENT' | 'WARN' | 'INFO';

export interface PipelineRow {
  userId: string;
  fullName: string | null;
  applicantId: string | null;
  email: string | null;
  workAuthType: string | null;
  authorizedUntil: string | null;
  daysUntilExpiration: number | null;
  workAuthSeverity: AlertSeverity | null;
  i9Status: string | null;
  i9Section2DueBy: string | null;
  i9DaysUntil: number | null;
  i9Severity: AlertSeverity | null;
  everifyStatus: string | null;
  everifyDueBy: string | null;
  everifyDaysUntil: number | null;
  everifySeverity: AlertSeverity | null;
  i983Required: boolean | null;
}

export interface PipelineKpi {
  workAuthExpiring30: number;
  i9OverdueOrDueSoon: number;
  everifyTncOrOverdue: number;
  i983Required: number;
}

export interface PipelinePage {
  items: PipelineRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  kpi: PipelineKpi;
}

export interface WorkAuthCard {
  recordId: string | null;
  workAuthType: string | null;
  authorizedFrom: string | null;
  authorizedUntil: string | null;
  eadCardNumberMasked: string | null;
  eadExpiration: string | null;
  i20Expiration: string | null;
  i983Required: boolean | null;
  i983Id: string | null;
  dsoName: string | null;
  dsoEmail: string | null;
  dsoPhone: string | null;
  ermNotes: string | null;
  lastUpdatedAt: string | null;
  lastUpdatedById: string | null;
}

export interface I9TimelineCard {
  i9FormId: string | null;
  status: string | null;
  firstDayOfEmployment: string | null;
  section1DueDate: string | null;
  section2DueDate: string | null;
  section2DueByCalculated: string | null;
  section2DaysUntil: number | null;
  section2Severity: AlertSeverity | null;
  section1SignedAt: string | null;
  section2SignedAt: string | null;
  employerName: string | null;
  employerTitle: string | null;
}

export interface EverifyCard {
  caseId: string | null;
  caseNumberMasked: string | null;
  status: string | null;
  dueBy: string | null;
  expectedCloseBy: string | null;
  daysUntilClose: number | null;
  severity: AlertSeverity | null;
  openedAt: string | null;
  closedAt: string | null;
  closureReason: string | null;
  photoMatchRequired: boolean | null;
  photoMatchResult: string | null;
  ermNotes: string | null;
  lastUpdatedAt: string | null;
}

export interface I983Card {
  id: string | null;
  status: string | null;
  periodStartDate: string | null;
  periodEndDate: string | null;
  lastEvaluationAt: string | null;
  daysUntilNext: number | null;
  severity: AlertSeverity | null;
}

export interface TimelineEvent {
  label: string;
  eventDate: string;
  daysUntil: number | null;
  severity: AlertSeverity | null;
}

export interface InternTimeline {
  userId: string;
  fullName: string | null;
  email: string | null;
  workAuth: WorkAuthCard | null;
  i9: I9TimelineCard | null;
  everify: EverifyCard | null;
  i983: I983Card | null;
  upcomingEvents: TimelineEvent[];
}

export interface UpdateWorkAuthRequest {
  workAuthType?: string;
  authorizedFrom?: string;
  authorizedUntil?: string;
  eadCardNumber?: string;
  eadExpiration?: string;
  i20Expiration?: string;
  i983Required?: boolean;
  dsoName?: string;
  dsoEmail?: string;
  dsoPhone?: string;
  ermNotes?: string;
}

export interface RecordI9Section2Request {
  firstDayOfEmployment?: string;
  listATitle?: string;
  listAIssuingAuthority?: string;
  listADocumentNumber?: string;
  listAExpirationDate?: string;
  listBTitle?: string;
  listBIssuingAuthority?: string;
  listBDocumentNumber?: string;
  listBExpirationDate?: string;
  listCTitle?: string;
  listCIssuingAuthority?: string;
  listCDocumentNumber?: string;
  employerName?: string;
  employerTitle?: string;
  businessOrganizationName?: string;
  businessAddress?: string;
}

export interface RecordEverifyRequest {
  i9FormId: string;
  caseNumber?: string;
  status?: string;
  dueBy?: string;
  expectedCloseBy?: string;
  photoMatchRequired?: boolean;
  ermNotes?: string;
}

export interface UpdateEverifyStatusRequest {
  status: string;
  closureReason?: string;
  expectedCloseBy?: string;
  photoMatchResult?: string;
  ermNotes?: string;
}
