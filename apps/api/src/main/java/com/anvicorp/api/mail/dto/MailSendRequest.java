package com.anvicorp.api.mail.dto;

import java.util.List;

/**
 * Compose/send a message. Recipients are bare local parts or full addresses
 * within the SENDER's domain (cross-domain is rejected). At least one recipient
 * is required (validated in the service). {@code inReplyTo} is a message id when
 * this is a reply.
 */
public record MailSendRequest(
        List<String> to,
        List<String> cc,
        List<String> bcc,
        String subject,
        String bodyText,
        String bodyHtml,
        String inReplyTo
) {
}
