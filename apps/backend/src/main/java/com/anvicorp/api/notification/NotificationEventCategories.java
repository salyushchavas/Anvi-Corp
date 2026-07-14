package com.anvicorp.api.notification;

import java.util.EnumMap;
import java.util.Map;

/**
 * Static map: which category does each {@link NotificationEventType} belong
 * to? Used by {@code NotificationService.deliver} to decide whether the
 * recipient's preference flag should gate the send.
 *
 * <p>Maintenance rule: every new event type added must land in this map. The
 * helper {@link #categoryOf(NotificationEventType)} defaults to
 * {@code TRANSACTIONAL} for unmapped values — i.e. always send — so a
 * forgotten classification fails safe (the user still gets the email) rather
 * than silently dropping mail.</p>
 */
final class NotificationEventCategories {

    private static final Map<NotificationEventType, NotificationCategory> MAP =
            new EnumMap<>(NotificationEventType.class);

    static {
        // ── Auth flow — transactional (never opt-outable). ───────────────────
        MAP.put(NotificationEventType.VERIFICATION_CODE, NotificationCategory.TRANSACTIONAL);
        MAP.put(NotificationEventType.APPLICANT_ID_ISSUED, NotificationCategory.TRANSACTIONAL);
        MAP.put(NotificationEventType.PASSWORD_RESET, NotificationCategory.TRANSACTIONAL);

        // ── Applicant lifecycle — transactional. ─────────────────────────────
        MAP.put(NotificationEventType.APPLICATION_RECEIVED, NotificationCategory.TRANSACTIONAL);
        MAP.put(NotificationEventType.APPLICATION_SHORTLISTED, NotificationCategory.TRANSACTIONAL);
        MAP.put(NotificationEventType.APPLICATION_REJECTED, NotificationCategory.TRANSACTIONAL);
        MAP.put(NotificationEventType.INTERVIEW_SCHEDULED, NotificationCategory.TRANSACTIONAL);
        MAP.put(NotificationEventType.OFFER_EXTENDED, NotificationCategory.TRANSACTIONAL);
        MAP.put(NotificationEventType.OFFER_ACCEPTED, NotificationCategory.TRANSACTIONAL);
        MAP.put(NotificationEventType.OFFER_ACCEPTED_OPS, NotificationCategory.TRANSACTIONAL);
        MAP.put(NotificationEventType.ONBOARDING_WELCOME, NotificationCategory.TRANSACTIONAL);
        MAP.put(NotificationEventType.CONDITIONAL_SELECT, NotificationCategory.TRANSACTIONAL);

        // Interview reminder — categorised as a reminder, opt-outable.
        MAP.put(NotificationEventType.INTERVIEW_REMINDER, NotificationCategory.REMINDERS);
        // Interview completion ack — applicant should always be told.
        MAP.put(NotificationEventType.INTERVIEW_COMPLETED, NotificationCategory.TRANSACTIONAL);

        // ── Compliance / onboarding — mix of transactional + reminders. ──────
        // First-time I-9 §1 reminder fires at engagement activation; classify
        // as a reminder so the user can silence repeats via the opt-out.
        MAP.put(NotificationEventType.I9_SECTION1_REMINDER, NotificationCategory.REMINDERS);
        // HR-facing — always send (staff need it for compliance work).
        MAP.put(NotificationEventType.I9_SECTION2_PENDING, NotificationCategory.TRANSACTIONAL);
        // Intern-facing one-shot — transactional (single, important fact).
        MAP.put(NotificationEventType.I983_PLAN_NEEDED, NotificationCategory.TRANSACTIONAL);
        MAP.put(NotificationEventType.I983_PLAN_READY, NotificationCategory.TRANSACTIONAL);
        MAP.put(NotificationEventType.EVERIFY_CASE_OPENED, NotificationCategory.TRANSACTIONAL);
        // TNC — URGENT, never opt-outable.
        MAP.put(NotificationEventType.EVERIFY_TNC_ALERT, NotificationCategory.TRANSACTIONAL);
        MAP.put(NotificationEventType.EVERIFY_CLEARED, NotificationCategory.TRANSACTIONAL);

        // Work-auth expiry — reminders.
        MAP.put(NotificationEventType.WORKAUTH_EXPIRY_90, NotificationCategory.REMINDERS);
        MAP.put(NotificationEventType.WORKAUTH_EXPIRY_60, NotificationCategory.REMINDERS);
        MAP.put(NotificationEventType.WORKAUTH_EXPIRY_30, NotificationCategory.REMINDERS);
        MAP.put(NotificationEventType.WORKAUTH_EXPIRY_14, NotificationCategory.REMINDERS);
        MAP.put(NotificationEventType.WORKAUTH_EXPIRY_7, NotificationCategory.REMINDERS);
        MAP.put(NotificationEventType.COMPLIANCE_TASK_REMINDER, NotificationCategory.REMINDERS);

        // ── Intern weekly cycle. ─────────────────────────────────────────────
        // Engagement-updates (opt-outable):
        // WEEKLY_MATERIAL_RELEASED removed in Trainer Phase 0 (not in doc spec)
        MAP.put(NotificationEventType.WEEKLY_REPORT_RETURNED, NotificationCategory.ENGAGEMENT_UPDATES);
        MAP.put(NotificationEventType.WEEKLY_REPORT_APPROVED, NotificationCategory.ENGAGEMENT_UPDATES);
        MAP.put(NotificationEventType.PROJECT_ASSIGNED, NotificationCategory.ENGAGEMENT_UPDATES);
        MAP.put(NotificationEventType.PROJECT_SUBMITTED, NotificationCategory.ENGAGEMENT_UPDATES);
        MAP.put(NotificationEventType.PROJECT_RETURNED, NotificationCategory.ENGAGEMENT_UPDATES);
        MAP.put(NotificationEventType.PROJECT_COMPLETED, NotificationCategory.ENGAGEMENT_UPDATES);
        MAP.put(NotificationEventType.EVALUATION_FINALIZED, NotificationCategory.ENGAGEMENT_UPDATES);
        // Reminders (opt-outable):
        // MATERIAL_UNREAD_REMINDER removed in Trainer Phase 0 (not in doc spec)
        MAP.put(NotificationEventType.WEEKLY_REPORT_DUE, NotificationCategory.REMINDERS);
        MAP.put(NotificationEventType.TIMESHEET_DUE, NotificationCategory.REMINDERS);
        MAP.put(NotificationEventType.EVALUATION_DUE, NotificationCategory.REMINDERS);
        MAP.put(NotificationEventType.I983_SELF_EVAL_DUE, NotificationCategory.REMINDERS);

        // Two-role workflow — intern-facing transition updates.
        MAP.put(NotificationEventType.PROJECT_TECH_APPROVED, NotificationCategory.ENGAGEMENT_UPDATES);
        MAP.put(NotificationEventType.PROJECT_RETURNED_FOR_REVISIONS, NotificationCategory.ENGAGEMENT_UPDATES);
        MAP.put(NotificationEventType.PROJECT_PENDING_VIVA, NotificationCategory.ENGAGEMENT_UPDATES);
    }

    private NotificationEventCategories() {}

    /** Defaults to TRANSACTIONAL — failing safe so a forgotten classification never silences mail. */
    public static NotificationCategory categoryOf(NotificationEventType type) {
        return MAP.getOrDefault(type, NotificationCategory.TRANSACTIONAL);
    }
}
