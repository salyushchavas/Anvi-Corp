package com.anvicorp.api.manager.recordings;

import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.InternEvaluation;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.evaluator.recordings.EvaluationRecordingService;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.InternEvaluationRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manager approval queue for evaluation recordings. Sits alongside
 * {@link com.anvicorp.api.manager.hire.ManagerHireApprovalService} —
 * same shape (paginated pending list + approve / reject-style actions),
 * different domain. Every state transition writes back to
 * {@link InternEvaluation}'s recording-approval fields and fans out a
 * user notification to the evaluator so they know when to re-upload or
 * when their recording became visible.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerRecordingApprovalService {

    private final InternEvaluationRepository evalRepo;
    private final DocumentRepository documentRepo;
    private final UserRepository userRepo;
    private final ProjectRepository projectRepo;
    private final InternLifecycleRepository lifecycleRepo;
    private final UserNotificationDispatcher notifier;

    /** All recordings currently waiting on the manager — includes both
     *  fresh uploads (PENDING_APPROVAL) and re-uploads after
     *  REVISION_REQUESTED (the re-upload flips the status back to
     *  PENDING_APPROVAL — the service records the transition, the queue
     *  surfaces it). Oldest-uploaded first (FIFO). */
    @Transactional(readOnly = true)
    public ManagerRecordingApprovalDtos.PendingListResponse listPending() {
        List<InternEvaluation> all = evalRepo.findAll().stream()
                .filter(e -> e.getRecordingDocumentId() != null)
                .filter(e -> EvaluationRecordingService.STATUS_PENDING
                        .equals(e.getRecordingApprovalStatus()))
                .toList();
        if (all.isEmpty()) {
            return new ManagerRecordingApprovalDtos.PendingListResponse(List.of(), 0);
        }
        Set<UUID> docIds = new HashSet<>();
        Set<UUID> userIds = new HashSet<>();
        Set<UUID> projectIds = new HashSet<>();
        Set<UUID> lifecycleIds = new HashSet<>();
        for (InternEvaluation e : all) {
            docIds.add(e.getRecordingDocumentId());
            userIds.add(e.getInternId());
            userIds.add(e.getEvaluatorId());
            if (e.getLinkedProjectId() != null) projectIds.add(e.getLinkedProjectId());
            if (e.getInternLifecycleId() != null) lifecycleIds.add(e.getInternLifecycleId());
        }
        Map<UUID, Document> docs = new HashMap<>();
        for (Document d : documentRepo.findAllById(docIds)) docs.put(d.getId(), d);
        Map<UUID, User> users = new HashMap<>();
        for (User u : userRepo.findAllById(userIds)) users.put(u.getId(), u);
        Map<UUID, Project> projects = new HashMap<>();
        for (Project p : projectRepo.findAllById(projectIds)) projects.put(p.getId(), p);
        Map<UUID, InternLifecycle> lifecycles = new HashMap<>();
        for (InternLifecycle lc : lifecycleRepo.findAllById(lifecycleIds)) {
            lifecycles.put(lc.getId(), lc);
        }

        Instant now = Instant.now();
        List<ManagerRecordingApprovalDtos.PendingRow> rows = new ArrayList<>(all.size());
        for (InternEvaluation e : all) {
            Document doc = docs.get(e.getRecordingDocumentId());
            if (doc == null || doc.getDeletedAt() != null) continue;
            User intern = users.get(e.getInternId());
            User evaluator = users.get(e.getEvaluatorId());
            Project project = e.getLinkedProjectId() != null
                    ? projects.get(e.getLinkedProjectId()) : null;
            InternLifecycle lc = e.getInternLifecycleId() != null
                    ? lifecycles.get(e.getInternLifecycleId()) : null;
            String monthYear = e.getPeriodStart() != null
                    ? String.format("%d-%02d",
                        e.getPeriodStart().getYear(), e.getPeriodStart().getMonthValue())
                    : "unknown";
            long hoursWaiting = ChronoUnit.HOURS.between(doc.getCreatedAt(), now);
            rows.add(new ManagerRecordingApprovalDtos.PendingRow(
                    e.getId(),
                    e.getRecordingDocumentId(),
                    monthYear,
                    e.getEvaluationType(),
                    e.getRecordingScope(),
                    e.getInternId(),
                    intern != null ? intern.getFullName() : null,
                    lc != null ? lc.getEmployeeId() : null,
                    e.getLinkedProjectId(),
                    project != null
                            ? (project.getName() != null ? project.getName() : project.getTitle())
                            : null,
                    e.getEvaluatorId(),
                    evaluator != null ? evaluator.getFullName() : null,
                    doc.getFileName(),
                    doc.getFileSize(),
                    doc.getCreatedAt(),
                    e.getRecordingApprovalStatus(),
                    hoursWaiting));
        }
        // FIFO — oldest waiting first so nothing rots at the bottom.
        rows.sort(Comparator.comparing(
                ManagerRecordingApprovalDtos.PendingRow::uploadedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return new ManagerRecordingApprovalDtos.PendingListResponse(rows, rows.size());
    }

    @Transactional
    public void approve(UUID evaluationId, User manager) {
        InternEvaluation ev = mustGetWithRecording(evaluationId);
        String status = ev.getRecordingApprovalStatus();
        if (EvaluationRecordingService.STATUS_APPROVED.equals(status)) return; // idempotent
        if (!EvaluationRecordingService.STATUS_PENDING.equals(status)) {
            throw new BadRequestException(
                    "Recording is not awaiting approval (status=" + status + ")");
        }
        ev.setRecordingApprovalStatus(EvaluationRecordingService.STATUS_APPROVED);
        ev.setRecordingApprovedBy(manager.getId());
        ev.setRecordingApprovedAt(Instant.now());
        ev.setRecordingRevisionNotes(null);
        evalRepo.save(ev);
        log.info("[RecordingApproval] APPROVED eval={} by manager={}",
                evaluationId, manager.getId());
        notifyEvaluator(ev,
                "RECORDING_APPROVED",
                "Recording approved",
                "Your session recording has been approved and is now visible.",
                "/careers/evaluator/evaluations/" + evaluationId + "/compose");
    }

    @Transactional
    public void requestRevision(
            UUID evaluationId,
            ManagerRecordingApprovalDtos.RequestRevisionRequest req,
            User manager) {
        if (req == null || req.notes() == null || req.notes().trim().length() < 10) {
            throw new BadRequestException(
                    "Revision notes are required (min 10 chars) so the "
                            + "evaluator knows what to fix.");
        }
        InternEvaluation ev = mustGetWithRecording(evaluationId);
        String status = ev.getRecordingApprovalStatus();
        if (!EvaluationRecordingService.STATUS_PENDING.equals(status)) {
            throw new BadRequestException(
                    "Recording is not awaiting approval (status=" + status + ")");
        }
        String notes = req.notes().trim();
        if (notes.length() > 4000) notes = notes.substring(0, 4000);
        ev.setRecordingApprovalStatus(EvaluationRecordingService.STATUS_REVISION);
        ev.setRecordingApprovedBy(manager.getId());
        ev.setRecordingApprovedAt(Instant.now());
        ev.setRecordingRevisionNotes(notes);
        evalRepo.save(ev);
        log.info("[RecordingApproval] REVISION_REQUESTED eval={} by manager={} notesLen={}",
                evaluationId, manager.getId(), notes.length());
        notifyEvaluator(ev,
                "RECORDING_REVISION_REQUESTED",
                "Recording needs revision",
                "Manager notes: " + (notes.length() > 200 ? notes.substring(0, 200) + "…" : notes),
                "/careers/evaluator/evaluations/" + evaluationId + "/compose");
    }

    private InternEvaluation mustGetWithRecording(UUID id) {
        InternEvaluation ev = evalRepo.findById(id).orElseThrow(() ->
                new ResourceNotFoundException("Evaluation not found: " + id));
        if (ev.getRecordingDocumentId() == null) {
            throw new BadRequestException("Evaluation has no recording attached.");
        }
        return ev;
    }

    private void notifyEvaluator(
            InternEvaluation ev, String eventType, String title, String body, String actionUrl) {
        try {
            notifier.dispatch(
                    ev.getEvaluatorId(),
                    eventType,
                    ev.getInternId(),
                    title, body, actionUrl, false);
        } catch (Exception e) {
            log.warn("[RecordingApproval] evaluator notify failed for eval={}: {}",
                    ev.getId(), e.getMessage());
        }
    }
}
