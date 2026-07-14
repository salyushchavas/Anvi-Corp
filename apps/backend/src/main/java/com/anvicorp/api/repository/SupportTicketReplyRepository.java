package com.anvicorp.api.repository;

import com.anvicorp.api.entity.SupportTicketReply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupportTicketReplyRepository extends JpaRepository<SupportTicketReply, UUID> {

    List<SupportTicketReply> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);
}
