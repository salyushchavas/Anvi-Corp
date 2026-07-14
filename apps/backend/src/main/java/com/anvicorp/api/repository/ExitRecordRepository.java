package com.anvicorp.api.repository;

import com.anvicorp.api.entity.ExitRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExitRecordRepository extends JpaRepository<ExitRecord, UUID> {

    Optional<ExitRecord> findByInternLifecycleId(UUID internLifecycleId);

    Optional<ExitRecord> findByInternId(UUID internId);

    boolean existsByInternLifecycleId(UUID internLifecycleId);

    Page<ExitRecord> findAllByOrderByExitDateDescCreatedAtDesc(Pageable pageable);

    Page<ExitRecord> findByExitTypeOrderByExitDateDescCreatedAtDesc(
            String exitType, Pageable pageable);

    Page<ExitRecord> findByExitDateBetweenOrderByExitDateDescCreatedAtDesc(
            LocalDate from, LocalDate to, Pageable pageable);

    Page<ExitRecord> findByExitTypeAndExitDateBetweenOrderByExitDateDescCreatedAtDesc(
            String exitType, LocalDate from, LocalDate to, Pageable pageable);
}
