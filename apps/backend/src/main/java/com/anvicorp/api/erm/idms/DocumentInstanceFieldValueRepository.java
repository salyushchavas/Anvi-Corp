package com.anvicorp.api.erm.idms;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentInstanceFieldValueRepository
        extends JpaRepository<DocumentInstanceFieldValue, UUID> {

    List<DocumentInstanceFieldValue> findByInstanceId(UUID instanceId);

    Optional<DocumentInstanceFieldValue> findByInstanceIdAndFieldId(UUID instanceId, String fieldId);

    void deleteByInstanceId(UUID instanceId);
}
