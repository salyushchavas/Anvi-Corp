package com.anvicorp.api.event;

import java.util.UUID;

/**
 * Mail bridge Phase 4 — fired by {@code MailHandoverService.assignCompanyEmail}
 * AFTER the user-row mutations + mailbox provisioning + welcome-message
 * drop have all committed.
 *
 * <p>The single listener
 * ({@code com.anvicorp.api.listener.CompanyEmailAssignedListener})
 * runs at {@code TransactionPhase.AFTER_COMMIT} and sends THE LAST
 * EXTERNAL email to the user's personal Gmail with the starting
 * credentials. Listening after-commit guarantees credentials are
 * never emailed for a rolled-back mailbox.</p>
 *
 * <p>The starting password rides on this event as a transient
 * in-memory string — it is NEVER persisted. The listener uses it
 * exactly once to compose the credential email body, then it goes out
 * of scope with the event. There is no second source of truth for the
 * starting password after this listener runs (the BCrypt hash on the
 * mail account is what the system retains long-term).</p>
 */
public final class CompanyEmailAssignedEvent extends DomainEvent {

    private final UUID userId;
    private final UUID mailAccountId;
    private final String personalEmail;
    private final String companyEmail;
    private final String startingPassword;

    public CompanyEmailAssignedEvent(UUID userId,
                                      UUID mailAccountId,
                                      String personalEmail,
                                      String companyEmail,
                                      String startingPassword) {
        this.userId = userId;
        this.mailAccountId = mailAccountId;
        this.personalEmail = personalEmail;
        this.companyEmail = companyEmail;
        this.startingPassword = startingPassword;
    }

    public UUID getUserId() { return userId; }
    public UUID getMailAccountId() { return mailAccountId; }
    public String getPersonalEmail() { return personalEmail; }
    public String getCompanyEmail() { return companyEmail; }
    public String getStartingPassword() { return startingPassword; }
}
