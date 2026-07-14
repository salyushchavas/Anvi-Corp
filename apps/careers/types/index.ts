// Shared frontend types — populated as the API surface grows.

export type Uuid = string;

export type IsoDateTime = string;

export type ApiError = {
  message: string;
  status: number;
  details?: unknown;
};

// Six-role taxonomy. Mirrors backend UserRole.java.
//   INTERN            — applies, runs projects, submits timesheets.
//   TRAINER           — assigns projects, conducts sessions, first sign-off.
//   REPORTING_MANAGER — post-trainer review, approves timesheets.
//   MANAGER           — monitors ERM and the application pipeline.
//   ERM               — shortlisting + onboarding + pre-hire ops.
//   SUPER_ADMIN       — owner / god-mode oversight.
export type UserRole =
  | 'INTERN'
  | 'TRAINER'
  | 'EVALUATOR'
  | 'REPORTING_MANAGER'
  | 'MANAGER'
  | 'ERM'
  | 'SUPER_ADMIN';

// Phase 1.4 — candidate's neutral self-attestation on expected work-auth
// track. NO documents are collected at this stage; this is candidate self-
// reporting only and drives downstream compliance routing (Phase 3).
export type WorkAuthTrack = 'CITIZEN' | 'CPT' | 'OPT' | 'STEM_OPT' | 'OTHER';

/**
 * Mirrors backend DegreeLevel enum. Phase 1.5 expanded the original
 * 3-value set (BACHELORS / MASTERS / DOCTORATE) with HIGH_SCHOOL,
 * ASSOCIATE, DIPLOMA, MBA, and OTHER. {@code DOCTORATE} is intentionally
 * kept (rather than renamed PHD) because existing I983Plan rows
 * already persist that value.
 */
export type DegreeLevel =
  | 'HIGH_SCHOOL'
  | 'ASSOCIATE'
  | 'DIPLOMA'
  | 'BACHELORS'
  | 'MASTERS'
  | 'MBA'
  | 'DOCTORATE'
  | 'OTHER';

export const DEGREE_LEVEL_LABEL: Record<DegreeLevel, string> = {
  HIGH_SCHOOL: 'High School',
  ASSOCIATE: 'Associate',
  DIPLOMA: 'Diploma',
  BACHELORS: 'Bachelor’s (UG)',
  MASTERS: 'Master’s (PG / MS)',
  MBA: 'MBA',
  DOCTORATE: 'PhD / Doctorate',
  OTHER: 'Other',
};

export interface User {
  userId: string;
  email: string;
  fullName: string;
  phoneNumber?: string;
  roles: UserRole[];
  createdAt?: string;
  // Phase 1.2: account-journey state lives on the User.
  emailVerified?: boolean;
  applicantId?: string;
  /**
   * Phase 3 step 6: candidate's expected work-auth track. Populated from
   * the Candidate row via {@code /auth/me}; staff accounts will be undefined.
   * Used to gate STEM-OPT-only UI (I-983 Training Plan tile, page).
   */
  expectedTrack?: WorkAuthTrack;
  /**
   * TRUE for staff accounts created by SUPER_ADMIN with a temp password.
   * When true, the AuthProvider redirects to /careers/force-change-password
   * and the backend's ForcePasswordChangeFilter blocks every other API call
   * until the user changes their password.
   */
  mustChangePassword?: boolean;
}

export interface AuthResponse {
  token: string;
  /** Refresh token (raw, only returned at issuance — stored client-side). */
  refreshToken?: string;
  /** Lifetime of the access token in seconds. */
  accessTokenExpiresInSeconds?: number;
  userId: string;
  email: string;
  fullName: string;
  roles: UserRole[];
  emailVerified?: boolean;
  applicantId?: string;
  mustChangePassword?: boolean;
}

// Session management — /api/v1/me/sessions
export interface UserSessionResponse {
  id: string;
  createdAt: string;
  lastUsedAt: string;
  userAgent?: string;
  deviceLabel: string;
  ip?: string;
  current: boolean;
}

export interface VerifyEmailResponse {
  emailVerified: boolean;
  applicantId?: string;
  message?: string;
}

// === Job postings ============================================================

export type EmploymentType = 'INTERNSHIP' | 'CONTRACT' | 'FULL_TIME';

export type JobPostingStatus = 'DRAFT' | 'OPEN' | 'PAUSED' | 'CLOSED';

export interface JobPostingResponse {
  id: Uuid;
  slug: string;
  /** Skyzen Job ID — format `SKZ-JOB-YYYY-NNNNNN`. Immutable, unique
   *  across all postings. May be undefined on legacy rows briefly until
   *  the BACKFILL_JOB_ID_V1 migration runs. */
  jobId?: string;
  title: string;
  description: string;
  requirements?: string;
  location: string;
  employmentType: EmploymentType;
  status: JobPostingStatus;
  entityName?: string;
  publishedAt?: IsoDateTime;
  createdAt: IsoDateTime;
  /**
   * Populated by the backend only when the caller is an authenticated
   * CANDIDATE. Public / staff callers always see {@code applied = false}
   * (or the field absent) and undefined application fields.
   */
  applied?: boolean;
  applicationId?: Uuid;
  applicationStatus?: ApplicationStatus | string;
  /**
   * Populated by the admin postings list endpoint. Defaults to 0 elsewhere
   * so non-admin views can ignore it.
   */
  applicantCount?: number;
  /** Owning StaffingEntity id; populated wherever {@code entityName} is. */
  entityId?: Uuid;
}

// Paged envelope returned by every list endpoint. The backend returns a custom
// PagedResponse<T> instead of Spring's PageImpl (deprecated for serialization
// in Spring Boot 3 — see backend/.../dto/common/PagedResponse.java). Field
// shape mirrors PagedAuditLogResponse from D2 for cross-endpoint consistency.
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// === Applications ============================================================

