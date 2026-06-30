package com.anvicorp.api.mail.dto;

import java.util.List;

/** Save/update a draft. Everything is optional — drafts persist freely. */
public record MailDraftRequest(
        List<String> to,
        List<String> cc,
        List<String> bcc,
        String subject,
        String bodyText,
        String bodyHtml,
        String inReplyTo
) {
}
