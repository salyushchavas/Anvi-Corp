package com.anvicorp.api.mail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One per-account inbound rule (A7). Conditions/actions are stored as JSON TEXT
 * (parsed via Jackson at evaluation), NOT a DB json type and NOT child tables —
 * an additive {@code mail_rules} table alongside A2/A6. Rules are evaluated ONLY
 * at inbound delivery (the recipient's INBOX entry), never the sender's SENT copy,
 * and are strictly per-account: only the recipient's own enabled rules affect
 * their delivery. Evaluation is FAIL-OPEN — see {@code MailRuleEngine}.
 */
@Entity
@Table(name = "mail_rules",
        indexes = {
                @Index(name = "idx_mail_rule_account", columnList = "account_id"),
                @Index(name = "idx_mail_rule_account_priority", columnList = "account_id, priority")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailRule {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 150)
    private String name;

    /** Evaluation order within the account (ascending); assigned/compacted on reorder. */
    @Column(nullable = false)
    @Builder.Default
    private Integer priority = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = Boolean.TRUE;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_mode", nullable = false, length = 8)
    @Builder.Default
    private MailRuleMatchMode matchMode = MailRuleMatchMode.ALL;

    /** When a matched rule sets this, later (lower-priority) rules are skipped. */
    @Column(name = "stop_processing", nullable = false)
    @Builder.Default
    private Boolean stopProcessing = Boolean.FALSE;

    /** JSON array of {field, operator, value} — parsed by Jackson at eval time. */
    @Column(name = "conditions_json", nullable = false, columnDefinition = "TEXT")
    private String conditionsJson;

    /** JSON array of {type, targetSystemFolder?, targetCustomFolderId?}. */
    @Column(name = "actions_json", nullable = false, columnDefinition = "TEXT")
    private String actionsJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
