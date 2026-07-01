package com.anvicorp.api.mail.rules;

import com.anvicorp.api.mail.entity.MailFolder;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single action, serialized inside a rule's actions_json. {@code targetSystemFolder}
 * applies to MOVE_TO_SYSTEM_FOLDER; {@code targetCustomFolderId} (a UUID string) to
 * MOVE_TO_CUSTOM_FOLDER. Both are null for the flag/DELETE action types.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MailRuleAction(MailRuleActionType type, MailFolder targetSystemFolder, String targetCustomFolderId) {
}
