package com.anvicorp.api.mail.service;

import com.anvicorp.api.mail.auth.MailPrincipal;
import com.anvicorp.api.mail.dto.MailRuleRequest;
import com.anvicorp.api.mail.dto.MailRuleResponse;
import com.anvicorp.api.mail.entity.MailFolder;
import com.anvicorp.api.mail.entity.MailRule;
import com.anvicorp.api.mail.entity.MailRuleMatchMode;
import com.anvicorp.api.mail.exception.MailApiException;
import com.anvicorp.api.mail.repository.MailAccountRepository;
import com.anvicorp.api.mail.repository.MailCustomFolderRepository;
import com.anvicorp.api.mail.repository.MailRuleRepository;
import com.anvicorp.api.mail.rules.MailRuleAction;
import com.anvicorp.api.mail.rules.MailRuleCondition;
import com.anvicorp.api.mail.rules.MailRuleField;
import com.anvicorp.api.mail.rules.MailRuleOperator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Walled CRUD for per-account inbox rules (A7). Every op re-loads the actor and
 * scopes by accountId; a foreign rule id returns 404 (anti-enumeration). Save-time
 * validation requires ≥1 condition + ≥1 action, checks each condition/action shape,
 * and requires a MOVE_TO_CUSTOM_FOLDER target to be a folder the caller owns AT
 * SAVE TIME (400 otherwise) — the delivery engine still fails open if that folder
 * is later deleted. Conditions/actions are stored as Jackson JSON TEXT.
 */
@Service
@RequiredArgsConstructor
public class MailRuleService {

    private static final int MAX_RULES = 100;
    private static final int MAX_NAME = 150;
    private static final int MAX_CONDITIONS = 25;
    private static final int MAX_ACTIONS = 25;
    /** The only system folders a rule may move inbound mail into (SENT/DRAFTS are outbound-only). */
    private static final Set<MailFolder> INBOUND_TARGETS =
            EnumSet.of(MailFolder.INBOX, MailFolder.ARCHIVE, MailFolder.TRASH);

    private final MailRuleRepository ruleRepository;
    private final MailAccountRepository accountRepository;
    private final MailCustomFolderRepository customFolderRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<MailRuleResponse> list(MailPrincipal principal) {
        UUID actorId = loadActorId(principal);
        return ruleRepository.findByAccountIdOrderByPriorityAscCreatedAtAsc(actorId).stream()
                .map(this::toResponse).toList();
    }

    @Transactional
    public MailRuleResponse create(MailPrincipal principal, MailRuleRequest req) {
        UUID actorId = loadActorId(principal);
        Validated v = validate(actorId, req);
        if (ruleRepository.countByAccountId(actorId) >= MAX_RULES) {
            throw badRequest("Rule limit reached (" + MAX_RULES + ")", "MAIL_RULE_LIMIT");
        }
        int priority = (int) ruleRepository.countByAccountId(actorId); // append last
        MailRule rule = MailRule.builder()
                .accountId(actorId)
                .name(v.name())
                .priority(priority)
                .enabled(req.enabled() == null ? Boolean.TRUE : req.enabled())
                .matchMode(req.matchMode() == null ? MailRuleMatchMode.ALL : req.matchMode())
                .stopProcessing(Boolean.TRUE.equals(req.stopProcessing()))
                .conditionsJson(v.conditionsJson())
                .actionsJson(v.actionsJson())
                .build();
        return toResponse(ruleRepository.save(rule));
    }

    @Transactional
    public MailRuleResponse update(MailPrincipal principal, UUID id, MailRuleRequest req) {
        UUID actorId = loadActorId(principal);
        MailRule rule = load(actorId, id);
        Validated v = validate(actorId, req);
        rule.setName(v.name());
        if (req.enabled() != null) {
            rule.setEnabled(req.enabled());
        }
        rule.setMatchMode(req.matchMode() == null ? MailRuleMatchMode.ALL : req.matchMode());
        rule.setStopProcessing(Boolean.TRUE.equals(req.stopProcessing()));
        rule.setConditionsJson(v.conditionsJson());
        rule.setActionsJson(v.actionsJson());
        return toResponse(ruleRepository.save(rule));
    }

