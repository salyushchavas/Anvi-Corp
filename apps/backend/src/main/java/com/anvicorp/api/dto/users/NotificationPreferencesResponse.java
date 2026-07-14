package com.anvicorp.api.dto.users;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Two opt-out flags exposed to the user. Both default TRUE (opt-in).
 * Transactional emails (verification, password reset, offer letters, TNC,
 * compliance HR routing) ignore both flags and are always sent.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationPreferencesResponse {
    /** Reminders for upcoming compliance and weekly tasks, expiry alerts. */
    private boolean reminders;
    /** Project + evaluation + material updates as they happen. */
    private boolean engagementUpdates;
}
