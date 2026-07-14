package com.anvicorp.api.mail.dto;

import java.util.List;

/** New priority order: the caller's rule ids, first = highest priority. */
public record MailRuleReorderRequest(List<String> ids) {
}