    @Transactional
    public void delete(MailPrincipal principal, UUID id) {
        UUID actorId = loadActorId(principal);
        ruleRepository.delete(load(actorId, id));
    }

    @Transactional
    public MailRuleResponse setEnabled(MailPrincipal principal, UUID id, Boolean enabled) {
        UUID actorId = loadActorId(principal);
        MailRule rule = load(actorId, id);
        rule.setEnabled(Boolean.TRUE.equals(enabled));
        return toResponse(ruleRepository.save(rule));
    }

    /** Reassigns contiguous priorities from the given full ordering of the caller's rules. */
    @Transactional
    public List<MailRuleResponse> reorder(MailPrincipal principal, List<String> ids) {
        UUID actorId = loadActorId(principal);
        if (ids == null || ids.isEmpty()) {
            throw badRequest("No order provided", "MAIL_RULE_REORDER_EMPTY");
        }
        List<MailRule> all = ruleRepository.findByAccountIdOrderByPriorityAscCreatedAtAsc(actorId);
        if (ids.size() != all.size()) {
            throw badRequest("Reorder must list every rule exactly once", "MAIL_RULE_REORDER_MISMATCH");
        }
        Map<UUID, MailRule> byId = all.stream().collect(Collectors.toMap(MailRule::getId, r -> r));
        Set<UUID> seen = new HashSet<>();
        int p = 0;
        for (String raw : ids) {
            UUID rid = parseId(raw);
            MailRule rule = byId.get(rid);
            if (rule == null || !seen.add(rid)) {
                // Foreign/unknown id, or a duplicate — 404 (anti-enumeration), never partial.
                throw new MailApiException(HttpStatus.NOT_FOUND, "Rule not found", "MAIL_NOT_FOUND");
            }
            rule.setPriority(p++);
        }
        ruleRepository.saveAll(byId.values());
        return ruleRepository.findByAccountIdOrderByPriorityAscCreatedAtAsc(actorId).stream()
                .map(this::toResponse).toList();
    }

    // ── Validation ───────────────────────────────────────────────────────────

    private Validated validate(UUID actorId, MailRuleRequest req) {
        if (req == null) {
            throw badRequest("Rule is required", "MAIL_RULE_INVALID");
        }
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            throw badRequest("Rule name is required", "MAIL_RULE_NAME_REQUIRED");
        }
        if (name.length() > MAX_NAME) {
            throw badRequest("Rule name is too long", "MAIL_RULE_NAME_TOO_LONG");
        }

        List<MailRuleCondition> conditions = req.conditions();
        if (conditions == null || conditions.isEmpty()) {
            throw badRequest("At least one condition is required", "MAIL_RULE_NO_CONDITIONS");
        }
        if (conditions.size() > MAX_CONDITIONS) {
            throw badRequest("Too many conditions", "MAIL_RULE_TOO_MANY_CONDITIONS");
        }
        for (MailRuleCondition c : conditions) {
            validateCondition(c);
        }

        List<MailRuleAction> actions = req.actions();
        if (actions == null || actions.isEmpty()) {
            throw badRequest("At least one action is required", "MAIL_RULE_NO_ACTIONS");
        }
        if (actions.size() > MAX_ACTIONS) {
            throw badRequest("Too many actions", "MAIL_RULE_TOO_MANY_ACTIONS");
        }
        for (MailRuleAction a : actions) {
            validateAction(actorId, a);
        }