export type ApplicationStatus =
  | 'APPLIED'
  // ERM Phase 2 — decision-flow holding states (Applied band).
  | 'HOLD'
  | 'INFO_REQUESTED'
  // Phase 2.1 — lightweight web screening sits between Applied and Shortlisted.
  | 'SCREENING_SENT'
  | 'SCREENING_COMPLETED'
  | 'SHORTLISTED'
  | 'INTERVIEW_SCHEDULED'
  | 'INTERVIEWED'
  // Phase 2.3 — conditional employment confirmation sent; formal offer pending.
  | 'SELECTED_CONDITIONAL'
  | 'OFFERED'
  | 'ACCEPTED'
  | 'ONBOARDING'
  | 'ACTIVE'
  | 'HIRED'
  | 'COMPLETED'
  | 'REJECTED'
  | 'WITHDRAWN'
  | 'LAPSED'
  | 'NO_SHOW';

// === Screening (phase 2.1) ===================================================

export type ScreeningStatus = 'SENT' | 'COMPLETED';
export type ScreeningQuestionType = 'SINGLE_CHOICE' | 'FREE_TEXT';

export interface ScreeningSummaryResponse {
  id: Uuid;
  applicationId: Uuid;
  status: ScreeningStatus;
  sentAt: IsoDateTime;
  completedAt?: IsoDateTime;
  score?: number;
  maxScore?: number;
}

export interface ScreeningCandidateResponse {
  id: Uuid;
  status: ScreeningStatus;
  sentAt: IsoDateTime;
  completedAt?: IsoDateTime;
  jobPostingTitle?: string;
  entityName?: string;
  questions: Array<{
    id: Uuid;
    orderIndex: number;
    type: ScreeningQuestionType;
    prompt: string;
    /** Present for SINGLE_CHOICE only; absent for FREE_TEXT. */
    choices?: string[];
  }>;
}

export interface ScreeningStaffResponse {
  id: Uuid;
  applicationId: Uuid;
  status: ScreeningStatus;
  sentAt: IsoDateTime;
  completedAt?: IsoDateTime;
  score?: number;
  maxScore?: number;
  candidateName?: string;
  candidateEmail?: string;
  jobPostingTitle?: string;
  entityName?: string;
  answers: Array<{
    questionId: Uuid;
    orderIndex: number;
    type: ScreeningQuestionType;
    prompt: string;
    choices?: string[];
    correctChoiceIndex?: number;
    choiceIndex?: number;
    freeText?: string;
    points?: number;
    awardedPoints?: number;
  }>;
}

export interface ScreeningSubmitRequest {
  answers: Array<{
    questionId: Uuid;
    choiceIndex?: number;
    freeText?: string;
  }>;
}

export interface ApplicationResponse {
  id: Uuid;
  candidateName?: string;
  candidateEmail?: string;
  jobPostingTitle?: string;
  jobPostingId: Uuid;
  resumeId?: Uuid;
  resumeFileName?: string;
  status: ApplicationStatus;
  appliedAt: IsoDateTime;
  statusUpdatedAt?: IsoDateTime;
  recruiterNotes?: string;
  /** Recruiter's 1-5 rating from the review screen (nullable). */
  recruiterRating?: number;
  /** Phase 2 — applicant-typed motivation captured at apply time. */
  statementOfInterest?: string;
  /** Phase 2 — applicant-safe outcome message; populated by ERM. */
  applicantVisibleFeedback?: string;
  /** ERM Phase 2 — CSV of field keys the intern must provide when stage is INFO_REQUESTED. */
  infoRequestedFieldsCsv?: string;
}


export interface RecruiterDecisionRequest {
  rating?: number;
  note?: string;
}

// === Resumes =================================================================

export interface ResumeResponse {
  id: Uuid;
  fileName: string;
  fileSize: number;
  contentType: string;
  isDefault: boolean;
  createdAt: IsoDateTime;
}

// === Interviews ==============================================================

export type InterviewType =
  | 'INITIAL_SCREEN'
  | 'TECHNICAL'
  | 'BEHAVIORAL'
  | 'CULTURE_FIT'
  | 'FINAL_ROUND';

export type InterviewStatus = 'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';

export type InterviewRecommendation =
  | 'STRONG_HIRE'
  | 'HIRE'
  | 'NO_HIRE'
  | 'STRONG_NO_HIRE';

export interface InterviewResponse {
  id: Uuid;
  applicationId: Uuid;
  applicationStatus?: ApplicationStatus;
  candidateName?: string;
  candidateEmail?: string;
  jobPostingTitle?: string;
  interviewerName?: string;
  interviewerId?: Uuid;
  scheduledAt: IsoDateTime;
  durationMinutes: number;
  type: InterviewType;
  status: InterviewStatus;
  meetingUrl?: string;
  candidateNotes?: string;
  feedbackOverallRating?: number;
  feedbackTechnicalRating?: number;
  feedbackCommunicationRating?: number;
  /** Phase 2.2 scorecard dimension; null for legacy /feedback submissions. */
  feedbackProblemSolvingRating?: number;
  feedbackStrengths?: string;
  feedbackConcerns?: string;
  /** Phase 2.2 unified scorecard comments; legacy rows use strengths/concerns. */
  feedbackComments?: string;
  feedbackRecommendation?: InterviewRecommendation;
  feedbackSubmittedAt?: IsoDateTime;
  feedbackSubmittedByName?: string;
  createdAt: IsoDateTime;
  createdByName?: string;
}

/** Phase 2.2 — slim staff-only view for the recruiter review screen. */
export interface InterviewScorecardSummary {
  interviewId: Uuid;
  applicationId: Uuid;
  technicalRating?: number;
  communicationRating?: number;
  problemSolvingRating?: number;
  overallRating?: number;
  recommendation?: InterviewRecommendation;
  comments?: string;
  submittedByName?: string;
  submittedAt?: IsoDateTime;
  scheduledAt?: IsoDateTime;
}

