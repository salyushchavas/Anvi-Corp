package com.anvicorp.api.repository;

import com.anvicorp.api.entity.DocumentTask;
import com.anvicorp.api.erm.documents.SkyzenDocument;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentTaskRepository
        extends JpaRepository<DocumentTask, UUID> {

    List<DocumentTask> findByPacketIdOrderByCreatedAtAsc(UUID packetId);

    /** ERM Phase 8.2 — replaces findByPacketIdAndTemplateId. */
    Optional<DocumentTask> findByPacketIdAndDocumentKey(
            UUID packetId, SkyzenDocument documentKey);

    long countByPacketIdAndStatus(UUID packetId, String status);

    long countByPacketIdAndStatusNotIn(UUID packetId, List<String> statuses);

    /** Used by reviewTask — SELECT FOR UPDATE so two ERMs can't race to
     *  flip the same task status. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM DocumentTask t WHERE t.id = :id")
    Optional<DocumentTask> findByIdForUpdate(@Param("id") UUID id);
}
