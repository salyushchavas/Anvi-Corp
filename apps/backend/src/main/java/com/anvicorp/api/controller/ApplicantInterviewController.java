package com.anvicorp.api.controller;

import com.anvicorp.api.dto.interview.ApplicantInterviewDetailDTO;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.InterviewRecommendation;
import com.anvicorp.api.enums.InterviewStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.InterviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Change 4 — applicant-only read endpoint backing the "After your interview"
 * detail page. Verifies the requested interview's application belongs to the
 * caller; SUPER_ADMIN bypasses the ownership check for support.
 */
@RestController
@RequestMapping("/api/v1/applicant/interviews")
@RequiredArgsConstructor
public class ApplicantInterviewController {

    private final InterviewRepository interviewRepository;

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('INTERN', 'SUPER_ADMIN')")
    public ApplicantInterviewDetailDTO get(@PathVariable UUID id,
                                           @AuthenticationPrincipal User caller) {
        Interview interview = interviewRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Interview not found: " + id));

        Application application = interview.getApplication();
        if (application == null) {
            throw new ResourceNotFoundException("Interview has no application: " + id);
        }

        boolean isAdmin = caller != null
                && caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (!isAdmin) {
            Candidate cand = application.getCandidate();
            UUID ownerUserId = (cand != null && cand.getUser() != null)
                    ? cand.getUser().getId() : null;
            if (caller == null || ownerUserId == null
                    || !ownerUserId.equals(caller.getId())) {
                throw new ForbiddenException("You don't have access to this interview.");
            }
        }

        JobPosting jp = application.getJobPosting();
        String outcomeCategory = null;
        boolean positive = false;
        if (interview.getStatus() == InterviewStatus.COMPLETED) {
            InterviewRecommendation rec = interview.getFeedbackRecommendation();
            if (rec == InterviewRecommendation.HIRE
                    || rec == InterviewRecommendation.STRONG_HIRE) {
                outcomeCategory = "HIRE";
                positive = true;
            } else if (rec == InterviewRecommendation.NO_HIRE) {
                outcomeCategory = "HOLD";
            } else if (rec == InterviewRecommendation.STRONG_NO_HIRE) {
                outcomeCategory = "REJECT";
            } else {
                outcomeCategory = "HOLD";
            }
        }

        return ApplicantInterviewDetailDTO.builder()
                .id(interview.getId())
                .applicationId(application.getId())
                .jobPostingTitle(jp != null ? jp.getTitle() : null)
                .type(interview.getType())
                .status(interview.getStatus())
                .scheduledAt(interview.getScheduledAt())
                .durationMinutes(interview.getDurationMinutes())
                .interviewerName(interview.getInterviewer() != null
                        ? interview.getInterviewer().getFullName() : null)
                .completedAt(interview.getStatus() == InterviewStatus.COMPLETED
                        ? interview.getFeedbackSubmittedAt() : null)
                .outcomeCategory(outcomeCategory)
                .outcomePositive(positive)
                .build();
    }
}