export interface SubmitScorecardRequest {
  technicalRating: number;
  communicationRating: number;
  problemSolvingRating: number;
  recommendation: InterviewRecommendation;
  comments?: string;
}

export interface InterviewSummaryResponse {
  id: Uuid;
  candidateName?: string;
  jobPostingTitle?: string;
  interviewerName?: string;
  scheduledAt: IsoDateTime;
  durationMinutes: number;
  type: InterviewType;
  status: InterviewStatus;
  hasFeedback: boolean;
}

export type InterviewDecision = 'SELECTED' | 'HOLD' | 'REJECTED';

export interface CandidateInterviewResponse {
  id: Uuid;
  applicationId?: Uuid;
  jobPostingTitle?: string;
  scheduledAt: IsoDateTime;
  durationMinutes: number;
  timezone?: string;
  type: InterviewType;
  status: InterviewStatus;
  /** Applicant-safe meeting URL (zoom_join_url or legacy meeting_url). */
  meetingUrl?: string;
  zoomJoinUrl?: string;
  zoomPassword?: string;
  candidateNotes?: string;
  prepInstructions?: string;
  interviewerName?: string;
  /** SELECTED | HOLD | REJECTED — null until ERM completes the interview. */
  decision?: InterviewDecision;
  /** Applicant-safe outcome message. */
  applicantVisibleNotes?: string;
}

export interface ScheduleInterviewRequest {
  applicationId: Uuid;
  interviewerId: Uuid;
  scheduledAt: IsoDateTime;
  durationMinutes: number;
  type: InterviewType;
  meetingUrl?: string;
  candidateNotes?: string;
}

export interface SubmitFeedbackRequest {
  overallRating: number;
  technicalRating?: number;
  communicationRating?: number;
  strengths?: string;
  concerns?: string;
  recommendation: InterviewRecommendation;
}

// === Offers ==================================================================

export type CompensationFrequency = 'HOURLY' | 'MONTHLY' | 'YEARLY';

export type OfferStatus =
  | 'DRAFT'
  | 'SENT'
  | 'SIGNED'
  | 'VOIDED'
  | 'EXPIRED'
  | 'DECLINED'
  // Pre-IDMS legacy values; retained so existing rows still deserialize.
  | 'ACCEPTED'
  | 'REVOKED';

export interface OfferResponse {
  id: Uuid;
  applicationId: Uuid;
  candidateName?: string;
  candidateEmail?: string;
  candidateId?: Uuid;
  jobPostingTitle?: string;
  jobPostingId?: Uuid;
  entityName?: string;
  entityId?: Uuid;
  compensationAmount: number | string;
  compensationFrequency: CompensationFrequency;
  compensationCurrency: string;
  startDate: string;
  expectedEndDate?: string;
  expiresAt: IsoDateTime;
  status: OfferStatus;
  additionalTerms?: string;
  letterContent: string;
  declineReason?: string;
  sentAt?: IsoDateTime;
  respondedAt?: IsoDateTime;
  revokedAt?: IsoDateTime;
  createdAt: IsoDateTime;
  createdByName?: string;
  updatedAt: IsoDateTime;
  /** Server-side computed. Jackson serializes the boolean field as "expired". */
  expired: boolean;
}

export interface OfferSummaryResponse {
  id: Uuid;
  candidateName?: string;
  jobPostingTitle?: string;
  entityName?: string;
  compensationAmount: number | string;
  compensationFrequency: CompensationFrequency;
  startDate: string;
  expiresAt: IsoDateTime;
  status: OfferStatus;
  createdAt: IsoDateTime;
}

export interface CandidateOfferResponse {
  id: Uuid;
  jobPostingTitle?: string;
  entityName?: string;
  compensationAmount: number | string;
  compensationFrequency: CompensationFrequency;
  compensationCurrency: string;
  startDate: string;
  expectedEndDate?: string;
  expiresAt: IsoDateTime;
  status: OfferStatus;
  additionalTerms?: string;
  letterContent: string;
  sentAt?: IsoDateTime;
  respondedAt?: IsoDateTime;
  expired: boolean;
  // Phase 3 doc-spec fields (applicant-safe; voidedReason is null unless
  // status=VOIDED and the caller owns the offer).
  roleTitle?: string;
  compensationSummary?: string;
  worksite?: string;
  expectedHoursPerWeek?: number;
  legacyEnvelopeId?: string;
  signedAt?: IsoDateTime;
  voidedAt?: IsoDateTime;
  voidedReason?: string;
  signedPdfDocumentId?: Uuid;
  employeeId?: string;
  createdByName?: string;
  createdByEmail?: string;
}

export interface CreateOfferRequest {
  applicationId: Uuid;
  compensationAmount: number;
  compensationFrequency: CompensationFrequency;
  compensationCurrency?: string;
  startDate: string;
  expectedEndDate?: string;
  daysToRespond: number;
  additionalTerms?: string;
}

export interface UpdateOfferRequest {
  compensationAmount?: number;
  compensationFrequency?: CompensationFrequency;
  compensationCurrency?: string;
  startDate?: string;
  expectedEndDate?: string;
  daysToRespond?: number;
  additionalTerms?: string;
  letterContent?: string;
}

export interface DeclineOfferRequest {
  reason?: string;
}

// === Onboarding ==============================================================

export type OnboardingCategory =
  | 'PAPERWORK'
  | 'COMPLIANCE'
  | 'SETUP'
  | 'INTRODUCTION';

export type OnboardingTaskStatus =
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'BLOCKED'
  | 'NOT_APPLICABLE';

export interface OnboardingTaskResponse {
  id: Uuid;
  taskKey: string;
  title: string;
  description?: string;
  category: OnboardingCategory;
  status: OnboardingTaskStatus;
  sortOrder: number;
  /** LocalDate ISO string, e.g. "2026-05-24" (no time component). */
  dueDate?: string;
  linkUrl?: string;
  completedAt?: IsoDateTime;
  completedByName?: string;
  offerId?: Uuid;
  applicationId?: Uuid;
  /** Jackson serializes the boolean isOverdue() getter as "overdue". */
  overdue: boolean;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
}

