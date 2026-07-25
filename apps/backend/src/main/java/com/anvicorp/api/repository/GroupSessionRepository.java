package com.anvicorp.api.repository;

import com.anvicorp.api.entity.GroupSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GroupSessionRepository extends JpaRepository<GroupSession, UUID> {

    List<GroupSession> findByTrainerIdOrderByScheduledAtDesc(UUID trainerId);
}
