package com.anvicorp.api.mail.dto;

import java.util.List;

/** A thread = the caller's visible messages sharing a threadId, oldest first. */
public record MailThreadResponse(
        String threadId,
        List<MailMessageDetail> messages
) {
}
