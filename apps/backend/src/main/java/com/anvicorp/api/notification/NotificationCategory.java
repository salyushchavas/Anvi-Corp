package com.anvicorp.api.notification;

/**
 * Three buckets the user can (or can't) opt out of.
 *
 * <ul>
 *   <li>{@code TRANSACTIONAL} — always sent. Verification codes, password
 *       resets, offer letters, TNC alerts. Per CAN-SPAM these aren't
 *       commercial messages and the user can't silence them; doing so would
 *       break account safety.</li>
 *   <li>{@code REMINDERS} — opt-outable. Compliance reminders, weekly-cycle
 *       due-soon nudges, work-auth expiry chirps.</li>
 *   <li>{@code ENGAGEMENT_UPDATES} — opt-outable. Project assigned/returned/
 *       completed, evaluation finalized, material released.</li>
 * </ul>
 */
public enum NotificationCategory {
    TRANSACTIONAL,
    REMINDERS,
    ENGAGEMENT_UPDATES
}
