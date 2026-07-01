package com.anvicorp.api.mail.rules;

import com.anvicorp.api.mail.entity.MailFolder;
import com.anvicorp.api.mail.entity.MailRule;
import com.anvicorp.api.mail.entity.MailRuleMatchMode;
import com.anvicorp.api.mail.repository.MailCustomFolderRepository;
import com.anvicorp.api.mail.repository.MailRuleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Delivery-time inbox rules engine (A7). For one inbound recipient, resolves the
 * placement (a system folder OR an A6 custom-folder FK) + flags (read/star/
 * important) their enabled rules dictate — evaluated in priority order, honoring
 * the rule's ALL/ANY match mode, applying actions in order, and stopping at the
 * first matched rule that sets stop_processing.
 *
 * <p><b>FAIL-OPEN is the top invariant.</b> The whole evaluation is wrapped in a
 * catch-all: ANY error — malformed rule JSON, a missing/deleted/foreign custom-
 * folder target (A6 lets a user delete a folder a rule still points at), or any
 * other exception — abandons rule application and delivers the message to
 * INBOX/unread, logged. A rule NEVER drops, fails, or loses a message; correct
 * delivery always beats rule application. Nothing thrown here can reach (and thus
 * roll back) the caller's send transaction.</p>
 *
 * <p>Called ONLY for an inbound recipient's entry — never the sender's SENT copy.
 * Strictly per-account: it reads only the recipient's own rules and only accepts a
 * custom-folder target the recipient owns.</p>
 */
@Service
@RequiredArgsConstructor
public class MailRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(MailRuleEngine.class);

    private final MailRuleRepository ruleRepository;
    private final MailCustomFolderRepository customFolderRepository;
    private final ObjectMapper objectMapper;

    /** Resolve one recipient's inbound placement + flags. Never throws (fail-open). */
    public MailDeliveryOutcome resolveDelivery(UUID recipientAccountId, MailRuleEnvelope envelope) {
        try {
            return evaluate(recipientAccountId, envelope);
        } catch (Exception e) {
            log.warn("Inbox rule evaluation failed for account {} — delivering to INBOX/unread (fail-open)",
                    recipientAccountId, e);
            return MailDeliveryOutcome.inbox();
        }
    }

    private MailDeliveryOutcome evaluate(UUID recipientId, MailRuleEnvelope env) throws Exception {
        List<MailRule> rules =
                ruleRepository.findByAccountIdAndEnabledTrueOrderByPriorityAscCreatedAtAsc(recipientId);
        MailDeliveryOutcome outcome = MailDeliveryOutcome.inbox();
        for (MailRule rule : rules) {
            List<MailRuleCondition> conditions = objectMapper.readValue(
                    rule.getConditionsJson(), new TypeReference<List<MailRuleCondition>>() {});
            if (conditions == null || conditions.isEmpty()) {
                continue; // a rule with no conditions matches nothing (defensive)
            }
            if (!matches(rule.getMatchMode(), conditions, env)) {
                continue;
            }
            List<MailRuleAction> actions = objectMapper.readValue(
                    rule.getActionsJson(), new TypeReference<List<MailRuleAction>>() {});
            if (actions != null) {
                for (MailRuleAction action : actions) {
                    applyAction(recipientId, outcome, action);
                }
            }
            if (Boolean.TRUE.equals(rule.getStopProcessing())) {
                break;
            }
        }
        return outcome;
    }

    // ── Matching ─────────────────────────────────────────────────────────────

    private boolean matches(MailRuleMatchMode mode, List<MailRuleCondition> conditions, MailRuleEnvelope env) {
        boolean any = false;
        boolean all = true;
        for (MailRuleCondition c : conditions) {
            boolean m = matchesOne(c, env);
            any = any || m;
            all = all && m;
        }
        return mode == MailRuleMatchMode.ANY ? any : all;
    }

    private boolean matchesOne(MailRuleCondition c, MailRuleEnvelope env) {
        if (c == null || c.field() == null || c.operator() == null) {
            return false;
        }
        return switch (c.field()) {
            case HAS_ATTACHMENT -> c.operator() == MailRuleOperator.IS_TRUE && env.hasAttachment();
            case FROM -> textMatch(c, env.from());
            case SUBJECT -> textMatch(c, env.subject());
            case TO -> anyTextMatch(c, env.to());
            case CC -> anyTextMatch(c, env.cc());
        };
    }

    private boolean textMatch(MailRuleCondition c, String haystack) {
        if (haystack == null) {
            return false;
        }
        String needle = c.value() == null ? "" : c.value().trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return false; // an empty needle never matches (a text condition needs a value)
        }
        String hay = haystack.toLowerCase(Locale.ROOT);
        return switch (c.operator()) {
            case CONTAINS -> hay.contains(needle);
            case EQUALS -> hay.equals(needle);
            case IS_TRUE -> false; // IS_TRUE is only meaningful for HAS_ATTACHMENT
        };
    }

    private boolean anyTextMatch(MailRuleCondition c, List<String> haystacks) {
        if (haystacks == null) {
            return false;
        }
        for (String h : haystacks) {
            if (textMatch(c, h)) {
                return true;
            }
        }
        return false;
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private void applyAction(UUID recipientId, MailDeliveryOutcome o, MailRuleAction action) {
        if (action == null || action.type() == null) {
            return;
        }
        switch (action.type()) {
            case MARK_READ -> o.setRead(true);
            case STAR -> o.setStarred(true);
            case MARK_IMPORTANT -> o.setImportant(true);
            case DELETE -> {
                o.setFolder(MailFolder.TRASH);
                o.setCustomFolderId(null);
            }
            case MOVE_TO_SYSTEM_FOLDER -> {
                MailFolder target = action.targetSystemFolder();
                if (target == null) {
                    throw new IllegalStateException("MOVE_TO_SYSTEM_FOLDER without a target");
                }
                // Defensive clamp: only INBOX/ARCHIVE/TRASH are valid inbound placements. Placing a
                // self-addressed sender's inbound entry into SENT/DRAFTS would collide with their own
                // outbound copy on the (account, message, folder) unique key and — thrown from the
                // caller's save, OUTSIDE this engine's catch — roll back the send. Save-time validation
                // blocks new such rules; ignore a legacy/corrupt row here rather than emit a bad placement.
                if (target != MailFolder.SENT && target != MailFolder.DRAFTS) {
                    o.setFolder(target);
                    o.setCustomFolderId(null); // A6 precedence: a system move clears custom placement
                }
            }
            case MOVE_TO_CUSTOM_FOLDER -> {
                // null/garbage id → thrown → outer catch → fail-open to INBOX/unread.
                UUID target = UUID.fromString(action.targetCustomFolderId());
                if (!customFolderRepository.existsByIdAndAccountId(target, recipientId)) {
                    // Dangling/foreign target (e.g. the folder was deleted after the rule
                    // was saved). Tolerate it at eval time — fail-open, never drop.
                    throw new IllegalStateException("custom-folder target missing/foreign → fail-open");
                }
                o.setCustomFolderId(target);
                if (o.getFolder() == MailFolder.TRASH) {
                    // A6 precedence: keep a custom-foldered entry out of TRASH so it stays searchable.
                    o.setFolder(MailFolder.INBOX);
                }
            }
        }
    }
}
