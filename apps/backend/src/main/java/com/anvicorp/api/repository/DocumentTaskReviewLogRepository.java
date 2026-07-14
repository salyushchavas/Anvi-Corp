package com.anvicorp.api.repository;

import com.anvicorp.api.entity.DocumentTaskReviewLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentTaskReviewLogRepository
        extends JpaRepository<DocumentTaskReviewLog, UUID> {

    List<DocumentTaskReviewLog> findByTaskIdOrderByCreatedAtAsc(UUID taskId);
}
