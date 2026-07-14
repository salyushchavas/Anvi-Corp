package com.anvicorp.api.repository;

import com.anvicorp.api.entity.ExceptionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface ExceptionRecordRepository
        extends JpaRepository<ExceptionRecord, UUID> {

    Set<String> ACTIVE_STATUSES = Set.of("OPEN", "ASSIGNED", "IN_PROGRESS");

    /** UPSERT key — only one of these may exist per (intern, type). */
    @Query("SELECT r FROM ExceptionRecord r "
            + "WHERE r.subjectUserId = :subjectUserId "
            + "  AND r.exceptionType = :exceptionType "
            + "  AND r.status IN ('OPEN','ASSIGNED','IN_PROGRESS')")
    Optional<ExceptionRecord> findActiveBySubjectAndType(
            @Param("subjectUserId") UUID subjectUserId,
            @Param("exceptionType") String exceptionType);

    @Query("SELECT r FROM ExceptionRecord r "
            + "WHERE r.exceptionType = :exceptionType "
            + "  AND r.status IN ('OPEN','ASSIGNED','IN_PROGRESS') "
            + "  AND r.lastSeenAt < :cutoff")
    List<ExceptionRecord> findStaleActiveByType(
            @Param("exceptionType") String exceptionType,
            @Param("cutoff") Instant cutoff);

    @Query("SELECT r FROM ExceptionRecord r "
            + "WHERE r.subjectUserId = :userId "
            + "  AND r.status IN ('OPEN','ASSIGNED','IN_PROGRESS') "
            + "ORDER BY r.openedAt DESC")
    List<ExceptionRecord> findActiveBySubject(@Param("userId") UUID userId);
}
