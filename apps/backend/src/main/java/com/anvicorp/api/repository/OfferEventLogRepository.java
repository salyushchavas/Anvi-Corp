package com.anvicorp.api.repository;

import com.anvicorp.api.entity.OfferEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OfferEventLogRepository extends JpaRepository<OfferEventLog, UUID> {
    List<OfferEventLog> findByOfferIdOrderByCreatedAtDesc(UUID offerId);
}
