package com.anvicorp.api.mail.rules;

import com.anvicorp.api.mail.entity.MailFolder;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * The resolved placement + flags for one recipient's inbound entry, mutated as a
 * rule's actions apply in order. Defaults are exactly today's A2 behavior: an
 * unread INBOX entry with no custom placement and no flags. Honors the A6
 * precedence — a non-null {@code customFolderId} means the entry lives in that
 * custom folder (the system {@link #folder} enum is ignored for placement).
 */
@Getter
@Setter
public class MailDeliveryOutcome {

    private MailFolder folder = MailFolder.INBOX;
    private UUID customFolderId;
    private boolean read;
    private boolean starred;
    private boolean important;

    /** The fail-open / no-match default: an unread INBOX entry (A2 behavior). */
    public static MailDeliveryOutcome inbox() {
        return new MailDeliveryOutcome();
    }
}
