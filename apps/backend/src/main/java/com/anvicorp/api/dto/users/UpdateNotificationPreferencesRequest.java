package com.anvicorp.api.dto.users;

/**
 * Both fields are optional — only the supplied ones are updated. Sending
 * {@code null} for either keeps the existing value.
 */
public record UpdateNotificationPreferencesRequest(
        Boolean reminders,
        Boolean engagementUpdates
) {}
