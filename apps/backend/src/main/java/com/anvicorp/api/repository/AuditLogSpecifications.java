package com.anvicorp.api.repository;

import com.anvicorp.api.entity.AuditLog;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * JPA Specifications for the admin audit log viewer. Same motivation as
 * {@link InterviewSpecifications}: nullable {@code Instant} bounds in the
 * previous {@code (:param IS NULL OR ...)} pattern surfaced Postgres
 * {@code SQLSTATE 42P18} ("could not determine data type of parameter $N")
 * because Hibernate bound the null without an explicit type Postgres could
 * infer. Building predicates only when the value is present keeps null
 * filters out of the SQL bind entirely.
 *
 * No fetch joins are needed — {@code AuditLog} has no relationships that
 * the DTO mapper reads through.
 */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {}

    public static Specification<AuditLog> withFilters(
            String action,
            Collection<UUID> userIds,
            String entityType,
            Instant from,
            Instant to) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (action != null && !action.isBlank()) {
                preds.add(cb.equal(root.get("action"), action));
            }
            if (userIds != null && !userIds.isEmpty()) {
                preds.add(root.get("userId").in(userIds));
            }
            if (entityType != null && !entityType.isBlank()) {
                preds.add(cb.equal(root.get("entityType"), entityType));
            }
            if (from != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
            }
            if (to != null) {
                preds.add(cb.lessThanOrEqualTo(root.get("timestamp"), to));
            }
            return preds.isEmpty() ? cb.conjunction() : cb.and(preds.toArray(new Predicate[0]));
        };
    }

    /**
     * Per-user audit feed (SUPER_ADMIN L3 supervision). Returns rows where the
     * user is the ACTOR OR the SUBJECT of the audited action. Subject coverage
     * resolves via three OR branches:
     * <ol>
     *   <li>{@code subject_user_id == userId} — explicit, populated by new writers</li>
     *   <li>{@code entity_type = 'User' AND entity_id = userId} — user-management
     *       events where the target IS the user</li>
     *   <li>{@code entity_type IN bucket-keys AND entity_id IN bucket-ids} —
     *       candidate-side derivation via {@link com.anvicorp.api.service.UserAuditEntityResolver}</li>
     * </ol>
     * Additional filters (action / from / to) AND on top of the user scope.
     */
    public static Specification<AuditLog> forUserAuditFeed(
            UUID userId,
            Map<String, Set<UUID>> entityIdsByType,
            String action,
            Instant from,
            Instant to) {
        return (root, query, cb) -> {
            // ── User-scope OR clause ────────────────────────────────────────
            List<Predicate> userScope = new ArrayList<>();
            userScope.add(cb.equal(root.get("userId"), userId));
            userScope.add(cb.equal(root.get("subjectUserId"), userId));
            userScope.add(cb.and(
                    cb.equal(root.get("entityType"), "User"),
                    cb.equal(root.get("entityId"), userId)));
            if (entityIdsByType != null) {
                for (Map.Entry<String, Set<UUID>> e : entityIdsByType.entrySet()) {
                    if (e.getValue() == null || e.getValue().isEmpty()) continue;
                    userScope.add(cb.and(
                            cb.equal(root.get("entityType"), e.getKey()),
                            root.get("entityId").in(e.getValue())));
                }
            }
            Predicate scope = cb.or(userScope.toArray(new Predicate[0]));

            // ── Optional filters AND'd on top ───────────────────────────────
            List<Predicate> ands = new ArrayList<>();
            ands.add(scope);
            if (action != null && !action.isBlank()) {
                ands.add(cb.equal(root.get("action"), action));
            }
            if (from != null) {
                ands.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
            }
            if (to != null) {
                ands.add(cb.lessThanOrEqualTo(root.get("timestamp"), to));
            }
            return cb.and(ands.toArray(new Predicate[0]));
        };
    }
}
