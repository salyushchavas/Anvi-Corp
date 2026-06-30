package com.anvicorp.api.mail.repository;

import com.anvicorp.api.mail.entity.MailAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MailAccountRepository extends JpaRepository<MailAccount, UUID> {

    /** Lookup by local part + domain name regardless of domain active state (seeder idempotency). */
    Optional<MailAccount> findByLocalPartAndDomain_Name(String localPart, String domainName);

    /** Login lookup — only matches accounts on an ACTIVE domain. */
    @Query("select a from MailAccount a where a.localPart = :localPart "
            + "and a.domain.name = :domainName and a.domain.active = true")
    Optional<MailAccount> findActiveByLocalPartAndDomainName(@Param("localPart") String localPart,
                                                             @Param("domainName") String domainName);

    /** Same-domain recipient resolution for walled send (A2): look up a local
     *  part within the SENDER's own domain only. */
    Optional<MailAccount> findByLocalPartAndDomain_Id(String localPart, UUID domainId);
}
