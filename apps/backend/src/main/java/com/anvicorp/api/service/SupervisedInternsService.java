package com.anvicorp.api.service;

import com.anvicorp.api.dto.supervised.InternSummaryResponse;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.EngagementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 3 step 10 — supervised interns roster reads {@link Engagement} as the
 * source of truth. A candidate appears in the roster when they have an
 * engagement in PENDING_COMPLIANCE / READY_TO_START / ACTIVE / COMPLETED.
 * Legacy candidates with a HIRED application but no engagement yet are merged
 * in afterwards; the step-11 backfill will eliminate the second pass by
 * ensuring every legacy hire has a corresponding engagement row.
 */
@Service
@RequiredArgsConstructor
public class SupervisedInternsService {

    private static final List<EngagementStatus> ROSTER_STATUSES = List.of(
            EngagementStatus.PENDING_COMPLIANCE,
            EngagementStatus.READY_TO_START,
            EngagementStatus.ACTIVE,
            EngagementStatus.COMPLETED
    );

    private final ApplicationRepository applicationRepository;
    private final EngagementRepository engagementRepository;

    @Transactional(readOnly = true)
    public List<InternSummaryResponse> listHiredInterns(UUID entityId, String search) {
        String normalized = (search != null && !search.isBlank()) ? search.trim() : null;
        // Precompute the lowercase %wildcard% pattern in Java. Postgres 18's
        // type resolver can't infer `:param` as text inside
        // `LOWER(CONCAT('%', :param, '%'))` and falls back to bytea, which
        // makes `LOWER(...)` blow up (SQLSTATE 42883). Binding the pre-built
        // pattern directly to LIKE gives Postgres an unambiguous text type.
        String searchPattern = (normalized == null)
                ? null
                : "%" + normalized.toLowerCase() + "%";
        Set<UUID> seen = new HashSet<>();
        List<InternSummaryResponse> out = new ArrayList<>();

        // ── Engagement-first read (the new canonical path) ─────────────────
        List<Engagement> engagements = engagementRepository
                .findRosterByStatusIn(ROSTER_STATUSES, entityId, searchPattern);
        for (Engagement e : engagements) {
            Candidate c = e.getCandidate();
            if (c == null || !seen.add(c.getId())) continue;
            User u = c.getUser();
            JobPosting jp = e.getApplication() != null
                    ? e.getApplication().getJobPosting() : null;
            StaffingEntity ent = e.getEntity();
            User ae = c.getAssignedEvaluator();
            out.add(InternSummaryResponse.builder()
                    .candidateId(c.getId())
                    .name(u != null ? u.getFullName() : null)
                    .email(u != null ? u.getEmail() : null)
                    .position(jp != null ? jp.getTitle() : null)
                    .entityName(ent != null ? ent.getName() : null)
                    .hiredDate(toInstant(e.getActualStartDate()))
                    .assignedEvaluatorName(ae != null ? ae.getFullName() : null)
                    .build());
        }

        // ── Legacy fallback — Application.HIRED for candidates not yet on an
        // engagement. Removed by step-11 backfill.
        List<Application> legacy = applicationRepository.findHiredInterns(entityId, searchPattern);
        for (Application app : legacy) {
            Candidate c = app.getCandidate();
            if (c == null || !seen.add(c.getId())) continue;
            User u = c.getUser();
            JobPosting jp = app.getJobPosting();
            StaffingEntity ent = jp != null ? jp.getEntity() : null;
            User ae = c.getAssignedEvaluator();
            out.add(InternSummaryResponse.builder()
                    .candidateId(c.getId())
                    .name(u != null ? u.getFullName() : null)
                    .email(u != null ? u.getEmail() : null)
                    .position(jp != null ? jp.getTitle() : null)
                    .entityName(ent != null ? ent.getName() : null)
                    .hiredDate(app.getStatusUpdatedAt())
                    .assignedEvaluatorName(ae != null ? ae.getFullName() : null)
                    .build());
        }
        return out;
    }

    private static Instant toInstant(LocalDate date) {
        return date != null ? date.atStartOfDay(ZoneOffset.UTC).toInstant() : null;
    }
}
