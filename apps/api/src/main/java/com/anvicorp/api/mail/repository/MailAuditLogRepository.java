package com.anvicorp.api.mail.repository;

import com.anvicorp.api.mail.entity.MailAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MailAuditLogRepository extends JpaRepository<MailAuditLog, UUID> {
}