export interface OnboardingSummaryResponse {
  totalTasks: number;
  completedTasks: number;
  pendingTasks: number;
  inProgressTasks: number;
  blockedTasks: number;
  progressPercent: number;
  nextDueTask?: OnboardingTaskResponse | null;
}

export interface UpdateTaskStatusRequest {
  status: OnboardingTaskStatus;
}

// === Form I-9 ================================================================

export type I9Status =
  | 'NOT_STARTED'
  // Phase 3 step 5 — explicit "Section 1 done, Section 2 due" phase.
  | 'SECTION_2_PENDING'
  /** @deprecated legacy alias for SECTION_2_PENDING; existing rows still load. */
  | 'SECTION_1_COMPLETE'
  | 'COMPLETED'
  | 'REOPENED';

export type CitizenshipStatus =
  | 'US_CITIZEN'
  | 'NONCITIZEN_NATIONAL'
  | 'LAWFUL_PERMANENT_RESIDENT'
  | 'ALIEN_AUTHORIZED_TO_WORK';

export interface I9FormResponse {
  // Identity
  id: Uuid;
  candidateId?: Uuid;
  candidateName?: string;
  candidateEmail?: string;
  status: I9Status;

  // Section 1
  lastName?: string;
  firstName?: string;
  middleInitial?: string;
  otherLastNamesUsed?: string;
  addressStreet?: string;
  addressAptNumber?: string;
  addressCity?: string;
  addressState?: string;
  addressZipCode?: string;
  /** LocalDate ISO string, e.g. "2002-05-23". */
  dateOfBirth?: string;
  ssn?: string;
  email?: string;
  phoneNumber?: string;
  citizenshipStatus?: CitizenshipStatus;
  alienRegistrationNumber?: string;
  foreignPassportNumber?: string;
  foreignPassportCountry?: string;
  workAuthExpirationDate?: string;
  preparerTranslatorUsed?: boolean;
  section1SignedAt?: IsoDateTime;
  section1SignedByName?: string;

  // Section 2
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
  additionalInformation?: string;
  employerName?: string;
  employerTitle?: string;
  businessOrganizationName?: string;
  businessAddress?: string;
  section2SignedAt?: IsoDateTime;
  section2SignedByName?: string;

  // Computed — Phase 3 step 5 split.
  section1DueDate?: string;
  section2DueDate?: string;
  section1Overdue?: boolean;
  section2Overdue?: boolean;
  /** @deprecated alias for section2Overdue; new code should read section2Overdue. */
  overdue: boolean;
  daysUntilDue?: number;

  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
}

export interface I9SummaryResponse {
  id: Uuid;
  candidateId?: Uuid;
  candidateName?: string;
  candidateEmail?: string;
  jobPostingTitle?: string;
  status: I9Status;
  firstDayOfEmployment?: string;
  section1DueDate?: string;
  section2DueDate?: string;
  section1Overdue?: boolean;
  section2Overdue?: boolean;
  /** @deprecated alias for section2Overdue. */
  overdue: boolean;
  daysUntilDue?: number;
}

/**
 * Section 1 wire payload.
 *
 * Note: the backend DTO field is named `draft` (renamed from `isDraft` to dodge
 * a Lombok/Jackson naming collision — see B1 backend doc). So the JSON wire
 * name is "draft", not "isDraft".
 */
export interface Section1Request {
  lastName?: string;
  firstName?: string;
  middleInitial?: string;
  otherLastNamesUsed?: string;
  addressStreet?: string;
  addressAptNumber?: string;
  addressCity?: string;
  addressState?: string;
  addressZipCode?: string;
  dateOfBirth?: string;
  ssn?: string;
  email?: string;
  phoneNumber?: string;
  citizenshipStatus?: CitizenshipStatus;
  alienRegistrationNumber?: string;
  foreignPassportNumber?: string;
  foreignPassportCountry?: string;
  workAuthExpirationDate?: string;
  preparerTranslatorUsed?: boolean;
  draft: boolean;
}

/** Section 2 wire payload (same `draft` convention as Section 1). */
export interface Section2Request {
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
  additionalInformation?: string;
  employerName?: string;
  employerTitle?: string;
  businessOrganizationName?: string;
  businessAddress?: string;
  draft: boolean;
}

export interface I9HistoryEntryResponse {
  auditId: Uuid;
  timestamp: IsoDateTime;
  action: string;
  performedByName?: string;
  performedByRole?: string;
  summary: string;
}

export interface ReopenI9Request {
  reason: string;
}

// === E-Verify ================================================================

export type EVerifyStatus =
  | 'PENDING_SUBMISSION'
  | 'OPEN'
  | 'EMPLOYMENT_AUTHORIZED'
  | 'TENTATIVE_NONCONFIRMATION'
  | 'FINAL_NONCONFIRMATION'
  | 'CLOSED';

/**
 * Phase 3 step 7 — coarse phase derived from {@link EVerifyStatus} for UI.
 * The rich enum remains the source of truth in workflows + audits.
 */
export type EVerifyPhase =
  | 'CREATED'
  | 'AUTHORIZED'
  | 'IN_REVIEW'
  | 'NOT_AUTHORIZED'
  | 'CLOSED';

export type EVerifyClosureReason =
  | 'SUCCESSFUL'
  | 'EMPLOYEE_TERMINATED'
  | 'INVALID_QUERY'
  | 'OTHER';

export type PhotoMatchResult = 'MATCH' | 'NO_MATCH' | 'NOT_APPLICABLE';

