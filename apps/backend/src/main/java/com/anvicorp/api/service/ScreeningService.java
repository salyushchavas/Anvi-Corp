package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.screening.ScreeningCandidateResponse;
import com.anvicorp.api.dto.screening.ScreeningStaffResponse;
import com.anvicorp.api.dto.screening.ScreeningSubmitRequest;
import com.anvicorp.api.dto.screening.ScreeningSummaryResponse;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Screening;
import com.anvicorp.api.entity.ScreeningAnswer;
import com.anvicorp.api.entity.ScreeningQuestion;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.ScreeningQuestionType;
import com.anvicorp.api.enums.ScreeningStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.ScreeningAnswerRepository;
import com.anvicorp.api.repository.ScreeningQuestionRepository;
import com.anvicorp.api.repository.ScreeningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight screening (phase 2.1). The recruiter sends a screening, the
 * candidate completes a short questionnaire, the result + score show to staff.
 * All application-status changes go through {@link ApplicationService#transitionTo}
 * so the 1.1 guard + audit remain the single source of truth.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScreeningService {

    private final ScreeningRepository screeningRepository;
    private final ScreeningQuestionRepository questionRepository;
    private final ScreeningAnswerRepository answerRepository;
    private final ApplicationRepository applicationRepository;
    private final AuditLogRepository auditLogRepository;
    private final ApplicationService applicationService;
    private final ObjectMapper objectMapper;

    // ── Commands ────────────────────────────────────────────────────────────

    /**
     * Recruiter sends a screening. Creates a Screening row (one per application;
     * idempotent if it already exists) and transitions the application to
     * SCREENING_SENT. Calling on an app already past APPLIED that's NOT in
     * SCREENING_SENT/COMPLETED is refused with 400 (the lifecycle guard does this).
     */
    @Transactional
    public ScreeningSummaryResponse send(UUID applicationId, User actor) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + applicationId));

        // Idempotent: if a screening already exists, return it without state changes.
        Screening existing = screeningRepository
                .findByApplicationIdWithGraph(applicationId)
                .orElse(null);
        if (existing != null) {
            return toSummary(existing);
        }

        Screening screening = Screening.builder()
                .application(application)
                .status(ScreeningStatus.SENT)
                .sentAt(Instant.now())
                .createdBy(actor != null ? actor.getId() : null)
                .build();
        screening = screeningRepository.save(screening);

        // Transition the application — the guard rejects illegal source states
        // (e.g. INTERVIEWED) with 400 via BadRequestException, NOT 500.
        applicationService.transitionTo(application,
                ApplicationStatus.SCREENING_SENT,
                "SCREENING_SENT",
                actor);

        writeAudit("Screening", screening.getId(), "SCREENING_SENT",
                actor != null ? actor.getId() : null);
        return toSummary(screening);
    }

    /**
     * Candidate submits answers. Scores SINGLE_CHOICE questions, records all
     * answers, flips the screening to COMPLETED + completedAt = now, and
     * transitions the application to SCREENING_COMPLETED. Idempotent at the
     * "already completed" boundary — a resubmit is refused with 400.
     */
    @Transactional
    public ScreeningSummaryResponse submit(UUID screeningId,
                                           ScreeningSubmitRequest req,
                                           User candidateUser) {
        Screening screening = screeningRepository.findByIdWithGraph(screeningId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Screening not found: " + screeningId));

        ensureCandidateOwns(screening, candidateUser);

        if (screening.getStatus() == ScreeningStatus.COMPLETED) {
            throw new BadRequestException("Screening already completed");
        }

        // Pull all questions once; build a lookup by id.
        List<ScreeningQuestion> questions = questionRepository.findAllByOrderByOrderIndexAsc();
        Map<UUID, ScreeningQuestion> byId = new HashMap<>();
        for (ScreeningQuestion q : questions) byId.put(q.getId(), q);

        int score = 0;
        int maxScore = 0;
        List<ScreeningAnswer> toSave = new ArrayList<>();
        for (ScreeningSubmitRequest.AnswerInput in : req.getAnswers()) {
            ScreeningQuestion q = byId.get(in.getQuestionId());
            if (q == null) {
                // Unknown question id — skip rather than fail (older question
                // pool may have rotated). Doesn't affect the score.
                continue;
            }
            int awarded = 0;
            if (q.getType() == ScreeningQuestionType.SINGLE_CHOICE) {
                maxScore += q.getPoints() != null ? q.getPoints() : 0;
                if (in.getChoiceIndex() != null
                        && q.getCorrectChoiceIndex() != null
                        && in.getChoiceIndex().equals(q.getCorrectChoiceIndex())) {
                    awarded = q.getPoints() != null ? q.getPoints() : 0;
                }
                score += awarded;
            }
            toSave.add(ScreeningAnswer.builder()
                    .screening(screening)
                    .question(q)
                    .choiceIndex(in.getChoiceIndex())
                    .freeText(in.getFreeText())
                    .awardedPoints(awarded)
                    .build());
        }

        // For SINGLE_CHOICE questions the candidate didn't answer, the
        // question still counts toward maxScore so the score reflects "out of
        // the full screening." This penalizes skipping the right way around.
        for (ScreeningQuestion q : questions) {
            if (q.getType() != ScreeningQuestionType.SINGLE_CHOICE) continue;
            boolean answered = req.getAnswers().stream()
                    .anyMatch(a -> q.getId().equals(a.getQuestionId()));
            if (!answered) {
                maxScore += q.getPoints() != null ? q.getPoints() : 0;
            }
        }

        answerRepository.saveAll(toSave);

        screening.setStatus(ScreeningStatus.COMPLETED);
        screening.setCompletedAt(Instant.now());
        screening.setScore(score);
        screening.setMaxScore(maxScore);
        screening = screeningRepository.save(screening);

        applicationService.transitionTo(screening.getApplication(),
                ApplicationStatus.SCREENING_COMPLETED,
                "SCREENING_COMPLETED",
                candidateUser);

        writeAudit("Screening", screening.getId(), "SCREENING_COMPLETED",
                candidateUser != null ? candidateUser.getId() : null);
        return toSummary(screening);
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ScreeningCandidateResponse getForCandidate(UUID screeningId, User candidateUser) {
        Screening screening = screeningRepository.findByIdWithGraph(screeningId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Screening not found: " + screeningId));
        ensureCandidateOwns(screening, candidateUser);
        return toCandidateView(screening);
    }

    @Transactional(readOnly = true)
    public ScreeningStaffResponse getForApplicationStaff(UUID applicationId, User caller) {
        ensureStaffCanRead(caller);
        Screening screening = screeningRepository
                .findByApplicationIdWithGraph(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No screening for application " + applicationId));
        return toStaffView(screening);
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    private ScreeningSummaryResponse toSummary(Screening s) {
        return ScreeningSummaryResponse.builder()
                .id(s.getId())
                .applicationId(s.getApplication() != null ? s.getApplication().getId() : null)
                .status(s.getStatus() != null ? s.getStatus().name() : null)
                .sentAt(s.getSentAt())
                .completedAt(s.getCompletedAt())
                .score(s.getScore())
                .maxScore(s.getMaxScore())
                .build();
    }

    private ScreeningCandidateResponse toCandidateView(Screening s) {
        Application app = s.getApplication();
        JobPosting jp = app != null ? app.getJobPosting() : null;
        StaffingEntity ent = jp != null ? jp.getEntity() : null;
        List<ScreeningQuestion> questions = questionRepository.findAllByOrderByOrderIndexAsc();
        List<ScreeningCandidateResponse.Question> dtoQuestions = questions.stream()
                .map(this::toCandidateQuestion)
                .toList();
        return ScreeningCandidateResponse.builder()
                .id(s.getId())
                .status(s.getStatus() != null ? s.getStatus().name() : null)
                .sentAt(s.getSentAt())
                .completedAt(s.getCompletedAt())
                .jobPostingTitle(jp != null ? jp.getTitle() : null)
                .entityName(ent != null ? ent.getName() : null)
                .questions(dtoQuestions)
                .build();
    }

    private ScreeningCandidateResponse.Question toCandidateQuestion(ScreeningQuestion q) {
        return ScreeningCandidateResponse.Question.builder()
                .id(q.getId())
                .orderIndex(q.getOrderIndex())
                .type(q.getType() != null ? q.getType().name() : null)
                .prompt(q.getPrompt())
                .choices(parseChoices(q.getChoicesJson()))
                .build();
    }

    private ScreeningStaffResponse toStaffView(Screening s) {
        Application app = s.getApplication();
        JobPosting jp = app != null ? app.getJobPosting() : null;
        StaffingEntity ent = jp != null ? jp.getEntity() : null;
        Candidate candidate = app != null ? app.getCandidate() : null;
        User candidateUser = candidate != null ? candidate.getUser() : null;

        List<ScreeningStaffResponse.AnswerView> answerViews;
        if (s.getStatus() == ScreeningStatus.COMPLETED) {
            List<ScreeningAnswer> answers = answerRepository
                    .findByScreeningIdWithQuestion(s.getId());
            answerViews = answers.stream().map(this::toAnswerView).toList();
        } else {
            answerViews = Collections.emptyList();
        }

        return ScreeningStaffResponse.builder()
                .id(s.getId())
                .applicationId(app != null ? app.getId() : null)
                .status(s.getStatus() != null ? s.getStatus().name() : null)
                .sentAt(s.getSentAt())
                .completedAt(s.getCompletedAt())
                .score(s.getScore())
                .maxScore(s.getMaxScore())
                .candidateName(candidateUser != null ? candidateUser.getFullName() : null)
                .candidateEmail(candidateUser != null ? candidateUser.getEmail() : null)
                .jobPostingTitle(jp != null ? jp.getTitle() : null)
                .entityName(ent != null ? ent.getName() : null)
                .answers(answerViews)
                .build();
    }

    private ScreeningStaffResponse.AnswerView toAnswerView(ScreeningAnswer a) {
        ScreeningQuestion q = a.getQuestion();
        return ScreeningStaffResponse.AnswerView.builder()
                .questionId(q.getId())
                .orderIndex(q.getOrderIndex())
                .type(q.getType() != null ? q.getType().name() : null)
                .prompt(q.getPrompt())
                .choices(parseChoices(q.getChoicesJson()))
                .correctChoiceIndex(q.getCorrectChoiceIndex())
                .choiceIndex(a.getChoiceIndex())
                .freeText(a.getFreeText())
                .points(q.getPoints())
                .awardedPoints(a.getAwardedPoints())
                .build();
    }

    private List<String> parseChoices(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Bad choicesJson — returning empty list: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── Permission helpers ──────────────────────────────────────────────────

    private void ensureCandidateOwns(Screening s, User candidateUser) {
        if (candidateUser == null) {
            throw new ForbiddenException("Authentication required");
        }
        Application app = s.getApplication();
        if (app == null || app.getCandidate() == null || app.getCandidate().getUser() == null) {
            throw new ForbiddenException("This screening does not belong to you");
        }
        if (!app.getCandidate().getUser().getId().equals(candidateUser.getId())) {
            throw new ForbiddenException("This screening does not belong to you");
        }
    }

    private void ensureStaffCanRead(User caller) {
        if (caller == null || caller.getRoles() == null) {
            throw new ForbiddenException("Authentication required");
        }
        boolean allowed = caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.ERM);
        if (!allowed) {
            throw new ForbiddenException("Not allowed to view this screening");
        }
    }

    // ── Audit log ───────────────────────────────────────────────────────────

    private void writeAudit(String entityType, UUID entityId, String action, UUID userId) {
        AuditLog row = AuditLog.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .userId(userId)
                .build();
        auditLogRepository.save(row);
    }
}
