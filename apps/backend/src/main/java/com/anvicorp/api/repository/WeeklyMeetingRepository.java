package com.anvicorp.api.repository;

import com.anvicorp.api.entity.WeeklyMeeting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WeeklyMeetingRepository extends JpaRepository<WeeklyMeeting, UUID> {

    List<WeeklyMeeting> findByInternLifecycleIdOrderByScheduledForAsc(UUID internLifecycleId);

    List<WeeklyMeeting> findByHostUserIdOrderByScheduledForDesc(UUID hostUserId);

    List<WeeklyMeeting> findByRecurrenceParentIdOrderByScheduledForAsc(UUID parentId);

    Optional<WeeklyMeeting> findFirstByZoomMeetingId(String zoomMeetingId);

    /** Date-range scan used by the weekly-sessions tracker so the
     *  per-month query stays bounded instead of pulling every meeting
     *  the trainer has ever hosted. */
    List<WeeklyMeeting> findByInternLifecycleIdInAndScheduledForBetween(
            Collection<UUID> internLifecycleIds, Instant fromInclusive, Instant toExclusive);
}
