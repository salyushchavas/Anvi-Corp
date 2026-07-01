package com.anvicorp.api.mail.repository;

import com.anvicorp.api.mail.entity.MailRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-account rule access — every query is scoped by account_id (walling). The
 * enabled-only, priority-ordered query is the one the delivery engine uses.
 */
public interface MailRuleRepository extends JpaRepository<MailRule, UUID> {

    List<MailRule> findByAccountIdOrderByPriorityAscCreatedAtAsc(UUID accountId);

    List<MailRule> findByAccountIdAndEnabledTrueOrderByPriorityAscCreatedAtAsc(UUID accountId);

    Optional<MailRule> findByIdAndAccountId(UUID id, UUID accountId);

    long countByAccountId(UUID accountId);
}
