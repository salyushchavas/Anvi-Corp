package com.anvicorp.api.repository;

import com.anvicorp.api.entity.GroupSession;
import com.anvicorp.api.entity.GroupSessionParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GroupSessionParticipantRepository
        extends JpaRepository<GroupSessionParticipant, UUID> {

    List<GroupSessionParticipant> findByInternUserIdOrderByCreatedAtDesc(UUID internUserId);

    List<GroupSessionParticipant> findByGroupSession(GroupSession groupSession);
}
