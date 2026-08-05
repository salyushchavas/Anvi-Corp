package com.anvicorp.api.admin.editabletemplates;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EditableTemplateRepository extends JpaRepository<EditableTemplate, UUID> {

    Optional<EditableTemplate> findByKey(String key);

    boolean existsByKey(String key);

    List<EditableTemplate> findAllByOrderBySortOrderAscTitleAsc();
}
