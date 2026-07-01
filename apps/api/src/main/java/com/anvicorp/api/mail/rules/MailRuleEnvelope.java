package com.anvicorp.api.mail.rules;

import java.util.List;

/**
 * The inbound-message fields a rule matches against — built ONCE per message at
 * delivery from the sender + resolved recipient lists, then evaluated per recipient.
 * Addresses are lowercased email strings; {@code to}/{@code cc} exclude BCC (there
 * is no BCC condition field).
 */
public record MailRuleEnvelope(String from, List<String> to, List<String> cc, String subject, boolean hasAttachment) {
}
