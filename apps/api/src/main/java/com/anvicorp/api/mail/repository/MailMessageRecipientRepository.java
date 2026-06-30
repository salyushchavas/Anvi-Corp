package com.anvicorp.api.mail.repository;

import com.anvicorp.api.mail.entity.MailMessageRecipient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MailMessageRecipientRepository extends JpaRepository<MailMessageRecipient, UUID> {

    List<MailMessageRecipient> findByMessageId(UUID messageId);

    List<MailMessageRecipient> findByMessageIdIn(Collection<UUID> messageIds);
}
