package com.anvicorp.api.admin.onboardingtemplates;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OnboardingDocumentTemplateRepository
        extends JpaRepository<OnboardingDocumentTemplate, UUID> {

    Optional<OnboardingDocumentTemplate> findByKey(String key);

    List<OnboardingDocumentTemplate> findByActiveTrueOrderBySortOrderAscTitleAsc();

    List<OnboardingDocumentTemplate> findAllByOrderBySortOrderAscTitleAsc();

    boolean existsByKey(String key);
}
