package com.anvicorp.api.repository;

import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.enums.JobPostingStatus;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Filters for the admin Job Postings list. Composes optional search
 * (title or description), status, and entityId.
 *
 * No fetch joins here — the entity name needed for the response is rendered
 * by the service inside the same read-only transaction, and the entity
 * association is small (1 row per posting). Keeping the count query simple.
 */
public final class JobPostingSpecifications {

    private JobPostingSpecifications() {}

    public static Specification<JobPosting> withFilters(
            String search,
            JobPostingStatus status,
            UUID entityId) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase() + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("description")), like)));
            }
            if (status != null) {
                preds.add(cb.equal(root.get("status"), status));
            }
            if (entityId != null) {
                // LEFT JOIN — postings should normally have an entity, but
                // we don't want to silently filter out null-entity rows when
                // no entity filter was requested. The IN clause above handles
                // the actual narrowing.
                preds.add(cb.equal(
                        root.join("entity", JoinType.LEFT).get("id"),
                        entityId));
            }

            return preds.isEmpty() ? cb.conjunction() : cb.and(preds.toArray(new Predicate[0]));
        };
    }
}
