package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);

    List<Document> findByOwnerUserIdAndCategoryOrderByCreatedAtDesc(UUID ownerUserId, String category);

    /** Phase 4 vault listing — non-soft-deleted, newest first. */
    List<Document> findByOwnerUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID ownerUserId);

    /**
     * Phase B (volume → S3) migration finders. Discriminator on
     * {@code storage_key} shape: rows pointing at the volume can be
     * absolute ({@code /data/...}) OR relative ({@code ./uploads/...}
     * when {@code DOCUMENTS_STORAGE_PATH} is unset and the default
     * {@code app.documents.storage-path=./uploads/documents} kicks in).
     * The first revision of these finders only matched the absolute
     * shape; relative-path rows were silently skipped by the migration.
     */
    @org.springframework.data.jpa.repository.Query(
            "select d from Document d "
                    + "where d.storageKey like '/%' "
                    + "   or d.storageKey like './%' "
                    + "   or d.storageKey like '../%' "
                    + "   or d.storageKey like '\\\\%' "
                    + "   or substring(d.storageKey, 2, 1) = ':'")
    List<Document> findVolumeStored();

    @org.springframework.data.jpa.repository.Query(
            "select count(d) from Document d "
                    + "where d.storageKey like '/%' "
                    + "   or d.storageKey like './%' "
                    + "   or d.storageKey like '../%' "
                    + "   or d.storageKey like '\\\\%' "
                    + "   or substring(d.storageKey, 2, 1) = ':'")
    long countVolumeStored();
}
