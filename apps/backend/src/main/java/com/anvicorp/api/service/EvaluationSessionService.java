package com.anvicorp.api.service;

import com.anvicorp.api.dto.supervised.AssignEvaluatorRequest;
import com.anvicorp.api.dto.supervised.CompleteEvaluationRequest;
import com.anvicorp.api.dto.supervised.EvaluationSessionResponse;
import com.anvicorp.api.dto.supervised.EvaluatorOption;
import com.anvicorp.api.dto.supervised.InternSummaryResponse;
import com.anvicorp.api.dto.supervised.ScheduleEvaluationRequest;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.EvaluationSession;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.EvaluationSessionStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EvaluationSessionRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EvaluationSessionService {

    private final EvaluationSessionRepository sessionRepository;
    private final CandidateRepository candidateRepository;
    private final UserRepository userRepository;
    private final EngagementService engagementService;

    @Transactional(readOnly = true)
    public List<EvaluatorOption> listEvaluators() {
        return userRepository.findByRole(UserRole.TRAINER).stream()
                .map(u -> EvaluatorOption.builder()
                        .id(u.getId())
                        .name(u.getFullName())
                        .build())
                .toList();
    }

    /**
     * Sets {@link Candidate#getAssignedEvaluator()} and returns the refreshed
     * intern summary so the staff view can update its header in one round-trip.
     */
    @Transactional
    public InternSummaryResponse assignEvaluator(UUID candidateId, AssignEvaluatorRequest req) {
        Candidate intern = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate not found: " + candidateId));
        User evaluator = userRepository.findById(req.getEvaluatorId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Evaluator user not found: " + req.getEvaluatorId()));
        if (!evaluator.getRoles().contains(UserRole.TRAINER)) {
            throw new BadRequestException("Selected user is not a Technical Evaluator");
        }
        intern.setAssignedEvaluator(evaluator);
        candidateRepository.save(intern);

        User u = intern.getUser();
        return InternSummaryResponse.builder()
                .candidateId(intern.getId())
                .name(u != null ? u.getFullName() : null)
                .email(u != null ? u.getEmail() : null)
                .assignedEvaluatorName(evaluator.getFullName())
                .build();
    }

    @Transactional
    public EvaluationSessionResponse schedule(UUID candidateId,
                                              ScheduleEvaluationRequest req) {
        Candidate intern = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate not found: " + candidateId));

        User evaluator = null;
        if (req.getEvaluatorId() != null) {
            evaluator = userRepository.findById(req.getEvaluatorId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Evaluator user not found: " + req.getEvaluatorId()));
            if (!evaluator.getRoles().contains(UserRole.TRAINER)) {
                throw new BadRequestException("Selected user is not a Technical Evaluator");
            }
        } else if (intern.getAssignedEvaluator() != null) {
            evaluator = intern.getAssignedEvaluator();
        }

        // Phase 3 step 8 — link to the intern's active engagement when one
        // exists. Null is fine: row stays reachable via the intern-keyed
        // queries; step-11 backfill (opt-in) handles legacy rows.
        Engagement engagement = engagementService
                .resolveActiveForCandidate(intern.getId())
                .orElse(null);
        EvaluationSession s = EvaluationSession.builder()
                .intern(intern)
                .engagement(engagement)
                .evaluator(evaluator)
                .scheduledAt(req.getScheduledAt())
                .status(EvaluationSessionStatus.SCHEDULED)
                .build();
        s = sessionRepository.save(s);
        return toResponse(sessionRepository.findByIdWithGraph(s.getId())
                .orElseThrow(() -> new IllegalStateException("Just-created session vanished")));
    }

    @Transactional(readOnly = true)
    public List<EvaluationSessionResponse> listForIntern(UUID candidateId) {
        if (!candidateRepository.existsById(candidateId)) {
            throw new ResourceNotFoundException("Candidate not found: " + candidateId);
        }
        return sessionRepository.findForIntern(candidateId).stream()
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<EvaluationSessionResponse> listForCandidateUser(User caller) {
        return sessionRepository.findForCandidateUser(caller.getId()).stream()
                .map(this::toResponse).toList();
    }

    @Transactional
    public EvaluationSessionResponse complete(UUID id, CompleteEvaluationRequest req) {
        EvaluationSession s = sessionRepository.findByIdWithGraph(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evaluation session not found: " + id));
        if (s.getStatus() == EvaluationSessionStatus.COMPLETED) {
            // Idempotent: re-completing simply overwrites the rating/notes — useful
            // if the evaluator hits Save twice or wants to amend the feedback.
        } else if (s.getStatus() != EvaluationSessionStatus.SCHEDULED) {
            throw new BadRequestException(
                    "Only SCHEDULED sessions can be completed (current: " + s.getStatus() + ")");
        }
        s.setOverallRating(req.getOverallRating());
        s.setStrengths(req.getStrengths());
        s.setAreasForImprovement(req.getAreasForImprovement());
        s.setNotes(req.getNotes());
        s.setStatus(EvaluationSessionStatus.COMPLETED);
        s.setCompletedAt(Instant.now());
        sessionRepository.save(s);
        return toResponse(s);
    }

    @Transactional
    public EvaluationSessionResponse miss(UUID id) {
        EvaluationSession s = sessionRepository.findByIdWithGraph(id)
                .orElseThrow(() -> new ResourceNotFoundException("Evaluation session not found: " + id));
        if (s.getStatus() != EvaluationSessionStatus.SCHEDULED) {
            throw new BadRequestException(
                    "Only SCHEDULED sessions can be marked missed (current: " + s.getStatus() + ")");
        }
        s.setStatus(EvaluationSessionStatus.MISSED);
        sessionRepository.save(s);
        return toResponse(s);
    }

    private EvaluationSessionResponse toResponse(EvaluationSession s) {
        User evaluator = s.getEvaluator();
        return EvaluationSessionResponse.builder()
                .id(s.getId())
                .scheduledAt(s.getScheduledAt())
                .status(s.getStatus())
                .evaluatorName(evaluator != null ? evaluator.getFullName() : null)
                .evaluatorId(evaluator != null ? evaluator.getId() : null)
                .overallRating(s.getOverallRating())
                .strengths(s.getStrengths())
                .areasForImprovement(s.getAreasForImprovement())
                .notes(s.getNotes())
                .completedAt(s.getCompletedAt())
                .createdAt(s.getCreatedAt())
                .build();
    }
}
