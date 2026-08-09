package com.anvicorp.api.erm.idms;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentInstanceRepository
        extends JpaRepository<DocumentInstance, UUID> {

    /** SELECT FOR UPDATE — used by every state-transition path so two
     *  concurrent writes can't race through the same guard check. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT di FROM DocumentInstance di WHERE di.id = :id")
    Optional<DocumentInstance> findByIdForUpdate(@Param("id") UUID id);

    List<DocumentInstance> findByInternLifecycleIdOrderByCreatedAtDesc(UUID internLifecycleId);

    List<DocumentInstance> findByInternUserIdOrderByCreatedAtDesc(UUID internUserId);

    /** Every instance the ERM cockpit's queue needs — includes non-terminal
     *  live rows plus recent terminal rows for context. */
    @Query("SELECT di FROM DocumentInstance di "
            + "WHERE di.status IN :statuses "
            + "ORDER BY di.updatedAt DESC")
    List<DocumentInstance> findByStatusIn(@Param("statuses") java.util.Collection<DocumentInstanceStatus> statuses);

    /** For the intern's Agreements list — everything except VOIDED. */
    @Query("SELECT di FROM DocumentInstance di "
            + "WHERE di.internUserId = :userId AND di.status <> 'VOIDED' "
            + "ORDER BY di.updatedAt DESC")
    List<DocumentInstance> findVisibleForIntern(@Param("userId") UUID userId);

    /** Prior FINALIZED instances of the same template for a lifecycle — used
     *  by the supersede-ask on create. */
    List<DocumentInstance> findByInternLifecycleIdAndTemplateIdAndStatus(
            UUID internLifecycleId, UUID templateId, DocumentInstanceStatus status);

    /**
     * Pipeline-restore — offer-family instances for an intern, newest first.
     *
     * <p>"Offer-family" is any template whose key contains {@code OFFER_}
     * as a substring. Admin-derived template keys uppercase the title and
     * substitute non-alphanumerics with underscores
     * ({@code EditableTemplateAdminService.deriveKey}); a title like
     * "H1 Offer Letter" becomes {@code H1_OFFER_LETTER} and a strict
     * {@code startsWith('OFFER_')} filter would hide it from the intern.
     * The substring form catches every legitimate offer variant the
     * studio produces ({@code OFFER_LETTER}, {@code OFFER_LETTER_V2},
     * {@code H1_OFFER_LETTER}, {@code OFFER_LETTER_SOFTWARE_DEV}, …)
     * without matching unrelated keys — no seeded/derived key has
     * {@code OFFER_} in the middle by coincidence.</p>
     *
     * <p>VOIDED rows are excluded because a voided instance never had a
     * real offer effect. The caller inspects the head of the list to
     * decide "current" (usually the latest non-{@code SUPERSEDED} row);
     * the result-set is small (typically 0-2 rows per intern) so no
     * separate "latest" query is warranted.</p>
     */
    @Query("SELECT di FROM DocumentInstance di "
            + "WHERE di.internUserId = :userId "
            + "  AND di.templateKey LIKE '%OFFER\\_%' ESCAPE '\\' "
            + "  AND di.status <> 'VOIDED' "
            + "ORDER BY di.createdAt DESC")
    List<DocumentInstance> findOfferFamilyForIntern(@Param("userId") UUID userId);
}
