package com.anvicorp.api.mail.repository;

import com.anvicorp.api.mail.entity.MailDomain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MailDomainRepository extends JpaRepository<MailDomain, UUID> {

    Optional<MailDomain> findByName(String name);

    boolean existsByName(String name);
}
