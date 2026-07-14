// ERM Phase 4 — shared types for offer control + new-hire surfaces.

export type OfferStatus =
  | 'DRAFT'
  | 'SENT'
  | 'SIGNED'
  | 'VOIDED'
  | 'EXPIRED'
  | 'DECLINED';

export interface OfferRow {
  offerId: string;
  applicationId: string;
  applicantName: string | null;
  applicantId: string | null;
  applicantEmail: string | null;
  jobTitle: string | null;
  jobType: string | null;
  status: OfferStatus;
  roleTitle: string | null;
  compensationSummary: string | null;
  tentativeStartDate: string | null;
  sentAt: string | null;
  expiresAt: string | null;
  signedAt: string | null;
  voidedAt: string | null;
  voidReasonCode: string | null;
  reminderCount: number;
  legacyEnvelopeId: string | null;
  archived: boolean;
}

export interface OfferListPage {
  items: OfferRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export interface EventLogEntry {
  id: string;
  eventType: string;
  reasonCode: string | null;
  reasonText: string | null;
  payloadJson: string | null;
  actorUserId: string | null;
  actorName: string | null;
  createdAt: string;
}

export interface OfferDetail {
  id: string;
  status: OfferStatus;
  applicationId: string;
  applicantName: string | null;
  applicantEmail: string | null;
  applicantId: string | null;
  jobTitle: string | null;
  jobType: string | null;
  roleTitle: string | null;
  compensationSummary: string | null;
  worksite: string | null;
  expectedHoursPerWeek: number | null;
  tentativeStartDate: string | null;
  sentAt: string | null;
  expiresAt: string | null;
  signedAt: string | null;
  voidedAt: string | null;
  voidReasonCode: string | null;
  voidReasonText: string | null;
  reminderCount: number;
  lastReminderAt: string | null;
  legacyEnvelopeId: string | null;
  signedPdfDocumentId: string | null;
  internalNotes: string | null;
  archivedAt: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  history: EventLogEntry[];
}

export interface ReasonOption {
  code: string;
  label: string;
  requiresFreeText: boolean;
}

export interface ReasonGroup {
  category: string;
  options: ReasonOption[];
}

export interface UserStub {
  userId: string;
  fullName: string | null;
  email: string | null;
  role: string | null;
  currentInternCount: number;
}

export interface NewHireRow {
  internLifecycleId: string;
  internUserId: string;
  internName: string | null;
  internEmail: string | null;
  employeeId: string | null;
  signedAt: string | null;
  tentativeStartDate: string | null;
  trainerName: string | null;
  evaluatorName: string | null;
  managerName: string | null;
  reportingStructureComplete: boolean;
  onboardingAssigned: boolean;
  /** Onboarding-tracker progress per row (null on older clients). */
  stepsCompleted: number | null;
  stepsTotal: number | null;
  nextStepLabel: string | null;
  canActivate: boolean | null;
  /** Current step id from OnboardingTrackerDtos.StepId, null when all done. */
  currentStepId:
    | 'DOCS_ASSIGNED'
    | 'DOCS_VERIFIED'
    | 'TEAM_NOTIFIED'
    | 'MAIL_AND_JOINING'
    | 'ACTIVATE'
    | null;
}

export interface NewHireListPage {
  items: NewHireRow[];
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
}

export interface NewHireDetail {
  internLifecycleId: string;
  internUserId: string;
  internName: string | null;
  internEmail: string | null;
  employeeId: string | null;
  activeStatus: string;
  hiredAt: string | null;
  startedAt: string | null;
  tentativeStartDate: string | null;
  reportingStructureComplete: boolean;
  reportingStructureCompletedAt: string | null;
  reportingStructureCompletedById: string | null;
  trainer: UserStub | null;
  evaluator: UserStub | null;
  manager: UserStub | null;
  erm: UserStub | null;
  signedOffer: {
    offerId: string;
    roleTitle: string | null;
    compensationSummary: string | null;
    worksite: string | null;
    expectedHoursPerWeek: number | null;
    tentativeStartDate: string | null;
    signedAt: string | null;
    signedPdfDocumentId: string | null;
  } | null;
  onboardingAssigned: boolean;
  /** Phase 8.9 — server-authoritative flag. True iff the intern is at
   *  ONBOARDING_ACCEPTED with a signed offer; gates the "Activate now"
   *  ERM override so it can never skip document verification. */
  canActivateNow: boolean;
  /** ERM Pass 2 — committed activation switch (distinct from the offer's
   *  tentativeStartDate). Null until ERM sets it. Once set + ≤ today,
   *  the next scheduled scan flips the intern to ACTIVE_INTERN. */
  joiningDate: string | null;
  /** True when lifecycle = ONBOARDING_ACCEPTED. The new-hire UI uses
   *  this to enable the "Set joining date" control — ERM commits the
   *  date only after docs are accepted. */
  docsAccepted: boolean;
  /** Mail bridge Phase 5 — enum name from the backend. Drives the
   *  "Assign company email" section: PERSONAL+employeeId shows the
   *  button; PENDING_ACTIVATION shows an "awaiting activation" chip;
   *  ACTIVATED shows an "active" chip. Null when the backend doesn't
   *  surface it (legacy/old clients). */
  mailHandoverState: 'PERSONAL' | 'PENDING_ACTIVATION' | 'ACTIVATED' | null;
  /** Mail bridge Phase 5 — archived personal Gmail captured during
   *  the email swap. Null pre-swap. */
  personalEmail: string | null;
}
