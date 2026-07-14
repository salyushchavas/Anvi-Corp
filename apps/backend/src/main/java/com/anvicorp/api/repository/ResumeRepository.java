package com.anvicorp.api.repository;

import com.anvicorp.api.entity.Resume;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResumeRepository extends JpaRepository<Resume, UUID> {
    List<Resume> findByCandidateId(UUID candidateId);
    long countByCandidateId(UUID candidateId);

    @Modifying
    @Query("UPDATE Resume r SET r.isDefault = false " +
            "WHERE r.candidate.id = :candidateId AND r.id <> :resumeId")
    void clearDefaultForOtherResumes(@Param("candidateId") UUID candidateId,
                                     @Param("resumeId") UUID resumeId);

    @Query("select r from Resume r " +
            "join fetch r.candidate c " +
            "join fetch c.user u " +
            "where r.id = :id")
    Optional<Resume> findByIdWithCandidateUser(@Param("id") UUID id);

    /**
     * Phase B (volume → S3) migration finders. Discriminator on
     * {@code file_path}: volume paths can be absolute ({@code /data/...})
     * or relative ({@code ./uploads/...} when {@code RESUME_STORAGE_PATH}
     * is unset). The first revision matched only the absolute shape,
     * silently skipping the relative-path rows.
     */
    @Query("select r from Resume r join fetch r.candidate c join fetch c.user u "
            + "where r.filePath like '/%' "
            + "   or r.filePath like './%' "
            + "   or r.filePath like '../%' "
            + "   or r.filePath like '\\\\%' "
            + "   or substring(r.filePath, 2, 1) = ':'")
    List<Resume> findVolumeStoredWithCandidateUser();

    @Query("select count(r) from Resume r "
            + "where r.filePath like '/%' "
            + "   or r.filePath like './%' "
            + "   or r.filePath like '../%' "
            + "   or r.filePath like '\\\\%' "
            + "   or substring(r.filePath, 2, 1) = ':'")
    long countVolumeStored();
}
