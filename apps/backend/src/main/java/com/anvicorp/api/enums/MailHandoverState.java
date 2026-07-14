package com.anvicorp.api.enums;

/**
 * Tracks where a {@link com.anvicorp.api.entity.User} is in the
 * intern → employee mailbox handover.
 *
 * <ul>
 *   <li>{@code PERSONAL} — pre-handover. The user receives notifications on
 *       their personal Gmail (the {@code email} they registered with). This
 *       is the default for every new row.</li>
 *   <li>{@code PENDING_ACTIVATION} — ERM has assigned a company mailbox
 *       ({@code mail_account_id} is set) but the user hasn't activated it
 *       yet. Notifications still go to their personal Gmail.</li>
 *   <li>{@code ACTIVATED} — the user has activated their company mailbox.
 *       All subsequent notifications land in their internal inbox; the
 *       login email may have been swapped to the company address and the
 *       original personal Gmail moved to {@code users.personal_email}.</li>
 * </ul>
 *
 * <p>Phase 1 of the mail bridge only persists this column — no read path
 * branches on it yet. Phases 2/3 wire the dispatcher.</p>
 */
public enum MailHandoverState {
    PERSONAL,
    PENDING_ACTIVATION,
    ACTIVATED
}
