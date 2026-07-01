package com.anvicorp.api.mail.sse;

import java.util.UUID;

/**
 * Internal application event published inside the delivery transaction, once per
 * delivered recipient entry, carrying that entry's A7-resolved placement. A
 * {@code @TransactionalEventListener(AFTER_COMMIT)} fans it out to the recipient's
 * own SSE streams only AFTER the row is committed (and visible) — best-effort, so
 * a real-time failure never affects the send.
 */
public record MailDeliveredEvent(UUID recipientId, String folder, String customFolderId, String messageId) {
}
