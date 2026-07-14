package com.anvicorp.api.service;

import com.anvicorp.api.application.ApplicationLifecycle;
import com.anvicorp.api.dto.candidate.ApplicationJourneyResponse;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.InterviewRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.ScreeningRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Powers the My Applications journey view. Pulls applications + their related
 * Interview / Offer / AuditLog rows in one read-only transaction, all through
 * fetch-joined repository methods — no lazy access in the mapper.
 *
 * Stage map (mirrors {@link CandidateDashboardService}):
 *   APPLIED→0, SHORTLISTED→1, INTERVIEW_SCHEDULED|INTERVIEWED→2,
 *   OFFERED|ACCEPTED→3, ONBOARDING|ACTIVE|HIRED|COMPLETED→4.
 * Exit statuses (REJECTED|WITHDRAWN|LAPSED|NO_SHOW) flip isExited=true with
 * stageIndex=-1. We never fabricate dates for stages we can't pin down.
 */
@Service
@RequiredArgsConstructor
public class CandidateApplicationsService {

    /** Audit log fetch is bounded — even very chatty candidates won't exceed this. */
    private static final int AUDIT_FETCH_CAP = 200;

    private final ApplicationRepository applicationRepository;
    private final InterviewRepository interviewRepository;
    private final OfferRepository offerRepository;
    private final AuditLogRepository auditLogRepository;
    private final CandidateRepository candidateRepository;
    private final ScreeningRepository screeningRepository;

