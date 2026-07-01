package com.anvicorp.api.mail.dto;

import com.anvicorp.api.mail.entity.MailRuleMatchMode;
import com.anvicorp.api.mail.rules.MailRuleAction;
import com.anvicorp.api.mail.rules.MailRuleCondition;

import java.time.Instant;
import java.util.List;

/** A rule with its conditions/actions deserialized back to typed lists for the UI. */
public record MailRuleResponse(
        String id,
        String name,
        int priority,
        boolean enabled,
        MailRuleMatchMode matchMode,
        boolean stopProcessing,
        List<MailRuleCondition> conditions,
        List<MailRuleAction> actions,
        Instant createdAt) {
}
