package com.anvicorp.api.service;

import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.intern.ApplyReadiness;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for "can this intern apply?" — derives an
 * {@link ApplyReadiness} from the 6 required-to-apply fields:
 * phone, school, degree, graduationYear, skillset, resume.
 *
 * <p>Pure read, no writes. Used by both the dashboard payload (advisory
 * %/checklist) and the apply endpoint gate (authoritative — throws
 * {@link com.anvicorp.api.exception.ProfileIncompleteException} when
 * {@code complete == false}).</p>
 */
@Service
@RequiredArgsConstructor
public class ProfileCompletionService {

    private static final List<String> REQUIRED_KEYS = List.of(
            "phone", "school", "degree", "graduationYear", "skillset", "resume");

    private final CandidateRepository candidateRepository;
    private final ResumeRepository resumeRepository;

    @Transactional(readOnly = true)
    public ApplyReadiness applyReadiness(User user) {
        if (user == null) {
            return new ApplyReadiness(false, 0, REQUIRED_KEYS);
        }
        Candidate candidate = candidateRepository.findByUserId(user.getId()).orElse(null);

        List<String> missing = new ArrayList<>();
        if (isBlank(user.getPhoneNumber())) missing.add("phone");
        if (candidate == null || isBlank(candidate.getSchool())) missing.add("school");
        // Degree counts as filled when either the structured DegreeLevel is set
        // OR the legacy free-text degree column has content — older interns
        // registered before Phase 1.5 only have the legacy column.
        boolean hasDegree = candidate != null
                && (candidate.getDegreeLevel() != null || !isBlank(candidate.getDegree()));
        if (!hasDegree) missing.add("degree");
        if (candidate == null || candidate.getGraduationYear() == null) {
            missing.add("graduationYear");
        }
        if (candidate == null || isBlank(candidate.getSkillset())) missing.add("skillset");
        boolean hasResume = candidate != null
                && resumeRepository.countByCandidateId(candidate.getId()) > 0;
        if (!hasResume) missing.add("resume");

        int filled = REQUIRED_KEYS.size() - missing.size();
        int percent = Math.round(100f * filled / REQUIRED_KEYS.size());
        return new ApplyReadiness(missing.isEmpty(), percent, List.copyOf(missing));
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
