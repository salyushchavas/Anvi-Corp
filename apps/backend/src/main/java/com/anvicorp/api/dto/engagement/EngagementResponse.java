package com.anvicorp.api.dto.engagement;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.WorkAuthTrack;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 3 step 9 — Engagement read DTO. Exposes the engagement's state +
 * dates + placement to the HR / staff UI, plus the per-track compliance
 * requirements list (missing items only) so the dashboard can render a
 * blocker block without an extra round-trip.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EngagementResponse {
    UUID id;
    UUID applicationId;
    UUID candidateId;
    UUID offerId;
    UUID entityId;
    String candidateName;
    String candidateEmail;
    String entityName;
    String jobPostingTitle;
    WorkAuthTrack track;
    EngagementStatus status;
    LocalDate plannedStartDate;
    LocalDate plannedEndDate;
    LocalDate actualStartDate;
    LocalDate actualEndDate;
    UUID supervisorId;
    String supervisorName;
    String worksite;
    Integer hoursPerWeek;
    /** Per-track compliance items still pending; empty when ready. */
    List<String> missingRequirements;
    /** True iff {@link #missingRequirements} is empty. */
    boolean readyToStart;
    Instant createdAt;
    Instant updatedAt;
}
