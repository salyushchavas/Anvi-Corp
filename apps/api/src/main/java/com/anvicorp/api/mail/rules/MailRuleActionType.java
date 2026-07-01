package com.anvicorp.api.mail.rules;

/** What a matched rule does to the inbound entry. DELETE routes to TRASH. */
public enum MailRuleActionType {
    MOVE_TO_SYSTEM_FOLDER,
    MOVE_TO_CUSTOM_FOLDER,
    MARK_READ,
    STAR,
    MARK_IMPORTANT,
    DELETE
}
