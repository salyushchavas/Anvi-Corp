package com.anvicorp.api.service;

import com.anvicorp.api.dto.JobPostingCreateRequest;
import com.anvicorp.api.dto.JobPostingResponse;
import com.anvicorp.api.dto.JobPostingUpdateRequest;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.JobPostingStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.EmailUnverifiedException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.JobPostingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JobPostingService {

    private final JobPostingRepository jobPostingRepository;
    private final ApplicationRepository applicationRepository;
    private final CandidateRepository candidateRepository;

    @Transactional(readOnly = true)
    public Page<JobPostingResponse> listOpen(Pageable pageable) {
        return jobPostingRepository.findByStatus(JobPostingStatus.OPEN, pageable)
                .map(this::toResponse);
    }

    /**
     * Same paged list as {@link #listOpen(Pageable)}, but decorated with the
     * authenticated candidate's per-posting application state (applied,
     * applicationId, applicationStatus). Detection is a SINGLE query
     * ({@code findByCandidateId}) so the openings page stays O(1) regardless
     * of how many postings are on screen — no N+1.
     *
     * For non-candidates and unauthenticated callers we fall back to plain
     * {@link #listOpen(Pageable)} — those callers see the public shape.
     */
    @Transactional(readOnly = true)
    public Page<JobPostingResponse> listOpenForCandidate(User caller, Pageable pageable) {
        if (caller == null
                || caller.getRoles() == null
                || !(caller.getRoles().contains(UserRole.INTERN) || caller.getRoles().contains(UserRole.INTERN))) {
            // Anonymous + staff callers see the public openings list — they
            // don't need an Applicant ID and aren't subject to the gate.
            return listOpen(pageable);
        }

        // Phase 1.3 gate: a CANDIDATE must verify their email before the
        // openings list unlocks. Frontend keys off the "EMAIL_UNVERIFIED"
        // code returned via GlobalExceptionHandler.
        if (!Boolean.TRUE.equals(caller.getEmailVerified())) {
            throw new EmailUnverifiedException(
                    "Verify your email to unlock internships");
        }

        Candidate candidate = candidateRepository.findByUserId(caller.getId()).orElse(null);
        if (candidate == null) {
            return listOpen(pageable);
        }

        // One round-trip: pull every application this candidate has, then
        // group by jobPostingId and keep the most-recent one (by appliedAt).
        List<Application> apps = applicationRepository.findByCandidateId(candidate.getId());
        Map<UUID, Application> byPostingId = new HashMap<>();
        for (Application a : apps) {
            if (a.getJobPosting() == null) continue;
            UUID postingId = a.getJobPosting().getId();
            Application existing = byPostingId.get(postingId);
            if (existing == null
                    || moreRecent(a.getAppliedAt(), existing.getAppliedAt())) {
                byPostingId.put(postingId, a);
            }
        }

        return jobPostingRepository.findByStatus(JobPostingStatus.OPEN, pageable)
                .map(p -> {
                    JobPostingResponse base = toResponse(p);
                    Application app = byPostingId.get(p.getId());
                    if (app != null) {
                        base.setApplied(true);
                        base.setApplicationId(app.getId());
                        base.setApplicationStatus(
                                app.getStatus() != null ? app.getStatus().name() : null);
                    }
                    return base;
                });
    }

    private boolean moreRecent(Instant a, Instant b) {
        // a "wins" when it's strictly later than b. Null safely returns false
        // so an entry with a real timestamp beats one without.
        return Comparator.nullsLast(Comparator.<Instant>naturalOrder()).compare(a, b) > 0;
    }

    @Transactional(readOnly = true)
    public Page<JobPostingResponse> listAll(Pageable pageable) {
        return listAllForAdmin(null, null, null, pageable);
    }

    /**
     * Admin / ERM list for the postings management page. Composes optional
     * {@code search} (matches title OR description), {@code status}, and
     * {@code entityId} filters via a JPA Specification, then batch-loads
     * applicant counts in a single query so the page is O(1) regardless of
     * page size — no per-row count lookups.
     */
    @Transactional(readOnly = true)
    public Page<JobPostingResponse> listAllForAdmin(String search,
                                                    JobPostingStatus status,
                                                    UUID entityId,
                                                    Pageable pageable) {
        Page<JobPosting> page = jobPostingRepository.findAll(
                com.anvicorp.api.repository.JobPostingSpecifications.withFilters(
                        search, status, entityId),
                pageable);
        if (page.isEmpty()) {
            return page.map(this::toResponse);
        }

        // Batch applicant counts: one JPQL GROUP BY query for every posting
        // id on this page. Postings with zero applications won't appear in
        // the result, so we default-fill them as 0 below.
        java.util.List<java.util.UUID> postingIds = page.getContent().stream()
                .map(JobPosting::getId)
                .toList();
        java.util.Map<UUID, Long> countById = new java.util.HashMap<>();
        for (Object[] row : applicationRepository.countByJobPostingIdIn(postingIds)) {
            UUID pid = (UUID) row[0];
            Long count = ((Number) row[1]).longValue();
            countById.put(pid, count);
        }

        return page.map(p -> {
            JobPostingResponse r = toResponse(p);
            r.setApplicantCount(countById.getOrDefault(p.getId(), 0L));
            return r;
        });
    }

    @Transactional(readOnly = true)
    public JobPostingResponse findPublicByIdOrSlug(String idOrSlug,
                                                   com.anvicorp.api.entity.User viewer) {
        JobPosting posting = lookupByIdOrSlug(idOrSlug);
        boolean privileged = viewer != null
                && (viewer.getRoles().contains(UserRole.ERM)
                || viewer.getRoles().contains(UserRole.JOBS_ADMIN)
                || viewer.getRoles().contains(UserRole.SUPER_ADMIN));
        if (!privileged && posting.getStatus() != JobPostingStatus.OPEN) {
            throw new ResourceNotFoundException("Job posting not found");
        }
        return toResponse(posting);
    }

    @Transactional(readOnly = true)
    public JobPostingResponse findAnyByIdOrSlug(String idOrSlug) {
        return toResponse(lookupByIdOrSlug(idOrSlug));
    }

    @Transactional
    public JobPostingResponse create(JobPostingCreateRequest req, User publisher) {
        String jobId = normalizeJobId(req.getJobId());
        if (jobPostingRepository.existsByJobId(jobId)) {
            throw new BadRequestException("Job ID already exists: " + jobId);
        }

        JobPosting posting = JobPosting.builder()
                .jobId(jobId)
                .title(req.getTitle())
                .description(req.getDescription())
                .requirements(req.getRequirements())
                .location(req.getLocation())
                .employmentType(req.getEmploymentType())
                .status(JobPostingStatus.DRAFT)
                .slug(generateUniqueSlug(req.getTitle()))
                .publishedById(publisher != null ? publisher.getId() : null)
                .build();
        if (req.getStatus() != null) {
            applyStatusTransition(posting, req.getStatus());
        }
        posting = jobPostingRepository.save(posting);
        return toResponse(posting);
    }

    @Transactional
    public JobPostingResponse update(UUID id, JobPostingUpdateRequest req) {
        JobPosting posting = jobPostingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Job posting not found: " + id));

        if (req.getJobId() != null) {
            String jobId = normalizeJobId(req.getJobId());
            if (jobPostingRepository.existsByJobIdAndIdNot(jobId, id)) {
                throw new BadRequestException("Job ID already exists: " + jobId);
            }
            posting.setJobId(jobId);
        }
        if (req.getTitle() != null) posting.setTitle(req.getTitle());
        if (req.getDescription() != null) posting.setDescription(req.getDescription());
        if (req.getRequirements() != null) posting.setRequirements(req.getRequirements());
        if (req.getLocation() != null) posting.setLocation(req.getLocation());
        if (req.getEmploymentType() != null) posting.setEmploymentType(req.getEmploymentType());
        if (req.getStatus() != null) {
            applyStatusTransition(posting, req.getStatus());
        }
        return toResponse(posting);
    }

    @Transactional
    public JobPostingResponse updateStatus(UUID id, JobPostingStatus status) {
        JobPosting posting = jobPostingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Job posting not found: " + id));
        applyStatusTransition(posting, status);
        return toResponse(posting);
    }

    private void applyStatusTransition(JobPosting posting, JobPostingStatus newStatus) {
        boolean firstPublish = newStatus == JobPostingStatus.OPEN && posting.getPublishedAt() == null;
        posting.setStatus(newStatus);
        if (firstPublish) {
            posting.setPublishedAt(Instant.now());
        }
    }

    private JobPosting lookupByIdOrSlug(String idOrSlug) {
        UUID uuid = tryParseUuid(idOrSlug);
        if (uuid != null) {
            return jobPostingRepository.findById(uuid)
                    .orElseThrow(() -> new ResourceNotFoundException("Job posting not found: " + idOrSlug));
        }
        return jobPostingRepository.findBySlug(idOrSlug)
                .orElseThrow(() -> new ResourceNotFoundException("Job posting not found: " + idOrSlug));
    }

    private UUID tryParseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String generateUniqueSlug(String title) {
        String base = slugify(title);
        if (base.isEmpty()) {
            base = "job-posting";
        }
        if (!jobPostingRepository.existsBySlug(base)) {
            return base;
        }
        for (int i = 0; i < 5; i++) {
            String suffix = randomSuffix();
            String candidate = base + "-" + suffix;
            if (!jobPostingRepository.existsBySlug(candidate)) {
                return candidate;
            }
        }
        return base + "-" + UUID.randomUUID();
    }

    private String slugify(String input) {
        if (input == null) return "";
        String lower = input.toLowerCase(Locale.ROOT).trim();
        String replaced = lower.replaceAll("[^a-z0-9]+", "-");
        replaced = replaced.replaceAll("(^-+|-+$)", "");
        if (replaced.length() > 80) {
            replaced = replaced.substring(0, 80).replaceAll("-+$", "");
        }
        return replaced;
    }

    private String randomSuffix() {
        String hex = Long.toHexString(System.nanoTime() ^ Double.doubleToLongBits(Math.random()));
        return hex.substring(Math.max(0, hex.length() - 4));
    }

    private String normalizeJobId(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Job ID is required");
        }
        String jobId = value.trim();
        if (jobId.length() > 64) {
            throw new BadRequestException("Job ID must be 64 characters or fewer");
        }
        return jobId;
    }

    public JobPostingResponse toResponse(JobPosting p) {
        return JobPostingResponse.builder()
                .id(p.getId())
                .slug(p.getSlug())
                .jobId(p.getJobId())
                .title(p.getTitle())
                .description(p.getDescription())
                .requirements(p.getRequirements())
                .location(p.getLocation())
                .employmentType(p.getEmploymentType())
                .status(p.getStatus())
                .entityName(p.getEntity() != null ? p.getEntity().getName() : null)
                .entityId(p.getEntity() != null ? p.getEntity().getId() : null)
                .publishedAt(p.getPublishedAt())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
