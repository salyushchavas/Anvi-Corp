package com.anvicorp.api.mail.dto;

import com.anvicorp.api.mail.entity.MailRuleMatchMode;
import com.anvicorp.api.mail.rules.MailRuleAction;
import com.anvicorp.api.mail.rules.MailRuleCondition;

import java.util.List;

/**
 * Create/update body for a rule. {@code enabled}/{@code matchMode}/{@code
 * stopProcessing} default to true/ALL/false when null. Priority is managed by the
 * server (append on create, compacted by reorder), never set here.
 */
public record MailRuleRequest(
        String name,
        MailRuleMatchMode matchMode,
        Boolean enabled,
        Boolean stopProcessing,
        List<MailRuleCondition> conditions,
        List<MailRuleAction> actions) {
}
