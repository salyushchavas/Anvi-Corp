package com.anvicorp.api.mail.repository;

import com.anvicorp.api.mail.entity.MailAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MailAttachmentRepository extends JpaRepository<MailAttachment, UUID> {

    List<MailAttachment> findByMessageId(UUID messageId);

    long countByMessageId(UUID messageId);
}
