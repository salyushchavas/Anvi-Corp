package com.anvicorp.api.profile;

import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Education;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.profile.EducationDtos.EducationListResponse;
import com.anvicorp.api.profile.EducationDtos.EducationResponse;
import com.anvicorp.api.profile.EducationDtos.EducationUpsertRequest;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EducationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * B2 profile expansion — CRUD + primary-management for the multi-education
 * table.
 *
 * <p><b>Legacy sync rule (load-bearing)</b>: every write path — create,
 * update, delete, set-primary — that changes which row is primary OR
 * mutates the current primary's fields, mirrors the resulting primary's
 * fields back onto the {@code candidates} legacy columns (school,
 * degree_level, specialization, graduation_year). If no primary row
 * remains after a delete, the legacy columns are blanked. This keeps
 * every existing reader working unchanged: ProfileCompletionService (apply
 * gate), UserProfileService toResponse, ErmApplicationService.getDetail,
 * CandidateDashboardService.</p>
 *
 * <p>Every method fires the post-submission edit-notify hook via
 * {@link ProfileNotificationService#maybeNotifyOnEdit} so an intern's
 * multi-degree changes trigger the same throttled ERM notification the
 * name/phone/address/skillset/attestation edits do.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EducationService {

    private final EducationRepository educationRepository;
    private final CandidateRepository candidateRepository;
    private final ProfileNotificationService profileNotificationService;

    @Transactional(readOnly = true)
    public EducationListResponse listForUser(User caller) {
        Candidate c = mustLoadCandidate(caller);
        List<Education> rows = educationRepository
                .findByCandidateIdOrderByGraduationDateDescCreatedAtDesc(c.getId());
        return new EducationListResponse(rows.stream().map(EducationService::toResponse).toList());
    }

    @Transactional
    public EducationResponse create(User caller, EducationUpsertRequest req) {
        Candidate c = mustLoadCandidate(caller);
        // Snapshot BEFORE mutation for the edit-notify diff.
        String beforeSummary = summariseEducations(c.getId());

        Education row = Education.builder()
                .candidateId(c.getId())
                .degreeLevel(req.getDegreeLevel())
                .institution(trim(req.getInstitution()))
                .fieldOfStudy(trim(req.getFieldOfStudy()))
                .graduationDate(req.getGraduationDate())
                .isPrimary(Boolean.FALSE)
                .build();

        // First row for a candidate becomes primary automatically; or if
        // the client explicitly asked to promote, honour it.
        boolean isFirst = educationRepository.countByCandidateId(c.getId()) == 0;
        boolean shouldBePrimary = isFirst || Boolean.TRUE.equals(req.getMakePrimary());
        row.setIsPrimary(shouldBePrimary);
        Education saved = educationRepository.save(row);
        if (shouldBePrimary) {
            demoteOtherPrimaries(c.getId(), saved.getId());
            mirrorPrimaryToCandidate(c, saved);
        }

        String afterSummary = summariseEducations(c.getId());
        if (!beforeSummary.equals(afterSummary)) {
            profileNotificationService.maybeNotifyOnEdit(caller, c, "education");
        }
        return toResponse(saved);
    }

    @Transactional
    public EducationResponse update(User caller, UUID id, EducationUpsertRequest req) {
        Candidate c = mustLoadCandidate(caller);
        Education row = mustLoadOwn(id, c.getId());
        String beforeSummary = summariseEducations(c.getId());

        row.setDegreeLevel(req.getDegreeLevel());
        row.setInstitution(trim(req.getInstitution()));
        row.setFieldOfStudy(trim(req.getFieldOfStudy()));
        row.setGraduationDate(req.getGraduationDate());
        // makePrimary on update is treated as a promotion — leaving it null
        // preserves the current primary flag.
        if (Boolean.TRUE.equals(req.getMakePrimary()) && !Boolean.TRUE.equals(row.getIsPrimary())) {
            row.setIsPrimary(Boolean.TRUE);
        }
        Education saved = educationRepository.save(row);
        if (Boolean.TRUE.equals(saved.getIsPrimary())) {
            demoteOtherPrimaries(c.getId(), saved.getId());
            mirrorPrimaryToCandidate(c, saved);
        }

        String afterSummary = summariseEducations(c.getId());
        if (!beforeSummary.equals(afterSummary)) {
            profileNotificationService.maybeNotifyOnEdit(caller, c, "education");
        }
        return toResponse(saved);
    }

    @Transactional
    public EducationResponse setPrimary(User caller, UUID id) {
        Candidate c = mustLoadCandidate(caller);
        Education row = mustLoadOwn(id, c.getId());
        String beforeSummary = summariseEducations(c.getId());

        row.setIsPrimary(Boolean.TRUE);
        Education saved = educationRepository.save(row);
        demoteOtherPrimaries(c.getId(), saved.getId());
        mirrorPrimaryToCandidate(c, saved);

        String afterSummary = summariseEducations(c.getId());
        if (!beforeSummary.equals(afterSummary)) {
            profileNotificationService.maybeNotifyOnEdit(caller, c, "education");
        }
        return toResponse(saved);
    }

    @Transactional
    public void delete(User caller, UUID id) {
        Candidate c = mustLoadCandidate(caller);
        Education row = mustLoadOwn(id, c.getId());
        String beforeSummary = summariseEducations(c.getId());
        boolean wasPrimary = Boolean.TRUE.equals(row.getIsPrimary());
        educationRepository.delete(row);

        if (wasPrimary) {
            // Promote the newest remaining row (by graduation date, then
            // createdAt) as the new primary; if none remains, blank the
            // legacy columns on candidate.
            List<Education> remaining = educationRepository
                    .findByCandidateIdOrderByGraduationDateDescCreatedAtDesc(c.getId());
            if (remaining.isEmpty()) {
                blankLegacyOnCandidate(c);
            } else {
                Education newPrimary = remaining.get(0);
                newPrimary.setIsPrimary(Boolean.TRUE);
                educationRepository.save(newPrimary);
                mirrorPrimaryToCandidate(c, newPrimary);
            }
        }

        String afterSummary = summariseEducations(c.getId());
        if (!beforeSummary.equals(afterSummary)) {
            profileNotificationService.maybeNotifyOnEdit(caller, c, "education");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Candidate mustLoadCandidate(User caller) {
        if (caller == null || caller.getId() == null) {
            throw new ForbiddenException("Authentication required");
        }
        return candidateRepository.findByUserId(caller.getId())
                .orElseThrow(() -> new BadRequestException(
                        "Only intern candidates may manage their education list"));
    }

    private Education mustLoadOwn(UUID id, UUID candidateId) {
        Education row = educationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Education not found: " + id));
        if (!candidateId.equals(row.getCandidateId())) {
            throw new ForbiddenException("Education does not belong to caller");
        }
        return row;
    }

    /** Demote every other row for this candidate off primary. */
    private void demoteOtherPrimaries(UUID candidateId, UUID keepId) {
        for (Education other : educationRepository
                .findByCandidateIdOrderByGraduationDateDescCreatedAtDesc(candidateId)) {
            if (!other.getId().equals(keepId) && Boolean.TRUE.equals(other.getIsPrimary())) {
                other.setIsPrimary(Boolean.FALSE);
                educationRepository.save(other);
            }
        }
    }

    /**
     * Legacy sync rule — mirror the primary Education row's fields back
     * onto the candidate row. Every existing reader
     * (ProfileCompletionService, ErmApplicationService, dashboard) sees
     * the same values it always did.
     */
    private void mirrorPrimaryToCandidate(Candidate c, Education primary) {
        c.setSchool(primary.getInstitution());
        c.setDegreeLevel(primary.getDegreeLevel());
        c.setSpecialization(primary.getFieldOfStudy());
        c.setGraduationYear(primary.getGraduationDate() != null
                ? (short) primary.getGraduationDate().getYear()
                : null);
        candidateRepository.save(c);
    }

    private void blankLegacyOnCandidate(Candidate c) {
        c.setSchool(null);
        c.setDegreeLevel(null);
        c.setSpecialization(null);
        c.setGraduationYear(null);
        candidateRepository.save(c);
    }

    /**
     * Compact one-line summary of a candidate's education list — used as the
     * before/after fingerprint for the edit-notify diff. Any semantic
     * change (add / remove / edit field / re-primary) flips the fingerprint.
     */
    private String summariseEducations(UUID candidateId) {
        StringBuilder sb = new StringBuilder();
        for (Education e : educationRepository
                .findByCandidateIdOrderByGraduationDateDescCreatedAtDesc(candidateId)) {
            sb.append(e.getId())
                    .append("|").append(e.getDegreeLevel())
                    .append("|").append(e.getInstitution())
                    .append("|").append(e.getFieldOfStudy())
                    .append("|").append(e.getGraduationDate())
                    .append("|").append(Boolean.TRUE.equals(e.getIsPrimary()))
                    .append(";");
        }
        return sb.toString();
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static EducationResponse toResponse(Education e) {
        return new EducationResponse(
                e.getId(),
                e.getDegreeLevel(),
                e.getInstitution(),
                e.getFieldOfStudy(),
                e.getGraduationDate(),
                Boolean.TRUE.equals(e.getIsPrimary()));
    }
}
