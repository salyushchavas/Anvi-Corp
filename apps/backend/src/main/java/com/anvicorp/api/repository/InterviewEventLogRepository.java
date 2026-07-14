package com.anvicorp.api.repository;

import com.anvicorp.api.entity.InterviewEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InterviewEventLogRepository
        extends JpaRepository<InterviewEventLog, UUID> {

    List<InterviewEventLog> findByInterviewIdOrderByCreatedAtDesc(UUID interviewId);
}
