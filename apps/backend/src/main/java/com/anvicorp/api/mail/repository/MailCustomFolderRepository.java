package com.anvicorp.api.mail.repository;

import com.anvicorp.api.mail.entity.MailCustomFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MailCustomFolderRepository extends JpaRepository<MailCustomFolder, UUID> {

    List<MailCustomFolder> findByAccountIdOrderByNameAsc(UUID accountId);

    Optional<MailCustomFolder> findByIdAndAccountId(UUID id, UUID accountId);

    boolean existsByIdAndAccountId(UUID id, UUID accountId);

    boolean existsByAccountIdAndNameIgnoreCase(UUID accountId, String name);

    long countByAccountId(UUID accountId);
}