export interface EVerifyCaseResponse {
  id: Uuid;
  i9FormId: Uuid;
  candidateName?: string;
  candidateEmail?: string;
  candidateId?: Uuid;
  caseNumber?: string;
  status: EVerifyStatus;
  closureReason?: EVerifyClosureReason;
  openedAt?: IsoDateTime;
  closedAt?: IsoDateTime;
  photoMatchRequired?: boolean;
  photoMatchResult?: PhotoMatchResult;
  additionalVerificationRequired?: boolean;
  notes?: string;
  daysOpen?: number;
  // Phase 3 step 7 — federal deadline + UI-friendly coarse phase.
  /** LocalDate ISO ("yyyy-mm-dd"); null when no start date is known. */
  dueBy?: string;
  phase?: EVerifyPhase;
  /** True when dueBy is in the past AND phase !== AUTHORIZED. */
  overdue?: boolean;
  lastSyncedAt?: IsoDateTime;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
  createdByName?: string;
}

export interface EVerifyCaseSummaryResponse {
  id: Uuid;
  i9FormId: Uuid;
  candidateName?: string;
  candidateEmail?: string;
  caseNumber?: string;
  status: EVerifyStatus;
  openedAt?: IsoDateTime;
  closedAt?: IsoDateTime;
  daysOpen?: number;
  // Phase 3 step 7 — federal deadline + UI-friendly coarse phase.
  dueBy?: string;
  phase?: EVerifyPhase;
  overdue?: boolean;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
}

export interface CreateEVerifyCaseRequest {
  i9FormId: Uuid;
}

export interface UpdateEVerifyCaseRequest {
  caseNumber?: string;
  photoMatchRequired?: boolean;
  photoMatchResult?: PhotoMatchResult;
  additionalVerificationRequired?: boolean;
  notes?: string;
}

export interface UpdateEVerifyStatusRequest {
  status: EVerifyStatus;
  notes?: string;
}

export interface CloseEVerifyCaseRequest {
  closureReason: EVerifyClosureReason;
  notes?: string;
}

export interface EVerifyHistoryEntryResponse {
  auditId: Uuid;
  timestamp: IsoDateTime;
  action: string;
  performedByName?: string;
  performedByRole?: string;
  summary: string;
}

// === Form I-983 (STEM OPT Training Plan) =====================================

export type I983Status =
  | 'DRAFT'
  | 'COMPLETE'
  | 'SUBMITTED_TO_DSO'
  | 'DSO_APPROVED'
  | 'DSO_REJECTED'
  | 'AMENDMENT_REQUESTED';

// Phase 1.5 — DegreeLevel is declared near the top of this file alongside
// the registration intake types; removed here to avoid a duplicate
// identifier. The expanded set includes HIGH_SCHOOL, ASSOCIATE, DIPLOMA,
// MBA, and OTHER on top of the original BACHELORS / MASTERS / DOCTORATE.

export type DsoApprovalStatus =
  | 'NOT_SUBMITTED'
  | 'SUBMITTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'AMENDMENT_REQUESTED';

export interface I983PlanResponse {
  id: Uuid;
  candidateId?: Uuid;
  candidateName?: string;
  candidateEmail?: string;
  applicationId?: Uuid;
  offerId?: Uuid;
  entityId?: Uuid;
  entityName?: string;
  status: I983Status;

  // Section 1
  studentLastName?: string;
  studentFirstName?: string;
  studentMiddleName?: string;
  sevisId?: string;
  uscisNumber?: string;
  studentEmail?: string;
  degreeAwarded?: string;
  degreeLevel?: DegreeLevel;
  universityName?: string;
  universityCipCode?: string;
  dateOfDegreeAward?: string;
  optStartDate?: string;
  optEndDate?: string;

  // Section 2
  employerName?: string;
  employerEin?: string;
  employerAddress?: string;
  employerWebsite?: string;
  employerNaicsCode?: string;
  employerNumberOfFullTimeEmployees?: number;
  employerOfficialName?: string;
  employerOfficialTitle?: string;
  employerOfficialEmail?: string;
  employerOfficialPhone?: string;

  // Section 3
  jobTitle?: string;
  trainingStartDate?: string;
  trainingEndDate?: string;
  hoursPerWeek?: number;
  compensationAmount?: number | string;
  compensationFrequency?: CompensationFrequency;
  compensationCurrency?: string;
  supervisorName?: string;
  supervisorTitle?: string;
  supervisorEmail?: string;
  supervisorPhone?: string;

  // Section 4
  trainingProgramDescription?: string;
  howTrainingRelatesToDegree?: string;
  trainingGoalsAndObjectives?: string;
  performanceEvaluationMethod?: string;
  reportingRequirements?: string;
  skillsKnowledgeLearned?: string;
  resourcesEquipmentMaterials?: string;
  supervisorCommitments?: string;

  // Signatures
  employerSignedAt?: IsoDateTime;
  employerSignedByName?: string;
  employerSignedName?: string;
  studentSignedAt?: IsoDateTime;
  studentSignedName?: string;

  // DSO tracking
  dsoSubmittedAt?: IsoDateTime;
  dsoSubmittedByName?: string;
  dsoApprovalStatus: DsoApprovalStatus;
  dsoApprovalNotes?: string;
  dsoRespondedAt?: IsoDateTime;

  // Computed — Jackson serializes the boolean isFullySigned() getter as "fullySigned".
  fullySigned: boolean;
  daysSinceCreation?: number;
  daysOverdue?: number;

  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
  createdByName?: string;
}

export interface I983SummaryResponse {
  id: Uuid;
  candidateId?: Uuid;
  candidateName?: string;
  entityName?: string;
  jobTitle?: string;
  status: I983Status;
  dsoApprovalStatus: DsoApprovalStatus;
  /** Jackson serializes boolean isEmployerSigned() as "employerSigned". */
  employerSigned: boolean;
  /** Jackson serializes boolean isStudentSigned() as "studentSigned". */
  studentSigned: boolean;
  trainingStartDate?: string;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
}

export interface CreateI983Request {
  /** Optional when applicationId is provided — backend derives from the application. */
  candidateId?: Uuid;
  applicationId?: Uuid;
  offerId?: Uuid;
}

