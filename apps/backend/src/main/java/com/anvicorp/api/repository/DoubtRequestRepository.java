package com.anvicorp.api.repository;

import com.anvicorp.api.entity.DoubtRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DoubtRequestRepository extends JpaRepository<DoubtRequest, UUID> {

    /** Intern's own doubts — newest first for the "My Doubts" page. */
    List<DoubtRequest> findByInternUserIdOrderByCreatedAtDesc(UUID internUserId);

    /** Trainer queue — every doubt routed to a given trainer, newest first. */
    List<DoubtRequest> findByTrainerUserIdOrderByCreatedAtDesc(UUID trainerUserId);

    /** Trainer queue, status-filtered (e.g. only PENDING / SESSION_SCHEDULED). */
    List<DoubtRequest> findByTrainerUserIdAndStatusInOrderByCreatedAtDesc(
            UUID trainerUserId, List<String> statuses);

    /** Lookup by Zoom meeting id — used by MeetingHostKeyController so
     *  the trainer can refetch a fresh host-start url. */
    Optional<DoubtRequest> findFirstByZoomMeetingId(String zoomMeetingId);

    /** Project-scoped — the intern's project page shows doubts they've
     *  raised about this specific project. */
    List<DoubtRequest> findByInternUserIdAndProjectIdOrderByCreatedAtDesc(
            UUID internUserId, UUID projectId);
}
