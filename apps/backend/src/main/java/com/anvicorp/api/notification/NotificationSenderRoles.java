package com.anvicorp.api.notification;

import java.util.EnumMap;
import java.util.Map;

/**
 * Mail bridge Phase 2 — event-type → sender role mailbox local-part.
 *
 * <p>Ownership rules followed:</p>
 * <ul>
 *   <li><b>erm@</b> — application decisions, interviews, offer, onboarding,
 *       document review, the I-9 / I-983 / E-Verify family. The ERM is the
 *       human operator behind these touchpoints, so the intern reading the
 *       company inbox sees a coherent ERM sender on every step of their
 *       lifecycle.</li>
 *   <li><b>trainer@</b> — project assignment + review, weekly report
 *       returned/approved (the trainer is the one taking action).</li>
 *   <li><b>evaluator@</b> — evaluation due/finalized + I-983 self-eval
 *       (the evaluator owns the assessment cycle).</li>
 *   <li><b>manager@</b> — viva scheduling. (Hire decision, timesheet
 *       approval, and escalations don't have NotificationEventType values
 *       yet — when those events land, add them here.)</li>
 *   <li><b>noreply@</b> — system-fired or automated reminders with no
 *       single human owner: verification codes, password resets, the
 *       work-auth-expiry scheduler, generic compliance reminders, and the
 *       weekly-due nudges. Also the safe default for any value not
 *       enumerated below — see {@link #forEvent(NotificationEventType)}.</li>
 * </ul>
 *
 * <p>This is the SINGLE place to remap senders. The roles are persisted
 * mailboxes seeded in Phase 1 ({@code MailRoleAccountSeeder}).</p>
 */
public final class NotificationSenderRoles {

    /** Default fallback when no mapping is found OR no event context is set. */
    public static final String DEFAULT_LOCAL_PART = "noreply";

    static final String NOREPLY   = "noreply";
    static final String ERM       = "erm";
    static final String TRAINER   = "trainer";
    static final String EVALUATOR = "evaluator";
    static final String MANAGER   = "manager";

    private static final Map<NotificationEventType, String> MAPPING =
            new EnumMap<>(NotificationEventType.class);

    static {
        // System / auth — noreply.
        MAPPING.put(NotificationEventType.VERIFICATION_CODE,    NOREPLY);
        MAPPING.put(NotificationEventType.APPLICANT_ID_ISSUED,  NOREPLY);
        MAPPING.put(NotificationEventType.PASSWORD_RESET,       NOREPLY);

        // ERM-owned applicant lifecycle.
        MAPPING.put(NotificationEventType.APPLICATION_RECEIVED,    ERM);
        MAPPING.put(NotificationEventType.APPLICATION_SHORTLISTED, ERM);
        MAPPING.put(NotificationEventType.APPLICATION_REJECTED,    ERM);
        MAPPING.put(NotificationEventType.INTERVIEW_SCHEDULED,     ERM);
        MAPPING.put(NotificationEventType.INTERVIEW_COMPLETED,     ERM);
        MAPPING.put(NotificationEventType.OFFER_EXTENDED,          ERM);
        MAPPING.put(NotificationEventType.OFFER_ACCEPTED,          ERM);
        MAPPING.put(NotificationEventType.OFFER_ACCEPTED_OPS,      ERM);
        MAPPING.put(NotificationEventType.ONBOARDING_WELCOME,      ERM);
        MAPPING.put(NotificationEventType.CONDITIONAL_SELECT,      ERM);

        // ERM-owned compliance (I-9 / I-983 / E-Verify).
        MAPPING.put(NotificationEventType.I9_SECTION1_REMINDER,    ERM);
        MAPPING.put(NotificationEventType.I9_SECTION2_PENDING,     ERM);
        MAPPING.put(NotificationEventType.I983_PLAN_NEEDED,        ERM);
        MAPPING.put(NotificationEventType.I983_PLAN_READY,         ERM);
        MAPPING.put(NotificationEventType.EVERIFY_CASE_OPENED,     ERM);
        MAPPING.put(NotificationEventType.EVERIFY_TNC_ALERT,       ERM);
        MAPPING.put(NotificationEventType.EVERIFY_CLEARED,         ERM);

        // Scheduler-fired automated reminders — noreply.
        MAPPING.put(NotificationEventType.INTERVIEW_REMINDER,        NOREPLY);
        MAPPING.put(NotificationEventType.WORKAUTH_EXPIRY_90,        NOREPLY);
        MAPPING.put(NotificationEventType.WORKAUTH_EXPIRY_60,        NOREPLY);
        MAPPING.put(NotificationEventType.WORKAUTH_EXPIRY_30,        NOREPLY);
        MAPPING.put(NotificationEventType.WORKAUTH_EXPIRY_14,        NOREPLY);
        MAPPING.put(NotificationEventType.WORKAUTH_EXPIRY_7,         NOREPLY);
        MAPPING.put(NotificationEventType.COMPLIANCE_TASK_REMINDER,  NOREPLY);
        MAPPING.put(NotificationEventType.WEEKLY_REPORT_DUE,         NOREPLY);
        MAPPING.put(NotificationEventType.TIMESHEET_DUE,             NOREPLY);

        // Trainer-owned project + review actions.
        MAPPING.put(NotificationEventType.WEEKLY_REPORT_RETURNED,        TRAINER);
        MAPPING.put(NotificationEventType.WEEKLY_REPORT_APPROVED,        TRAINER);
        MAPPING.put(NotificationEventType.PROJECT_ASSIGNED,              TRAINER);
        MAPPING.put(NotificationEventType.PROJECT_SUBMITTED,             TRAINER);
        MAPPING.put(NotificationEventType.PROJECT_RETURNED,              TRAINER);
        MAPPING.put(NotificationEventType.PROJECT_COMPLETED,             TRAINER);
        MAPPING.put(NotificationEventType.PROJECT_TECH_APPROVED,         TRAINER);
        MAPPING.put(NotificationEventType.PROJECT_RETURNED_FOR_REVISIONS, TRAINER);

        // Manager-owned (Reporting Manager schedules the viva).
        MAPPING.put(NotificationEventType.PROJECT_PENDING_VIVA, MANAGER);

        // Evaluator-owned assessment cycle.
        MAPPING.put(NotificationEventType.EVALUATION_DUE,        EVALUATOR);
        MAPPING.put(NotificationEventType.EVALUATION_FINALIZED,  EVALUATOR);
        MAPPING.put(NotificationEventType.I983_SELF_EVAL_DUE,    EVALUATOR);
    }

    private NotificationSenderRoles() {}

    /**
     * Sender local-part for a notification event type. Returns
     * {@link #DEFAULT_LOCAL_PART} ({@code noreply}) for any value not
     * explicitly mapped, so a future enum addition doesn't break the
     * bridge — it just gets routed via the safe-default sender until
     * someone updates the map.
     */
    public static String forEvent(NotificationEventType eventType) {
        if (eventType == null) return DEFAULT_LOCAL_PART;
        String localPart = MAPPING.get(eventType);
        return localPart != null ? localPart : DEFAULT_LOCAL_PART;
    }
}