export interface UpdateI983Request {
  // Section 1
  studentLastName?: string;
  studentFirstName?: string;
  studentMiddleName?: string;
  sevisId?: string;
  uscisNumber?: string;
  studentEmail?: string;
  degreeAwarded?: string;
  degreeLevel?: DegreeLevel;
  universityName?: string;
  universityCipCode?: string;
  dateOfDegreeAward?: string;
  optStartDate?: string;
  optEndDate?: string;
  // Section 2
  employerName?: string;
  employerEin?: string;
  employerAddress?: string;
  employerWebsite?: string;
  employerNaicsCode?: string;
  employerNumberOfFullTimeEmployees?: number;
  employerOfficialName?: string;
  employerOfficialTitle?: string;
  employerOfficialEmail?: string;
  employerOfficialPhone?: string;
  // Section 3
  jobTitle?: string;
  trainingStartDate?: string;
  trainingEndDate?: string;
  hoursPerWeek?: number;
  compensationAmount?: number;
  compensationFrequency?: CompensationFrequency;
  compensationCurrency?: string;
  supervisorName?: string;
  supervisorTitle?: string;
  supervisorEmail?: string;
  supervisorPhone?: string;
  // Section 4
  trainingProgramDescription?: string;
  howTrainingRelatesToDegree?: string;
  trainingGoalsAndObjectives?: string;
  performanceEvaluationMethod?: string;
  reportingRequirements?: string;
  skillsKnowledgeLearned?: string;
  resourcesEquipmentMaterials?: string;
  supervisorCommitments?: string;
}

export interface SubmitToDsoRequest {
  submissionNotes?: string;
}

export interface DsoResponseRequest {
  approvalStatus: 'APPROVED' | 'REJECTED' | 'AMENDMENT_REQUESTED';
  notes?: string;
}

export interface I983HistoryEntryResponse {
  auditId: Uuid;
  timestamp: IsoDateTime;
  action: string;
  performedByName?: string;
  performedByRole?: string;
  summary: string;
}

// === Document Vault ==========================================================

export type DocumentType = 'I9' | 'I983' | 'OFFER' | 'RESUME';

export type DocumentStatusColor =
  | 'green'
  | 'amber'
  | 'red'
  | 'blue'
  | 'gray'
  | 'purple'
  | 'orange';

export interface DocumentRecordResponse {
  id: Uuid;
  type: DocumentType;
  title: string;
  candidateId?: Uuid;
  candidateName?: string;
  candidateEmail?: string;
  entityName?: string;
  status?: string;
  statusLabel?: string;
  statusColor?: DocumentStatusColor;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
  retentionPolicyText?: string;
  linkUrl?: string;
  /** Jackson serializes the boolean isImmutable() as "immutable". */
  immutable: boolean;
  /** Jackson serializes the boolean isHasAuditLog() as "hasAuditLog". */
  hasAuditLog: boolean;
}

// === Compliance Overview =====================================================

export type AlertSeverity = 'CRITICAL' | 'WARNING' | 'INFO';

export interface I9Stats {
  total: number;
  pending: number;
  completed: number;
  overdue: number;
}

export interface I983Stats {
  total: number;
  draft: number;
  complete: number;
  submittedToDso: number;
  approved: number;
  rejected: number;
  amendment: number;
}

export interface EverifyStats {
  total: number;
  pendingSubmission: number;
  open: number;
  tnc: number;
  authorized: number;
  closed: number;
}

export interface OfferStats {
  totalActive: number;
  pending: number;
  accepted: number;
  declined: number;
}

export interface ComplianceStats {
  i9: I9Stats;
  i983: I983Stats;
  everify: EverifyStats;
  offers: OfferStats;
}

export interface ComplianceAlert {
  severity: AlertSeverity;
  title: string;
  description?: string;
  linkUrl?: string;
  count?: number;
}

export interface UpcomingDeadline {
  label: string;
  dueDate: string;
  daysUntilDue?: number;
  candidateName?: string;
  linkUrl?: string;
}

export interface RecentAction {
  timestamp: IsoDateTime;
  summary: string;
  performedByName?: string;
  performedByRole?: string;
  entityType?: string;
  entityLinkUrl?: string;
}

export interface ComplianceOverviewResponse {
  stats: ComplianceStats;
  alerts: ComplianceAlert[];
  upcomingDeadlines: UpcomingDeadline[];
  recentActions: RecentAction[];
}

// === Weekly training materials (GAP C1 + D4) ================================

export type WeeklyMaterialStatus = 'DRAFT' | 'RELEASED';

export interface WeeklyMaterialResponse {
  id: Uuid;
  weekNo: number;
  title: string;
  description?: string;
  resourceUrls: string[];
  dueDate?: string; // ISO yyyy-MM-dd
  releaseDate?: IsoDateTime;

  publishedById?: Uuid;
  publishedByName?: string;

  /** Null for broadcasts (all ACTIVE interns); set for scoped to one engagement. */
  engagementId?: Uuid | null;
  scopedToCandidateName?: string | null;

  status: WeeklyMaterialStatus;
  createdAt: IsoDateTime;

  // Intern-side fields (null on supervisor views)
  acknowledged?: boolean;
  acknowledgedAt?: IsoDateTime;

  // Supervisor-side fields (null on intern views)
  acknowledgementCount?: number;
}

export interface MaterialAcknowledgementResponse {
  id: Uuid;
  materialId: Uuid;
  internCandidateId: Uuid;
  internName?: string;
  internEmail?: string;
  acknowledgedAt: IsoDateTime;
}

export interface CreateWeeklyMaterialRequest {
  weekNo: number;
  title: string;
  description?: string;
  resourceUrls?: string[];
  dueDate?: string;
  /** Null/omit = broadcast; set = scoped to a single engagement. */
  engagementId?: Uuid;
}

