package com.anvicorp.api.notification;

/**
 * One value per distinct outbound notification. The (eventType, targetId)
 * tuple is the idempotency key — see {@link com.anvicorp.api.entity.SentNotification}.
 *
 * <p>Why separate {@code OFFER_ACCEPTED} from {@code OFFER_ACCEPTED_OPS}:
 * the same source event (an Offer flipping to ACCEPTED) fires two distinct
 * sends to two distinct recipients. Treating them as separate event types
 * means each gets its own idempotency row, which keeps the simple
 * "one row per send" model intact.</p>
 */
public enum NotificationEventType {

    // Auth flow (legacy — kept for completeness of the registry; these
    // still go through NotificationStub directly because they don't
    // need idempotency: re-issuing a fresh verification code is the
    // user-driven retry).
    VERIFICATION_CODE,
    APPLICANT_ID_ISSUED,
    PASSWORD_RESET,

    // Batch 1 — applicant lifecycle.
    APPLICATION_RECEIVED,        // confirmation to applicant on submit
    APPLICATION_SHORTLISTED,     // status flipped to SHORTLISTED
    APPLICATION_REJECTED,        // status flipped to REJECTED
    INTERVIEW_SCHEDULED,         // a new Interview row exists
    INTERVIEW_REMINDER,          // 24h before, scheduled job
    INTERVIEW_COMPLETED,         // Change 4: scorecard finalized → applicant ack
    OFFER_EXTENDED,              // Offer.status SENT
    OFFER_ACCEPTED,              // applicant confirmation
    OFFER_ACCEPTED_OPS,          // notification to Operations
    ONBOARDING_WELCOME,          // engagement flipped to ACTIVE

    // Existing — recruiter conditional select (kept for the migration when
    // it moves under the unified NotificationService).
    CONDITIONAL_SELECT,

    // Batch 2 — compliance / onboarding.
    I9_SECTION1_REMINDER,        // intern, after engagement ACTIVE & §1 incomplete
    I9_SECTION2_PENDING,         // HR, when intern completes §1
    I983_PLAN_NEEDED,            // intern (STEM_OPT only), after engagement ACTIVE
    I983_PLAN_READY,             // HR, when student signs the plan
    EVERIFY_CASE_OPENED,         // intern, when case auto-promotes to OPEN
    EVERIFY_TNC_ALERT,           // intern — URGENT (Tentative Nonconfirmation)
    EVERIFY_CLEARED,             // intern (and HR via shared subject), favorable close

    // Work-authorization expiry — one event type per threshold so the
    // (event_type, target_id=engagement_id) unique constraint naturally
    // emails each threshold exactly once.
    WORKAUTH_EXPIRY_90,
    WORKAUTH_EXPIRY_60,
    WORKAUTH_EXPIRY_30,
    WORKAUTH_EXPIRY_14,
    WORKAUTH_EXPIRY_7,

    // Generic compliance-task reminder — target_id = task_id, so one reminder
    // per overdue task (not one per overdue day).
    COMPLIANCE_TASK_REMINDER,

    // Batch 3 — intern weekly cycle.
    //
    // Event-triggered (target_id = real row id):
    // WEEKLY_MATERIAL_RELEASED — removed in Trainer Phase 0 (not in doc spec)
    WEEKLY_REPORT_RETURNED,      // intern; target_id = report_id
    WEEKLY_REPORT_APPROVED,      // intern; target_id = report_id
    PROJECT_ASSIGNED,            // intern; target_id = project_id
    PROJECT_SUBMITTED,           // supervisor; target_id = project_id
    PROJECT_RETURNED,            // intern; target_id = project_id
    PROJECT_COMPLETED,           // intern; target_id = project_id
    EVALUATION_FINALIZED,        // intern; target_id = evaluation_id
    //
    // Scheduler-fired weekly / overdue reminders. The (event × intern × week)
    // grain is encoded into a deterministic UUID at send time (target_id =
    // UUIDv3 of "EVENT:owner:period"). See NotificationService.weeklyTargetId.
    // MATERIAL_UNREAD_REMINDER — removed in Trainer Phase 0 (not in doc spec)
    WEEKLY_REPORT_DUE,           // intern (per engagement × weekStart)
    TIMESHEET_DUE,               // intern (per engagement × weekStart)
    EVALUATION_DUE,              // supervisor; target_id = evaluation_id (DRAFT N+ days)
    I983_SELF_EVAL_DUE,          // intern; target_id = evaluation_id

    // Two-role workflow (P1b prerequisite for the workspace task).
    // target_id = project_id; recipient is the intern for tech-approval +
    // return-for-revisions + pending-viva, and the intern again on
    // completion (the existing PROJECT_COMPLETED covers that path so no
    // new completion type is added here).
    PROJECT_TECH_APPROVED,
    PROJECT_RETURNED_FOR_REVISIONS,
    PROJECT_PENDING_VIVA
}
