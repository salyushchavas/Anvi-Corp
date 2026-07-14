package com.anvicorp.api.mail.sse;

/**
 * The payload pushed to a recipient's live SSE stream (serialized as JSON).
 * {@code folder} is the system-folder name the A7 rule engine RESOLVED for this
 * delivery (e.g. INBOX/ARCHIVE/TRASH); {@code customFolderId} is non-null when a
 * rule filed the message into an A6 custom folder (in which case the client bumps
 * that folder's badge and does NOT pop a desktop notification). A true INBOX
 * arrival is {@code folder=INBOX} with {@code customFolderId=null}.
 */
public record MailSseEvent(String type, String folder, String customFolderId, String messageId) {

    public static MailSseEvent newMail(String folder, String customFolderId, String messageId) {
        return new MailSseEvent("NEW_MAIL", folder, customFolderId, messageId);
    }
}
