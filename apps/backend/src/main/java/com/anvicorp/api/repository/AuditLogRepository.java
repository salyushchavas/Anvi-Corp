package com.anvicorp.api.repository;

import com.anvicorp.api.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>,
        JpaSpecificationExecutor<AuditLog> {

    List<AuditLog> findByEntityTypeAndEntityIdOrderByTimestampDesc(
            String entityType, UUID entityId);

    /** Most recent audit entries across all entity types — used by Compliance Overview. */
    List<AuditLog> findTop25ByOrderByTimestampDesc();

    // The admin audit-log search was rewritten as a JPA Specification — see
    // {@link AuditLogSpecifications#withFilters}. Reason: the previous
    // {@code @Query} bound nullable {@code Instant} params via
    // {@code (:param IS NULL OR ...)}, surfacing Postgres SQLSTATE 42P18
    // ("could not determine data type of parameter"). Callers use
    // {@link org.springframework.data.jpa.repository.JpaSpecificationExecutor#findAll(Specification, Pageable)}.

    /** Distinct {@code action} values present in the log, for the filter dropdown. */
    @Query("SELECT DISTINCT a.action FROM AuditLog a ORDER BY a.action ASC")
    List<String> findDistinctActions();

    /**
     * Distinct {@code entityType} values present in the log — feeds the
     * super-admin audit viewer's target-entity filter dropdown. Null /
     * empty values are filtered out at the JPQL level.
     */
    @Query("SELECT DISTINCT a.entityType FROM AuditLog a " +
            "WHERE a.entityType IS NOT NULL AND a.entityType <> '' " +
            "ORDER BY a.entityType ASC")
    List<String> findDistinctEntityTypes();

    /**
     * Recent audit entries restricted to a set of entity ids — used by the
     * candidate dashboard to assemble "recent activity" across the caller's
     * own applications/offers/interviews without leaking other users' rows.
     * Pass {@code Pageable.ofSize(N)} to cap the result count. Returns an
     * empty list when {@code entityIds} is empty (handled by the caller).
     */
    @Query("SELECT a FROM AuditLog a " +
            "WHERE a.entityType = :entityType " +
            "AND a.entityId IN :entityIds " +
            "ORDER BY a.timestamp DESC")
    List<AuditLog> findRecentForEntityIds(@Param("entityType") String entityType,
                                          @Param("entityIds") Collection<UUID> entityIds,
                                          Pageable pageable);
}
