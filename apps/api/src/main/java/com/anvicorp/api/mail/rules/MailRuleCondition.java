package com.anvicorp.api.mail.rules;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single condition {@code {field, operator, value}}, serialized inside a rule's
 * conditions_json. {@code value} is ignored for the HAS_ATTACHMENT/IS_TRUE case.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MailRuleCondition(MailRuleField field, MailRuleOperator operator, String value) {
}