        try {
            return new Validated(name,
                    objectMapper.writeValueAsString(conditions),
                    objectMapper.writeValueAsString(actions));
        } catch (JsonProcessingException e) {
            throw badRequest("Invalid rule payload", "MAIL_RULE_INVALID");
        }
    }

    private void validateCondition(MailRuleCondition c) {
        if (c == null || c.field() == null || c.operator() == null) {
            throw badRequest("Invalid condition", "MAIL_RULE_INVALID_CONDITION");
        }
        if (c.field() == MailRuleField.HAS_ATTACHMENT) {
            if (c.operator() != MailRuleOperator.IS_TRUE) {
                throw badRequest("HAS_ATTACHMENT supports IS_TRUE only", "MAIL_RULE_INVALID_CONDITION");
            }
        } else {
            if (c.operator() != MailRuleOperator.CONTAINS && c.operator() != MailRuleOperator.EQUALS) {
                throw badRequest("A text condition needs CONTAINS or EQUALS", "MAIL_RULE_INVALID_CONDITION");
            }
            if (c.value() == null || c.value().trim().isEmpty()) {
                throw badRequest("A condition value is required", "MAIL_RULE_INVALID_CONDITION");
            }
        }
    }

    private void validateAction(UUID actorId, MailRuleAction a) {
        if (a == null || a.type() == null) {
            throw badRequest("Invalid action", "MAIL_RULE_INVALID_ACTION");
        }
        switch (a.type()) {
            case MOVE_TO_SYSTEM_FOLDER -> {
                if (a.targetSystemFolder() == null) {
                    throw badRequest("A system-folder target is required", "MAIL_RULE_INVALID_ACTION");
                }
                if (!INBOUND_TARGETS.contains(a.targetSystemFolder())) {
                    // SENT/DRAFTS are outbound-only. A rule that placed a self-addressed sender's
                    // inbound entry there would collide with their own SENT/DRAFTS copy on the
                    // (account, message, folder) unique key and roll back the send — never a legal target.
                    throw badRequest("A rule can only move mail to Inbox, Archive or Trash", "MAIL_RULE_INVALID_TARGET");
                }
            }
            case MOVE_TO_CUSTOM_FOLDER -> {
                UUID target = parseId(a.targetCustomFolderId());
                if (!customFolderRepository.existsByIdAndAccountId(target, actorId)) {
                    throw badRequest("Custom-folder target not found", "MAIL_RULE_INVALID_TARGET");
                }
            }
            default -> {
                // MARK_READ / STAR / MARK_IMPORTANT / DELETE need no target.
            }
        }
    }

    // ── Mapping / helpers ─────────────────────────────────────────────────────

    private MailRuleResponse toResponse(MailRule rule) {
        List<MailRuleCondition> conditions;
        List<MailRuleAction> actions;
        try {
            conditions = objectMapper.readValue(rule.getConditionsJson(),
                    new TypeReference<List<MailRuleCondition>>() {});
            actions = objectMapper.readValue(rule.getActionsJson(),
                    new TypeReference<List<MailRuleAction>>() {});
        } catch (JsonProcessingException e) {
            // Tolerate a legacy/corrupt row in the listing rather than 500 the whole page.
            conditions = List.of();
            actions = List.of();
        }
        return new MailRuleResponse(
                rule.getId().toString(), rule.getName(), rule.getPriority(),
                Boolean.TRUE.equals(rule.getEnabled()), rule.getMatchMode(),
                Boolean.TRUE.equals(rule.getStopProcessing()), conditions, actions, rule.getCreatedAt());
    }

    private MailRule load(UUID actorId, UUID id) {
        return ruleRepository.findByIdAndAccountId(id, actorId)
                .orElseThrow(() -> new MailApiException(HttpStatus.NOT_FOUND, "Rule not found", "MAIL_NOT_FOUND"));
    }

    private UUID loadActorId(MailPrincipal principal) {
        if (principal == null) {
            throw new MailApiException(HttpStatus.UNAUTHORIZED, "Authentication required", "MAIL_UNAUTHENTICATED");
        }
        return accountRepository.findById(principal.accountId())
                .orElseThrow(() -> new MailApiException(
                        HttpStatus.UNAUTHORIZED, "Authentication required", "MAIL_UNAUTHENTICATED"))
                .getId();
    }

    private UUID parseId(String s) {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            throw badRequest("Invalid id", "MAIL_INVALID_ID");
        }
    }

    private static MailApiException badRequest(String msg, String code) {
        return new MailApiException(HttpStatus.BAD_REQUEST, msg, code);
    }

    private record Validated(String name, String conditionsJson, String actionsJson) {
    }
}
