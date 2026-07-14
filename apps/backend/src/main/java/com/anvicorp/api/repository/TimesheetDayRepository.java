package com.anvicorp.api.repository;

import com.anvicorp.api.entity.TimesheetDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TimesheetDayRepository extends JpaRepository<TimesheetDay, UUID> {

    List<TimesheetDay> findByTimesheetIdOrderByDayOfWeekAsc(UUID timesheetId);

    Optional<TimesheetDay> findByTimesheetIdAndDayOfWeek(UUID timesheetId, DayOfWeek dayOfWeek);
}
