package com.anvicorp.api.repository;

import com.anvicorp.api.entity.ExceptionEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ExceptionEventLogRepository
        extends JpaRepository<ExceptionEventLog, UUID> {

    List<ExceptionEventLog> findByExceptionRecordIdOrderByCreatedAtAsc(UUID recordId);
}
