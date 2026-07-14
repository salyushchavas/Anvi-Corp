package com.anvicorp.api.repository;

import com.anvicorp.api.entity.DocumentPacket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentPacketRepository
        extends JpaRepository<DocumentPacket, UUID> {

    @Query("SELECT p FROM DocumentPacket p "
            + "WHERE p.internLifecycleId = :lifecycleId "
            + "  AND p.status <> 'CANCELLED' "
            + "ORDER BY p.assignedAt DESC")
    Optional<DocumentPacket> findActiveByLifecycle(
            @Param("lifecycleId") UUID lifecycleId);

    List<DocumentPacket> findByInternLifecycleIdOrderByAssignedAtDesc(UUID lifecycleId);

    long countByStatus(String status);
}
