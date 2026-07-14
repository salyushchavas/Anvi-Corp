package com.anvicorp.api.repository;

import com.anvicorp.api.entity.SupportTicket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {

    List<SupportTicket> findByOpenerUserIdOrderByUpdatedAtDesc(UUID openerUserId);

    Page<SupportTicket> findByStatusInOrderByCreatedAtDesc(
            Collection<String> statuses, Pageable pageable);
}
