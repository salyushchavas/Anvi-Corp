package com.anvicorp.api.repository;

import com.anvicorp.api.entity.InternLifecycle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InternLifecycleRepository extends JpaRepository<InternLifecycle, UUID> {

    Optional<InternLifecycle> findByUserId(UUID userId);

    Optional<InternLifecycle> findByEmployeeId(String employeeId);

    boolean existsByUserId(UUID userId);

    /** Active interns for the weekly-sessions tracker. Filters by
     *  active_status='ACTIVE' so closed lifecycles don't pollute the
     *  grid. Trainer scoping happens in the service layer via
     *  TrainerScopeGuard (handles the null-trainer single-org fallback). */
    List<InternLifecycle> findByActiveStatusOrderByEmployeeIdAsc(String activeStatus);

    /** ERM Document Gallery — list every lifecycle row (active + past)
     *  ordered by employee id. Filtering by status happens in the service
     *  layer so the same listing can serve the ALL / ACTIVE / INACTIVE /
     *  PROSPECTIVE filter without N+1 queries. */
    List<InternLifecycle> findAllByOrderByEmployeeIdAsc();

    List<InternLifecycle> findByActiveStatusInOrderByEmployeeIdAsc(
            java.util.Collection<String> activeStatuses);
}
