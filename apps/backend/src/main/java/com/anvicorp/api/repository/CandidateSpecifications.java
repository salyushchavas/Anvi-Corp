package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Candidate;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

/**
 * Reusable Specifications for the staff Candidates list. Matches the pattern
 * used by {@link ApplicationSpecifications} / {@link JobPostingSpecifications}:
 * fetch joins on the data query only (skipped on the count query so Spring's
 * total-elements computation doesn't trip HHH000104 or fail to render). The
 * older {@code searchWithUser} JPQL query — which lazy-loaded {@code c.user}
 * in the count query and used a brittle {@code :search IS NULL} predicate —
 * is replaced by this composer.
 */
public final class CandidateSpecifications {

    private CandidateSpecifications() {}

    public static Specification<Candidate> nameOrEmailMatches(String search) {
        return (root, query, cb) -> {
            boolean isCountQuery = query != null
                    && (query.getResultType() == Long.class
                            || query.getResultType() == long.class);

            Join<Object, Object> userJoin;
            if (!isCountQuery) {
                // Data query: pull the user via JOIN FETCH so the DTO mapper
                // reads name/email/phone without triggering lazy load.
                Fetch<Object, Object> uFetch = root.fetch("user", JoinType.INNER);
                userJoin = (Join<Object, Object>) uFetch;
            } else {
                // Count query: plain join for predicate evaluation only.
                userJoin = root.join("user", JoinType.INNER);
            }

            if (search == null || search.isBlank()) {
                return cb.conjunction();
            }
            String like = "%" + search.trim().toLowerCase() + "%";
            Predicate fullNameMatch = cb.like(cb.lower(userJoin.get("fullName")), like);
            Predicate emailMatch = cb.like(cb.lower(userJoin.get("email")), like);
            return cb.or(fullNameMatch, emailMatch);
        };
    }
}