export interface UpdateWeeklyMaterialRequest {
  weekNo?: number;
  title?: string;
  description?: string;
  resourceUrls?: string[];
  dueDate?: string;
  engagementId?: Uuid;
  clearEngagement?: boolean;
}

// === Weekly reports (Phase-2 weekly cycle, piece #2) =========================

export type WeeklyReportStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'RETURNED'
  | 'APPROVED';

export interface WeeklyReportResponse {
  id: Uuid;
  internCandidateId: Uuid;
  internName?: string | null;
  weekStart: string; // LocalDate (YYYY-MM-DD)
  completedWork?: string | null;
  blockers?: string | null;
  learningOutcomes?: string | null;
  nextPlan?: string | null;
  status: WeeklyReportStatus;
  submittedAt?: IsoDateTime | null;
  reviewedById?: Uuid | null;
  reviewedByName?: string | null;
  reviewNotes?: string | null;
  reviewedAt?: IsoDateTime | null;
  createdAt: IsoDateTime;
  updatedAt: IsoDateTime;
}

export interface CreateWeeklyReportRequest {
  weekStart: string;
  completedWork?: string;
  blockers?: string;
  learningOutcomes?: string;
  nextPlan?: string;
}

export interface UpdateWeeklyReportRequest {
  weekStart?: string;
  completedWork?: string;
  blockers?: string;
  learningOutcomes?: string;
  nextPlan?: string;
  /** Set true to also transition DRAFT/RETURNED → SUBMITTED in the same call. */
  submit?: boolean;
}

export interface ReviewWeeklyReportRequest {
  reviewNotes?: string;
}

// === Project workspace ======================================================

export type ProjectStatus =
  | 'NOT_STARTED'
  | 'IN_PROGRESS'
  | 'SUBMITTED'
  | 'RETURNED'
  | 'TECH_APPROVED'
  | 'PENDING_VIVA'
  | 'COMPLETED';

export interface ProjectTaskResponse {
  id: Uuid;
  title: string;
  done: boolean;
  sortOrder: number;
}

export interface ProjectSubmissionResponse {
  id: Uuid;
  description?: string | null;
  links: string[];
  submittedAt: IsoDateTime;
}

export interface ProjectResponse {
  id: Uuid;
  title: string;
  description?: string | null;
  deliverables?: string | null;
  resourceLinks: string[];

  internCandidateId: Uuid;
  internName?: string | null;
  engagementId: Uuid;
  assignedById: Uuid;
  assignedByName?: string | null;

  startDate?: string | null; // LocalDate
  dueDate?: string | null;
  status: ProjectStatus;
  progressPct: number;

  reviewNotes?: string | null;
  reviewedById?: Uuid | null;
  reviewedByName?: string | null;
  reviewedAt?: IsoDateTime | null;

  createdAt: IsoDateTime;
  startedAt?: IsoDateTime | null;
  submittedAt?: IsoDateTime | null;
  completedAt?: IsoDateTime | null;
  updatedAt: IsoDateTime;

  tasks: ProjectTaskResponse[];
  submissions: ProjectSubmissionResponse[];
}

export interface CreateProjectRequest {
  title: string;
  candidateId: Uuid;
  description?: string;
  deliverables?: string;
  resourceLinks?: string[];
  startDate?: string;
  dueDate?: string;
  taskTitles?: string[];
}

export interface UpdateProjectRequest {
  title?: string;
  description?: string;
  deliverables?: string;
  resourceLinks?: string[];
  startDate?: string;
  dueDate?: string;
  taskTitles?: string[];
}

export interface UpdateProgressRequest {
  progressPct?: number;
  taskUpdates?: { taskId: Uuid; done: boolean }[];
}

// === Workspace + immutable submissions ======================================

export interface WorkspaceFile {
  id: Uuid;
  projectId?: Uuid;
  path: string;
  sizeBytes: number;
  lastModifiedAt?: IsoDateTime;
  lastModifiedBy?: Uuid;
  /** Populated on single-file fetches; omitted from list responses. */
  content?: string;
}

export type ReviewOutcome = 'PENDING' | 'APPROVED' | 'RETURNED';

export interface WorkspaceSubmission {
  id: Uuid;
  projectId: Uuid;
  submissionNumber: number;
  submittedAt: IsoDateTime;
  submittedBy: Uuid;
  reviewedAt?: IsoDateTime;
  reviewerId?: Uuid;
  reviewOutcome: ReviewOutcome;
  reviewReason?: string;
  fileCount?: number;
}

export interface SubmissionDetail {
  submission: WorkspaceSubmission;
  files: WorkspaceFile[];
}

export interface SubmitProjectRequest {
  description?: string;
  links?: string[];
}

export interface ReviewProjectRequest {
  reviewNotes?: string;
}

// === Periodic evaluations ===================================================

export type EvaluationType =
  | 'MIDPOINT'
  | 'FINAL'
  | 'I983_12MO'
  | 'I983_FINAL'
  | 'CHECKPOINT';

export type EvaluationStatus = 'DRAFT' | 'FINALIZED';

export type EvaluationRecommendation =
  | 'HIGHLY_RECOMMENDED'
  | 'RECOMMENDED'
  | 'NEEDS_IMPROVEMENT'
  | 'NOT_RECOMMENDED';

export type RubricCriterion =
  | 'TECHNICAL_SKILLS'
  | 'CODE_QUALITY'
  | 'COMMUNICATION'
  | 'INITIATIVE'
  | 'PROFESSIONALISM'
  | 'LEARNING';

export interface EvaluationRubricScoreResponse {
  id: Uuid;
  criterion: RubricCriterion;
  score: number;
  note?: string | null;
}

export interface EvaluationSelfReviewResponse {
  id: Uuid;
  reflection?: string | null;
  selfOverallRating?: number | null;
  selfTechnicalRating?: number | null;
  selfGrowthRating?: number | null;
  submittedAt?: IsoDateTime | null;
}

