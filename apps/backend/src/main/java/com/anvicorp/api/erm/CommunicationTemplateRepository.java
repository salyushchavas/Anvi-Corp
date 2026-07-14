package com.anvicorp.api.erm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommunicationTemplateRepository
        extends JpaRepository<CommunicationTemplate, UUID> {

    Optional<CommunicationTemplate> findByKeyAndChannel(String key, String channel);

    List<CommunicationTemplate> findByActiveTrueOrderByKeyAsc();

    boolean existsByKeyAndChannel(String key, String channel);
}
