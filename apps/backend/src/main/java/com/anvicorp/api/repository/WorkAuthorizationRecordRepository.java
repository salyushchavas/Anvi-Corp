package com.anvicorp.api.repository;

import com.anvicorp.api.entity.WorkAuthorizationRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkAuthorizationRecordRepository
        extends JpaRepository<WorkAuthorizationRecord, UUID> {

    Optional<WorkAuthorizationRecord> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);
}
