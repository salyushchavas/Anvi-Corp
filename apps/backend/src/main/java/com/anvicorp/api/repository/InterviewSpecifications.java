package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.enums.InterviewStatus;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reusable JPA Specifications for the staff Interviews list. Mirrors
 * {@link ApplicationSpecifications} — composes search/filter predicates only
 * when a value is provided so null parameters never reach the SQL bind.
 *
 * Root cause this fixes: the old {@code @Query} used the
 * {@code (:param IS NULL OR col = :param)} pattern with a nullable
 * {@code Instant} (the scheduledAt window). When the cutoff was null,
 * Postgres returned {@code SQLSTATE 42P18 "could not determine data type of
 * parameter $7"} because Hibernate bound the null without an explicit type
 * Postgres could infer. Adding predicates only when the filter is present
 * removes that bind entirely.
 *
 * Fetch joins live on the data query only — the count query Spring Data
 * fires for pagination is detected by {@code query.getResultType() == Long.class}
 * and skips fetches (otherwise Hibernate emits HHH000104).
 */
public final class InterviewSpecifications {

    private InterviewSpecifications() {}

    public static Specification<Interview> withFilters(
            UUID applicationId,
            InterviewStatus status,
            UUID interviewerId,
            Instant upcomingCutoff,
            Instant pastCutoff) {
        return (root, query, cb) -> {
            boolean isCountQuery = query != null
                    && (query.getResultType() == Long.class
                            || query.getResultType() == long.class);

            // Set up the joins/fetches the toSummary mapper needs:
            // interview → application → candidate → user, plus jobPosting and interviewer.
            Join<Object, Object> applicationJoin;
            Join<Object, Object> interviewerJoin;

            if (!isCountQuery) {
                Fetch<Object, Object> aFetch = root.fetch("application", JoinType.INNER);
                Fetch<Object, Object> cFetch = aFetch.fetch("candidate", JoinType.INNER);
                cFetch.fetch("user", JoinType.INNER);
                aFetch.fetch("jobPosting", JoinType.LEFT);
                Fetch<Object, Object> iFetch = root.fetch("interviewer", JoinType.INNER);
                applicationJoin = (Join<Object, Object>) aFetch;
                interviewerJoin = (Join<Object, Object>) iFetch;
                if (query != null) query.distinct(true);
            } else {
                applicationJoin = root.join("application", JoinType.INNER);
                interviewerJoin = root.join("interviewer", JoinType.INNER);
            }

            List<Predicate> preds = new ArrayList<>();
            if (applicationId != null) {
                preds.add(cb.equal(applicationJoin.get("id"), applicationId));
            }
            if (status != null) {
                preds.add(cb.equal(root.get("status"), status));
            }
            if (interviewerId != null) {
                preds.add(cb.equal(interviewerJoin.get("id"), interviewerId));
            }
            if (upcomingCutoff != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("scheduledAt"), upcomingCutoff));
            }
            if (pastCutoff != null) {
                preds.add(cb.lessThan(root.get("scheduledAt"), pastCutoff));
            }

            return preds.isEmpty() ? cb.conjunction() : cb.and(preds.toArray(new Predicate[0]));
        };
    }
}
