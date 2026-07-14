package com.anvicorp.api.repository;

import com.anvicorp.api.entity.ExitChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExitChecklistItemRepository
        extends JpaRepository<ExitChecklistItem, UUID> {

    List<ExitChecklistItem> findByExitRecordIdOrderByItemKeyAsc(UUID exitRecordId);

    Optional<ExitChecklistItem> findByExitRecordIdAndItemKey(
            UUID exitRecordId, String itemKey);

    long countByExitRecordIdAndStatus(UUID exitRecordId, String status);
}