export interface EvaluationContextResponse {
  completedProjects: { id: Uuid; title: string; completedDate: string | null }[];
  reportStats: {
    totalCount: number;
    approvedCount: number;
    returnedCount: number;
    pendingCount: number;
  };
  timesheetStats: {
    totalCount: number;
    approvedCount: number;
    approvedHours: string;
  };
}

export interface EvaluationResponse {
  id: Uuid;
  internCandidateId: Uuid;
  internName?: string | null;
  engagementId: Uuid;
  evaluatorId: Uuid;
  evaluatorName?: string | null;
  type: EvaluationType;
  periodStart?: string | null;
  periodEnd?: string | null;
  overallRating?: number | null;
  strengths?: string | null;
  areasForImprovement?: string | null;
  comments?: string | null;
  recommendation?: EvaluationRecommendation | null;
  status: EvaluationStatus;
  createdAt: IsoDateTime;
  finalizedAt?: IsoDateTime | null;
  updatedAt: IsoDateTime;
  rubric: EvaluationRubricScoreResponse[];
  selfReview?: EvaluationSelfReviewResponse | null;
  context?: EvaluationContextResponse | null;
}

export interface CreateEvaluationRequest {
  candidateId: Uuid;
  type: EvaluationType;
  periodStart?: string;
  periodEnd?: string;
  rubric?: { criterion: RubricCriterion; score: number; note?: string }[];
}

export interface UpdateEvaluationRequest {
  periodStart?: string;
  periodEnd?: string;
  overallRating?: number;
  strengths?: string;
  areasForImprovement?: string;
  comments?: string;
  recommendation?: EvaluationRecommendation;
  rubric?: { criterion: RubricCriterion; score: number; note?: string }[];
}

export interface SubmitSelfReviewRequest {
  reflection?: string;
  selfOverallRating?: number;
  selfTechnicalRating?: number;
  selfGrowthRating?: number;
}

// === Candidate sidebar (backend-driven) =====================================

export type NavBadge =
  | { type: 'count'; value: number }
  | { type: 'new' };

export interface NavItem {
  key: string;
  label: string;
  route: string;
  group: 'primary' | 'history' | null;
  badge?: NavBadge | null;
}

export interface CandidateNavResponse {
  items: NavItem[];
  intern: boolean;
}

// === Candidate dashboard journey (SPEC §3 §4 §5 §6) ==========================

export type StageState = 'done' | 'current' | 'upcoming' | 'blocked';
export type SubStepState =
  | 'done'
  | 'current'
  | 'upcoming'
  | 'waiting'
  | 'blocked';
export type SubStepOwner =
  | 'you'
  | 'recruiter'
  | 'employer'
  | 'supervisor'
  | 'dso'
  | 'system';

export interface CandidateSubStep {
  key: string;
  label: string;
  state: SubStepState;
  owner?: SubStepOwner | null;
  href?: string | null;
  subtitle?: string | null;
}

export interface CandidateJourneyStage {
  key: string;
  label: string;
  state: StageState;
  subSteps: CandidateSubStep[];
}

export interface CandidateJourney {
  currentStageKey: string;
  isExited: boolean;
  stages: CandidateJourneyStage[];
}

export interface CandidateResumeInfo {
  id: Uuid;
  fileName: string;
  uploadedAt?: IsoDateTime;
}

// === Q&A (viva) sessions ====================================================

export type QaSessionStatus = 'SCHEDULED' | 'CONDUCTED' | 'COMPLETED' | 'RETURNED';

export interface QaSession {
  id: Uuid;
  projectId: Uuid;
  projectTitle?: string;
  internUserId?: Uuid;
  internName?: string;
  scheduledAt: IsoDateTime;
  meetingLink?: string;
  zoomMeetingId?: string;
  zoomJoinUrl?: string;
  zoomStartUrl?: string;
  status: QaSessionStatus;
  questionsAsked?: string;
  internResponses?: string;
  marks?: number;
  remarks?: string;
  scheduledByUserId?: Uuid;
  conductedByUserId?: Uuid;
  completedAt?: IsoDateTime;
  returnedAt?: IsoDateTime;
  returnReason?: string;
  createdAt?: IsoDateTime;
  updatedAt?: IsoDateTime;
}

export interface ScheduleQaSessionRequest {
  projectId: Uuid;
  scheduledAt: IsoDateTime;
  meetingLink?: string;
  durationMinutes?: number;
  timezone?: string;
  topic?: string;
  agenda?: string;
}

// === Day-by-day timesheet ===================================================

export type DayOfWeek =
  | 'MONDAY'
  | 'TUESDAY'
  | 'WEDNESDAY'
  | 'THURSDAY'
  | 'FRIDAY'
  | 'SATURDAY'
  | 'SUNDAY';

export type TimesheetStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED';

export interface TimesheetDayCell {
  id?: Uuid;
  dayOfWeek: DayOfWeek;
  hours: number;
  notes?: string;
}

export interface TimesheetWeek {
  id: Uuid;
  internUserId?: Uuid;
  internName?: string;
  weekStart: string;
  status: TimesheetStatus;
  totalHours: number;
  days: TimesheetDayCell[];
  reviewNote?: string;
  approvedByName?: string;
  approvedAt?: IsoDateTime;
  submittedAt?: IsoDateTime;
  createdAt?: IsoDateTime;
}

// === Reporting Manager dashboard ===========================================

export interface ProjectAwaitingQa {
  projectId: Uuid;
  projectTitle: string;
  internUserId?: Uuid;
  internName?: string;
  techApprovedAt?: IsoDateTime;
}

export interface ReportingManagerDashboard {
  pendingQaCount: number;
  qaInProgressCount: number;
  pendingTimesheetCount: number;
  completedThisMonthCount: number;
  projectsAwaitingQa: ProjectAwaitingQa[];
  qaInProgress: QaSession[];
  pendingTimesheets: TimesheetWeek[];
}
