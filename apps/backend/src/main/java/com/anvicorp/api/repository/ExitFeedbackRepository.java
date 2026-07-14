package com.anvicorp.api.repository;

import com.anvicorp.api.entity.ExitFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExitFeedbackRepository extends JpaRepository<ExitFeedback, UUID> {

    Optional<ExitFeedback> findByExitRecordId(UUID exitRecordId);

    Optional<ExitFeedback> findByInternId(UUID internId);

    boolean existsByExitRecordId(UUID exitRecordId);
}
