package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Application;
import com.anvicorp.api.enums.ApplicationStatus;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Reusable JPA Specifications for the staff Applications list. Composes
 * search + multi-status + entity + jobPosting filters in a single query.
 *
 * Important: fetch joins are added ONLY on the data query (not the count
 * query). The count query is fired by Spring Data when computing
 * {@code totalElements}; adding fetch joins there would force HHH000104
 * warnings about firstResult/maxResults with collection fetch — and silently
 * break paging. Hibernate identifies the count query by
 * {@code query.getResultType() == Long.class}.
 */
public final class ApplicationSpecifications {

    private ApplicationSpecifications() {}

    public static Specification<Application> withFilters(
            String search,
            Collection<ApplicationStatus> statuses,
            UUID entityId,
            UUID jobPostingId) {
        return (root, query, cb) -> {
            boolean isCountQuery = query != null
                    && (query.getResultType() == Long.class
                            || query.getResultType() == long.class);

            // Join paths used by filters AND fetched on the data query.
            // We construct them as fetches when on the data query (so SELECT
            // pulls the columns) and cast to Join for predicate building; on
            // the count query we just create plain joins.
            Join<Object, Object> candidateJoin;
            Join<Object, Object> userJoin;
            Join<Object, Object> jobPostingJoin;
            Join<Object, Object> entityJoin;

            if (!isCountQuery) {
                Fetch<Object, Object> cFetch = root.fetch("candidate", JoinType.INNER);
                Fetch<Object, Object> uFetch = cFetch.fetch("user", JoinType.INNER);
                Fetch<Object, Object> jpFetch = root.fetch("jobPosting", JoinType.LEFT);
                Fetch<Object, Object> eFetch = jpFetch.fetch("entity", JoinType.LEFT);
                root.fetch("resume", JoinType.LEFT);
                candidateJoin = (Join<Object, Object>) cFetch;
                userJoin = (Join<Object, Object>) uFetch;
                jobPostingJoin = (Join<Object, Object>) jpFetch;
                entityJoin = (Join<Object, Object>) eFetch;
                if (query != null) query.distinct(true);
            } else {
                candidateJoin = root.join("candidate", JoinType.INNER);
                userJoin = candidateJoin.join("user", JoinType.INNER);
                jobPostingJoin = root.join("jobPosting", JoinType.LEFT);
                entityJoin = jobPostingJoin.join("entity", JoinType.LEFT);
            }

            List<Predicate> preds = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase() + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(userJoin.get("fullName")), like),
                        cb.like(cb.lower(userJoin.get("email")), like)));
            }
            if (statuses != null && !statuses.isEmpty()) {
                preds.add(root.get("status").in(statuses));
            }
            if (entityId != null) {
                preds.add(cb.equal(entityJoin.get("id"), entityId));
            }
            if (jobPostingId != null) {
                preds.add(cb.equal(jobPostingJoin.get("id"), jobPostingId));
            }

            return preds.isEmpty() ? cb.conjunction() : cb.and(preds.toArray(new Predicate[0]));
        };
    }
}
