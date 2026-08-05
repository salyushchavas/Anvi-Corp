package com.anvicorp.api.erm.idms;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentInstanceReviewLogRepository
        extends JpaRepository<DocumentInstanceReviewLog, UUID> {

    List<DocumentInstanceReviewLog> findByInstanceIdOrderByCreatedAtAsc(UUID instanceId);
}
