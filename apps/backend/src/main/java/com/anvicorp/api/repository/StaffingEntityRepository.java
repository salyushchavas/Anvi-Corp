package com.anvicorp.api.repository;

import com.anvicorp.api.entity.StaffingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffingEntityRepository extends JpaRepository<StaffingEntity, UUID> {
    Optional<StaffingEntity> findByName(String name);
}
