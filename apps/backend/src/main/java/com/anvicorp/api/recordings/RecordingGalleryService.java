package com.anvicorp.api.recordings;

import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.entity.InternEvaluation;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.evaluator.recordings.EvaluationRecordingService;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.InternEvaluationRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.InterviewRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Unified Recording Gallery — builds one tree of intern → type → month
 * → project → files across INTERVIEW_RECORDING + EVALUATION_RECORDING
 * documents, filtered per caller role. The evaluator only ever sees
 * their OWN evaluations at status APPROVED; ERM + MANAGER see
 * everything at any status; interviews are entirely hidden from the
 * evaluator role.
 *
 * <p>Playback happens through per-type presigned-download endpoints
 * (see {@code downloadUrlPath} on each file), so no bytes flow through
 * this service.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecordingGalleryService {

    private final InternLifecycleRepository lifecycleRepo;
    private final UserRepository userRepo;
    private final CandidateRepository candidateRepo;
    private final DocumentRepository documentRepo;
    private final InterviewRepository interviewRepo;
    private final InternEvaluationRepository evaluationRepo;
    private final ProjectRepository projectRepo;

    @Transactional(readOnly = true)
    public RecordingGalleryDtos.GalleryResponse buildTree(User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        Set<UserRole> roles = caller.getRoles() == null
                ? java.util.Collections.emptySet() : caller.getRoles();
        boolean canSeeAll = roles.contains(UserRole.SUPER_ADMIN)
                || roles.contains(UserRole.ERM)
                || roles.contains(UserRole.MANAGER);
        boolean isEvaluator = roles.contains(UserRole.EVALUATOR);
        if (!canSeeAll && !isEvaluator) {
            throw new ForbiddenException("Role not permitted to view the recording gallery.");
        }

        // Load every active lifecycle up front so we can group under
        // "intern folders" including interns with no recordings (rendered
        // clean empty in the UI) — the file explorer wants a stable
        // intern list, not just interns that happen to have files.
        List<InternLifecycle> lifecycles = lifecycleRepo
                .findByActiveStatusOrderByEmployeeIdAsc("ACTIVE");
        Map<UUID, InternLifecycle> lifecycleByUserId = new HashMap<>();
        Set<UUID> internUserIds = new HashSet<>();
        for (InternLifecycle lc : lifecycles) {
            if (lc.getUserId() != null) {
                lifecycleByUserId.put(lc.getUserId(), lc);
                internUserIds.add(lc.getUserId());
            }
        }
        Map<UUID, User> usersById = new HashMap<>();
        for (User u : userRepo.findAllById(internUserIds)) usersById.put(u.getId(), u);

        // ── Evaluations with recordings ─────────────────────────────────
        List<InternEvaluation> evals = evaluationRepo.findAll().stream()
                .filter(e -> e.getRecordingDocumentId() != null)
                .filter(e -> !isEvaluator || (
                        caller.getId().equals(e.getEvaluatorId())
                                && EvaluationRecordingService.STATUS_APPROVED
                                        .equals(e.getRecordingApprovalStatus())))
                .toList();

        Set<UUID> docIds = new HashSet<>();
        Set<UUID> projectIds = new HashSet<>();
        Set<UUID> evaluatorIds = new HashSet<>();
        for (InternEvaluation e : evals) {
            docIds.add(e.getRecordingDocumentId());
            evaluatorIds.add(e.getEvaluatorId());
            if (e.getLinkedProjectId() != null) projectIds.add(e.getLinkedProjectId());
        }

        // ── Interview recordings (hidden for evaluator) ─────────────────
        List<Interview> interviews = isEvaluator
                ? List.of()
                : interviewRepo.findAll().stream()
                    .filter(iv -> iv.getRecordingDocumentId() != null)
                    .toList();
        for (Interview iv : interviews) docIds.add(iv.getRecordingDocumentId());

        // Standalone interview recordings — stored as Documents with
        // category=INTERVIEW_RECORDING and ownerUserId = intern.userId.
        // (The scorecard-flow interview recordings set ownerUserId to
        // the ERM, so this filter isolates the standalone ones.)
        List<Document> standaloneInterviews = isEvaluator
                ? List.of()
                : documentRepo.findAll().stream()
                    .filter(d -> d.getDeletedAt() == null)
                    .filter(d -> ErmStandaloneRecordingService.CATEGORY_STANDALONE_INTERVIEW
                            .equals(d.getCategory()))
                    .filter(d -> d.getOwnerUserId() != null
                            && internUserIds.contains(d.getOwnerUserId()))
                    .toList();
        for (Document d : standaloneInterviews) docIds.add(d.getId());

        // Bulk-load referenced docs + evaluators + projects (single pass each).
        Map<UUID, Document> docs = new HashMap<>();
        for (Document d : documentRepo.findAllById(docIds)) {
            if (d.getDeletedAt() == null) docs.put(d.getId(), d);
        }
        Map<UUID, User> evaluatorsById = new HashMap<>();
        for (User u : userRepo.findAllById(evaluatorIds)) evaluatorsById.put(u.getId(), u);
        Map<UUID, Project> projectsById = new HashMap<>();
        for (Project p : projectRepo.findAllById(projectIds)) projectsById.put(p.getId(), p);

        // Group by intern userId → build the tree.
        Map<UUID, InternBuilder> perIntern = new HashMap<>();
        // seed intern folders so the UI can render "no recordings yet"
        // cleanly for every active intern the caller has scope on.
        for (UUID internUserId : internUserIds) {
            perIntern.put(internUserId, new InternBuilder(internUserId));
        }

        // Interview recordings from scorecard flow (candidateId → user).
        if (!interviews.isEmpty()) {
            for (Interview iv : interviews) {
                Document doc = docs.get(iv.getRecordingDocumentId());
                if (doc == null) continue;
                UUID subjectUserId = resolveInterviewSubjectUserId(iv);
                if (subjectUserId == null) continue;
                InternBuilder b = perIntern.computeIfAbsent(subjectUserId, InternBuilder::new);
                b.interviewFiles.add(toInterviewFile(iv.getId(), doc, resolveUserName(doc.getUploadedById())));
            }
        }
        // Standalone interview recordings — ownerUserId = intern.
        for (Document doc : standaloneInterviews) {
            InternBuilder b = perIntern.computeIfAbsent(doc.getOwnerUserId(), InternBuilder::new);
            b.interviewFiles.add(toInterviewFile(null, doc, resolveUserName(doc.getUploadedById())));
        }
        // Evaluation recordings.
        for (InternEvaluation e : evals) {
            Document doc = docs.get(e.getRecordingDocumentId());
            if (doc == null) continue;
            InternBuilder b = perIntern.computeIfAbsent(e.getInternId(), InternBuilder::new);
            String monthYear = monthYearOf(e);
            Project project = e.getLinkedProjectId() != null
                    ? projectsById.get(e.getLinkedProjectId()) : null;
            String scope = e.getRecordingScope() != null ? e.getRecordingScope() : "P1";
            String projectTitle = project != null
                    ? (project.getName() != null ? project.getName() : project.getTitle())
                    : (scope.equals("P1_P2") ? "Combined P1 + P2" : "(unknown project)");
            String uploadedByName = evaluatorsById.get(e.getEvaluatorId()) != null
                    ? evaluatorsById.get(e.getEvaluatorId()).getFullName() : null;
            b.putEvaluation(monthYear, scope, e.getLinkedProjectId(), projectTitle,
                    new RecordingGalleryDtos.RecordingFile(
                            e.getId(),
                            doc.getId(),
                            doc.getFileName(),
                            doc.getMimeType(),
                            doc.getFileSize(),
                            doc.getCreatedAt(),
                            uploadedByName,
                            e.getRecordingApprovalStatus(),
                            e.getRecordingScope(),
                            "/api/v1/evaluation-recordings/" + e.getId() + "/download-url"));
        }

        // Assemble sorted output.
        List<RecordingGalleryDtos.InternFolder> out = new ArrayList<>(perIntern.size());
        for (InternBuilder b : perIntern.values()) {
            if (b.interviewFiles.isEmpty() && b.monthMap.isEmpty()) continue;
            InternLifecycle lc = lifecycleByUserId.get(b.internUserId);
            User u = usersById.get(b.internUserId);
            out.add(b.build(u, lc));
        }
        out.sort(Comparator.comparing(
                RecordingGalleryDtos.InternFolder::internName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return new RecordingGalleryDtos.GalleryResponse(out);
    }

    private String monthYearOf(InternEvaluation e) {
        if (e.getPeriodStart() != null) {
            return String.format("%d-%02d",
                    e.getPeriodStart().getYear(), e.getPeriodStart().getMonthValue());
        }
        return "unknown";
    }

    private RecordingGalleryDtos.RecordingFile toInterviewFile(
            UUID interviewId, Document doc, String uploadedByName) {
        // Interview recordings play through a per-Interview presign endpoint
        // wrapped by the standalone controller when interviewId is null.
        String downloadPath = interviewId != null
                ? "/api/v1/interview-recordings/" + interviewId + "/download-url"
                : "/api/v1/interview-recordings/by-document/" + doc.getId() + "/download-url";
        return new RecordingGalleryDtos.RecordingFile(
                null, doc.getId(), doc.getFileName(),
                doc.getMimeType(), doc.getFileSize(),
                doc.getCreatedAt(), uploadedByName,
                null, null, downloadPath);
    }

    private UUID resolveInterviewSubjectUserId(Interview iv) {
        try {
            if (iv.getApplication() != null && iv.getApplication().getCandidate() != null) {
                var c = iv.getApplication().getCandidate();
                return c.getUser() != null ? c.getUser().getId() : null;
            }
        } catch (Exception e) {
            log.debug("[RecordingGallery] interview subject resolve failed for iv={}: {}",
                    iv.getId(), e.getMessage());
        }
        return null;
    }

    private String resolveUserName(UUID userId) {
        if (userId == null) return null;
        return userRepo.findById(userId).map(User::getFullName).orElse(null);
    }

    /** Per-intern accumulator — a private builder so the tree-shaping
     *  loop stays readable. */
    private static class InternBuilder {
        final UUID internUserId;
        final List<RecordingGalleryDtos.RecordingFile> interviewFiles = new ArrayList<>();
        // monthYear → (scopeKey → project folder builder)
        final Map<String, Map<String, ProjectFolderBuilder>> monthMap = new HashMap<>();

        InternBuilder(UUID internUserId) { this.internUserId = internUserId; }

        void putEvaluation(String monthYear, String scope, UUID projectId,
                           String projectTitle, RecordingGalleryDtos.RecordingFile file) {
            String scopeKey = scope + "|" + (projectId != null ? projectId : "");
            monthMap.computeIfAbsent(monthYear, k -> new HashMap<>())
                    .computeIfAbsent(scopeKey, k -> new ProjectFolderBuilder(scope, projectId, projectTitle))
                    .files.add(file);
        }

        RecordingGalleryDtos.InternFolder build(User u, InternLifecycle lc) {
            interviewFiles.sort(Comparator.comparing(
                    RecordingGalleryDtos.RecordingFile::uploadedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            List<RecordingGalleryDtos.MonthFolder> months = monthMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Map<String, ProjectFolderBuilder>>comparingByKey().reversed())
                    .map(monthEntry -> {
                        List<RecordingGalleryDtos.ProjectFolder> projects = monthEntry.getValue().values()
                                .stream()
                                .sorted(Comparator.comparing(pf -> pf.scope))
                                .map(pf -> {
                                    pf.files.sort(Comparator.comparing(
                                            RecordingGalleryDtos.RecordingFile::uploadedAt,
                                            Comparator.nullsLast(Comparator.reverseOrder())));
                                    return new RecordingGalleryDtos.ProjectFolder(
                                            pf.scope, pf.projectId, pf.projectTitle,
                                            new ArrayList<>(pf.files));
                                })
                                .collect(Collectors.toList());
                        return new RecordingGalleryDtos.MonthFolder(
                                monthEntry.getKey(), projects);
                    })
                    .collect(Collectors.toList());
            int total = interviewFiles.size()
                    + months.stream().flatMap(m -> m.projects().stream())
                            .mapToInt(p -> p.files().size()).sum();
            return new RecordingGalleryDtos.InternFolder(
                    internUserId,
                    u != null ? u.getFullName() : null,
                    lc != null ? lc.getEmployeeId() : null,
                    new ArrayList<>(interviewFiles),
                    months,
                    total);
        }
    }

    private static class ProjectFolderBuilder {
        final String scope;
        final UUID projectId;
        final String projectTitle;
        final List<RecordingGalleryDtos.RecordingFile> files = new ArrayList<>();
        ProjectFolderBuilder(String scope, UUID projectId, String projectTitle) {
            this.scope = scope;
            this.projectId = projectId;
            this.projectTitle = projectTitle;
        }
    }
}
