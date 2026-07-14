package com.anvicorp.api.service;

import com.anvicorp.api.dto.candidates.CandidateDetailResponse;
import com.anvicorp.api.dto.candidates.CandidateListItemResponse;
import com.anvicorp.api.dto.common.PagedResponse;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Resume;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Staff-side read endpoints for browsing candidates. Backed by JOIN-FETCH
 * repository queries so the mappers never lazy-load — all reads run under
 * {@code @Transactional(readOnly = true)} as belt-and-braces.
 */
@Service
@RequiredArgsConstructor
public class CandidatesService {

    private final CandidateRepository candidateRepository;
    private final ApplicationRepository applicationRepository;
    private final ResumeRepository resumeRepository;

    @Transactional(readOnly = true)
    public PagedResponse<CandidateListItemResponse> list(String search, Pageable pageable) {
        String normalized = (search != null && !search.isBlank()) ? search.trim() : null;
        Page<Candidate> page = candidateRepository.findAll(
                com.anvicorp.api.repository.CandidateSpecifications
                        .nameOrEmailMatches(normalized),
                pageable);
        return PagedResponse.of(page, this::toListItem);
    }

    @Transactional(readOnly = true)
    public CandidateDetailResponse detail(UUID candidateId) {
        Candidate candidate = candidateRepository.findByIdWithUser(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate not found: " + candidateId));

        User u = candidate.getUser();
        List<Application> apps = applicationRepository.findByCandidateIdWithPosting(candidateId);

        CandidateDetailResponse.ResumeSummary resumeSummary = null;
        if (candidate.getDefaultResumeId() != null) {
            resumeSummary = resumeRepository.findById(candidate.getDefaultResumeId())
                    .map(r -> CandidateDetailResponse.ResumeSummary.builder()
                            .id(r.getId())
                            .fileName(r.getFileName())
                            .build())
                    .orElse(null);
        }
        if (resumeSummary == null) {
            // Fall back to any resume the candidate has uploaded if no default is set.
            resumeSummary = resumeRepository.findByCandidateId(candidateId).stream()
                    .findFirst()
                    .map(r -> CandidateDetailResponse.ResumeSummary.builder()
                            .id(r.getId())
                            .fileName(r.getFileName())
                            .build())
                    .orElse(null);
        }

        List<CandidateDetailResponse.ApplicationSummary> appSummaries = apps.stream()
                .map(this::toApplicationSummary)
                .toList();

        return CandidateDetailResponse.builder()
                .candidateId(candidate.getId())
                .name(u != null ? u.getFullName() : null)
                .email(u != null ? u.getEmail() : null)
                .phone(u != null ? u.getPhoneNumber() : null)
                .dateOfBirth(candidate.getDateOfBirth())
                .createdAt(candidate.getCreatedAt())
                .resume(resumeSummary)
                .applications(appSummaries)
                .build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private CandidateListItemResponse toListItem(Candidate c) {
        User u = c.getUser();
        List<Application> apps = applicationRepository.findByCandidateIdWithPosting(c.getId());
        Application latest = apps.stream()
                .max(Comparator.comparing(Application::getStatusUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        JobPosting jp = latest != null ? latest.getJobPosting() : null;
        boolean hasResume = c.getDefaultResumeId() != null
                || !resumeRepository.findByCandidateId(c.getId()).isEmpty();

        return CandidateListItemResponse.builder()
                .candidateId(c.getId())
                .name(u != null ? u.getFullName() : null)
                .email(u != null ? u.getEmail() : null)
                .phone(u != null ? u.getPhoneNumber() : null)
                .applicationCount(apps.size())
                .latestStatus(latest != null ? latest.getStatus() : null)
                .latestPosition(jp != null ? jp.getTitle() : null)
                .hasResume(hasResume)
                .createdAt(c.getCreatedAt())
                .build();
    }

    private CandidateDetailResponse.ApplicationSummary toApplicationSummary(Application a) {
        JobPosting jp = a.getJobPosting();
        StaffingEntity entity = jp != null ? jp.getEntity() : null;
        return CandidateDetailResponse.ApplicationSummary.builder()
                .id(a.getId())
                .position(jp != null ? jp.getTitle() : null)
                .entityName(entity != null ? entity.getName() : null)
                .status(a.getStatus() != null ? a.getStatus().name() : null)
                .appliedAt(a.getAppliedAt())
                .build();
    }
}
