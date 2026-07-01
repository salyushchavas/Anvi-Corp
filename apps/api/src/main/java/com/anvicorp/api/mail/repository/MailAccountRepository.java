package com.anvicorp.api.mail.repository;

import com.anvicorp.api.mail.entity.MailAccount;
import com.anvicorp.api.mail.entity.MailAccountStatus;
import com.anvicorp.api.mail.entity.MailRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    /** Same-domain recipient resolution for walled send (A2). */
    Optional<MailAccount> findByLocalPartAndDomain_Id(String localPart, UUID domainId);

    // ── Admin / provisioning (A3) ─────────────────────────────────────────────
    boolean existsByLocalPartAndDomain_Id(String localPart, UUID domainId);

    List<MailAccount> findByDomain_IdOrderByLocalPartAsc(UUID domainId);

    List<MailAccount> findAllByOrderByLocalPartAsc();

    long countByDomain_Id(UUID domainId);

    /**
     * Pessimistic-write lock over accounts of a (role, status) — used by the
     * last-active-super-admin guard so concurrent demotions/suspensions of
     * different super-admins serialize and can't jointly orphan the role.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from MailAccount a where a.role = :role and a.status = :status")
    List<MailAccount> lockByRoleAndStatus(@Param("role") MailRole role,
                                          @Param("status") MailAccountStatus status);
}