    @Transactional(readOnly = true)
    public List<ApplicationJourneyResponse> listJourneyForCandidate(User caller) {
        if (caller == null || caller.getId() == null) return Collections.emptyList();

        Candidate candidate = candidateRepository.findByUserId(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate profile not found for user " + caller.getId()));

        List<Application> apps = applicationRepository
                .findByCandidateIdWithPosting(candidate.getId());
        if (apps.isEmpty()) return Collections.emptyList();

        // Batch fetch interviews + offers for the candidate ONCE, then group by
        // application id in memory. Two queries total instead of 2*N.
        Map<UUID, List<Interview>> interviewsByApp = interviewRepository
                .findAllForCandidateUser(caller.getId()).stream()
                .filter(i -> i.getApplication() != null && i.getApplication().getId() != null)
                .collect(Collectors.groupingBy(i -> i.getApplication().getId()));

        Map<UUID, List<Offer>> offersByApp = offerRepository
                .findByCandidateUserIdWithGraph(caller.getId()).stream()
                .filter(o -> o.getApplication() != null && o.getApplication().getId() != null)
                .collect(Collectors.groupingBy(o -> o.getApplication().getId()));

        // SHORTLIST audit rows (written by ApplicationService.shortlist) — gives
        // us a confident shortlistedAt per app. Other STATUS_CHANGE rows aren't
        // currently written for plain status updates, so we don't pretend we
        // know when SHORTLISTED happened if no SHORTLIST audit row exists.
        Set<UUID> appIds = apps.stream().map(Application::getId).collect(Collectors.toCollection(HashSet::new));
        Map<UUID, Instant> shortlistedAtByApp = new HashMap<>();
        if (!appIds.isEmpty()) {
            List<AuditLog> rows = auditLogRepository.findRecentForEntityIds(
                    "Application", appIds, PageRequest.of(0, AUDIT_FETCH_CAP));
            // Earliest SHORTLIST per app (the list is DESC; iterate and overwrite).
            for (AuditLog row : rows) {
                if (!"SHORTLIST".equals(row.getAction())) continue;
                if (row.getEntityId() == null || row.getTimestamp() == null) continue;
                shortlistedAtByApp.merge(
                        row.getEntityId(),
                        row.getTimestamp(),
                        (a, b) -> a.isBefore(b) ? a : b);
            }
        }

        return apps.stream()
                .map(a -> toJourney(a,
                        interviewsByApp.getOrDefault(a.getId(), Collections.emptyList()),
                        offersByApp.getOrDefault(a.getId(), Collections.emptyList()),
                        shortlistedAtByApp.get(a.getId())))
                .toList();
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private ApplicationJourneyResponse toJourney(Application a,
                                                 List<Interview> interviews,
                                                 List<Offer> offers,
                                                 Instant shortlistedAt) {
        ApplicationStatus status = a.getStatus();
        boolean exited = ApplicationLifecycle.isExited(status);
        int stage = exited ? -1 : ApplicationLifecycle.stageIndexOf(status);
        JobPosting jp = a.getJobPosting();
        StaffingEntity ent = jp != null ? jp.getEntity() : null;

        // Most recent interview wins (DESC by scheduledAt) — the candidate
        // cares about the latest scheduled / most relevant one.
        Interview latestInterview = interviews.stream()
                .filter(i -> i.getScheduledAt() != null)
                .max(Comparator.comparing(Interview::getScheduledAt))
                .orElse(null);

        Offer latestOffer = offers.stream()
                .max(Comparator.comparing(
                        Offer::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);

        ApplicationJourneyResponse.Journey journey =
                ApplicationJourneyResponse.Journey.builder()
                        .appliedAt(a.getAppliedAt())
                        .shortlistedAt(shortlistedAt)
                        .interview(toInterviewBit(latestInterview))
                        .offer(toOfferBit(latestOffer))
                        .hiredAt(status == ApplicationStatus.HIRED ? a.getStatusUpdatedAt() : null)
                        .build();

        ApplicationJourneyResponse.ActionNeeded action =
                pickActionNeeded(a, latestOffer);

        return ApplicationJourneyResponse.builder()
                .id(a.getId())
                .position(jp != null ? jp.getTitle() : null)
                .entityName(ent != null ? ent.getName() : null)
                .status(status != null ? status.name() : null)
                .stageIndex(stage)
                .isExited(exited)
                .journey(journey)
                .actionNeeded(action)
                .build();
    }

    private ApplicationJourneyResponse.InterviewBit toInterviewBit(Interview i) {
        if (i == null) return null;
        return ApplicationJourneyResponse.InterviewBit.builder()
                .scheduledAt(i.getScheduledAt())
                .status(i.getStatus() != null ? i.getStatus().name() : null)
                .build();
    }

    private ApplicationJourneyResponse.OfferBit toOfferBit(Offer o) {
        if (o == null) return null;
        // Don't surface DRAFT offers — the staff is still editing; the candidate
        // doesn't see them anywhere else and shouldn't see them here either.
        if (o.getStatus() == OfferStatus.DRAFT) return null;
        return ApplicationJourneyResponse.OfferBit.builder()
                .id(o.getId())
                .status(o.getStatus() != null ? o.getStatus().name() : null)
                .expiresAt(o.getExpiresAt())
                .decidedAt(o.getRespondedAt())
                .build();
    }

    private ApplicationJourneyResponse.ActionNeeded pickActionNeeded(
            Application application, Offer latestOffer) {
        // Live offer is the only universally-actionable thing on this view.
        if (latestOffer != null
                && latestOffer.getStatus() == OfferStatus.SENT
                && (latestOffer.getExpiresAt() == null
                        || latestOffer.getExpiresAt().isAfter(Instant.now()))) {
            return ApplicationJourneyResponse.ActionNeeded.builder()
                    .label("Respond to offer")
                    .href("/careers/intern/offers/" + latestOffer.getId())
                    .build();
        }
        // Phase 2.1: surface the take-screening CTA when the recruiter has sent
        // a screening that's still pending. Resolves the Screening id so the
        // link goes straight to the questionnaire.
        if (application != null
                && application.getStatus() == ApplicationStatus.SCREENING_SENT) {
            UUID screeningId = screeningRepository
                    .findByApplicationIdWithGraph(application.getId())
                    .map(s -> s.getId())
                    .orElse(null);
            if (screeningId != null) {
                return ApplicationJourneyResponse.ActionNeeded.builder()
                        .label("Complete screening")
                        .href("/careers/screening/" + screeningId)
                        .build();
            }
        }
        return null;
    }

}
