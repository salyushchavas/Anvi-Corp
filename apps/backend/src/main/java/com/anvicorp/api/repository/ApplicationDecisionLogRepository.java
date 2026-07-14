package com.anvicorp.api.repository;

import com.anvicorp.api.entity.ApplicationDecisionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ApplicationDecisionLogRepository
        extends JpaRepository<ApplicationDecisionLog, UUID> {

    List<ApplicationDecisionLog> findByApplicationIdOrderByDecidedAtDesc(UUID applicationId);
}
