package com.anvicorp.api.intern;

import com.anvicorp.api.dto.doubt.DoubtDtos;
import com.anvicorp.api.entity.Document;
import com.anvicorp.api.entity.DoubtRequest;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.meeting.MeetingProvider;
import com.anvicorp.api.integration.meeting.MeetingRequest;
import com.anvicorp.api.integration.meeting.MeetingResponse;
import com.anvicorp.api.notification.DoubtNotificationDispatcher;
import com.anvicorp.api.repository.DocumentRepository;
import com.anvicorp.api.repository.DoubtRequestRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.trainer.TrainerScopeGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Doubt-session feature core. Reuses:
 * <ul>
 *   <li>{@link OrgTeamResolver} to resolve the intern's Trainer (with the
 *       org-singleton DEFAULT_TRAINER_EMAIL fallback) at create time.</li>
 *   <li>{@link MeetingProvider} for live-session Zoom create (same seam
 *       as KT / weekly meetings).</li>
 *   <li>{@link TrainerScopeGuard} for trainer-side action gating.</li>
 *   <li>{@link DocumentRepository} to validate attachment ownership.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DoubtRequestService {

    private final DoubtRequestRepository doubtRepository;
    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final DocumentRepository documentRepository;
    private final OrgTeamResolver orgTeamResolver;
    private final TrainerScopeGuard trainerScopeGuard;
    private final MeetingProvider meetingProvider;
    private final DoubtNotificationDispatcher notifier;

    // ── Intern create ────────────────────────────────────────────────────

    @Transactional
    public DoubtDtos.DoubtResponse createForIntern(DoubtDtos.CreateDoubtRequest req,
                                                    User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        if (req == null) throw new BadRequestException("body required");
        String text = req.text() != null ? req.text().trim() : "";
        if (text.isEmpty()) throw new BadRequestException("text is required");
        if (text.length() > 4000) {
            throw new BadRequestException("text must be 4000 characters or fewer");
        }

        InternLifecycle lc = lifecycleRepository.findByUserId(caller.getId())
                .orElseThrow(() -> new ForbiddenException(
                        "Doubts can only be raised by an active intern."));
        if (!"ACTIVE".equals(lc.getActiveStatus())) {
            throw new ForbiddenException(
                    "Doubts can only be raised once your internship is ACTIVE.");
        }

        UUID trainerId = orgTeamResolver.resolveTrainerId(lc);
        if (trainerId == null) {
            throw new BadRequestException(
                    "No Trainer is configured for your account yet. Reach out to ERM.");
        }

        if (req.projectId() != null) {
            Project p = projectRepository.findById(req.projectId()).orElse(null);
            if (p == null) {
                throw new BadRequestException("Unknown projectId");
            }
        }

        if (req.attachmentDocumentId() != null) {
            Document doc = documentRepository.findById(req.attachmentDocumentId())
                    .orElse(null);
            if (doc == null) {
                throw new BadRequestException("Attachment not found");
            }
            if (!caller.getId().equals(doc.getOwnerUserId())) {
                throw new ForbiddenException(
                        "Attachment belongs to a different user.");
            }
        }

        DoubtRequest d = DoubtRequest.builder()
                .internUserId(caller.getId())
                .trainerUserId(trainerId)
                .internLifecycleId(lc.getId())
                .projectId(req.projectId())
                .projectAssignmentId(req.projectAssignmentId())
                .text(text)
                .attachmentDocumentId(req.attachmentDocumentId())
                .status("PENDING")
                .build();
        d = doubtRepository.save(d);
        log.info("[DoubtRequest] created id={} intern={} trainer={} project={}",
                d.getId(), caller.getId(), trainerId, req.projectId());

        notifier.dispatchRaised(d);
        return toResponse(d, /*includeStartUrl=*/false);
    }

    // ── Intern list ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DoubtDtos.DoubtResponse> listMine(User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        return doubtRepository.findByInternUserIdOrderByCreatedAtDesc(caller.getId())
                .stream()
                .map(d -> toResponse(d, /*includeStartUrl=*/false))
                .toList();
    }

    @Transactional(readOnly = true)
    public DoubtDtos.DoubtResponse getOneForIntern(UUID id, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        DoubtRequest d = doubtRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doubt not found: " + id));
        if (!caller.getId().equals(d.getInternUserId())) {
            throw new ForbiddenException("Not your doubt.");
        }
        return toResponse(d, false);
    }

    // ── Trainer list ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DoubtDtos.DoubtResponse> listForTrainer(User caller, boolean openOnly) {
        ensureTrainer(caller);
        List<DoubtRequest> rows = openOnly
                ? doubtRepository.findByTrainerUserIdAndStatusInOrderByCreatedAtDesc(
                        caller.getId(),
                        List.of("PENDING", "REPLIED", "SESSION_SCHEDULED"))
                : doubtRepository.findByTrainerUserIdOrderByCreatedAtDesc(caller.getId());
        return rows.stream()
                .map(d -> toResponse(d, /*includeStartUrl=*/true))
                .toList();
    }

    @Transactional(readOnly = true)
    public DoubtDtos.DoubtResponse getOneForTrainer(UUID id, User caller) {
        DoubtRequest d = loadForTrainer(id, caller);
        return toResponse(d, true);
    }

    // ── Trainer actions ──────────────────────────────────────────────────

    @Transactional
    public DoubtDtos.DoubtResponse reply(UUID id, DoubtDtos.ReplyRequest req, User caller) {
        if (req == null || req.reply() == null || req.reply().trim().isEmpty()) {
            throw new BadRequestException("reply is required");
        }
        DoubtRequest d = loadForTrainer(id, caller);
        if ("RESOLVED".equals(d.getStatus())) {
            throw new BadRequestException("This doubt is already resolved.");
        }
        d.setTrainerReply(req.reply().trim());
        d.setRepliedAt(Instant.now());
        d.setRepliedById(caller.getId());
        // SESSION_SCHEDULED beats REPLIED — keep the higher state.
        if ("PENDING".equals(d.getStatus())) {
            d.setStatus("REPLIED");
        }
        d = doubtRepository.save(d);
        notifier.dispatchReplied(d, caller);
        return toResponse(d, true);
    }

    @Transactional
    public DoubtDtos.DoubtResponse scheduleSession(UUID id,
                                                    DoubtDtos.ScheduleSessionRequest req,
                                                    User caller) {
        if (req == null || req.scheduledFor() == null) {
            throw new BadRequestException("scheduledFor is required");
        }
        if (req.scheduledFor().isBefore(Instant.now().minus(Duration.ofMinutes(5)))) {
            throw new BadRequestException("scheduledFor must be in the future");
        }
        DoubtRequest d = loadForTrainer(id, caller);
        if ("RESOLVED".equals(d.getStatus())) {
            throw new BadRequestException("This doubt is already resolved.");
        }

        int duration = req.durationMinutes() == null ? 30
                : Math.max(15, Math.min(180, req.durationMinutes()));
        String tz = (req.timezone() == null || req.timezone().isBlank())
                ? "UTC" : req.timezone();
        String topic = req.topic() != null && !req.topic().isBlank()
                ? req.topic() : "Doubt-clearing session";
        String agenda = req.agenda();

        // Create Zoom (best-effort — mirrors WeeklyMeetingService.schedule).
        String zoomId = null, joinUrl = null, startUrl = null, password = null;
        if (meetingProvider.isReady()) {
            try {
                String hostId = caller.getZoomEmail() != null
                        && !caller.getZoomEmail().isBlank()
                        ? caller.getZoomEmail() : "me";
                MeetingResponse z = meetingProvider.createMeeting(
                        new MeetingRequest(hostId, topic, req.scheduledFor(),
                                duration, tz, agenda));
                zoomId = z.providerMeetingId();
                joinUrl = z.joinUrl();
                startUrl = z.startUrl();
                password = z.password();
                log.info("[DoubtRequest] {} session-meeting created id={} for doubt={}",
                        meetingProvider.providerName(), zoomId, d.getId());
            } catch (Exception e) {
                log.warn("[DoubtRequest] {} session-meeting create failed (non-fatal): {}",
                        meetingProvider.providerName(), e.getMessage());
            }
        }

        d.setSessionScheduledFor(req.scheduledFor());
        d.setSessionDurationMinutes(duration);
        d.setSessionTimezone(tz);
        d.setZoomMeetingId(zoomId);
        d.setZoomJoinUrl(joinUrl);
        d.setZoomStartUrl(startUrl);
        d.setZoomPassword(password);
        d.setStatus("SESSION_SCHEDULED");
        d = doubtRepository.save(d);

        notifier.dispatchSessionScheduled(d, caller);
        return toResponse(d, true);
    }

    @Transactional
    public DoubtDtos.DoubtResponse resolve(UUID id, User caller) {
        DoubtRequest d = loadForTrainer(id, caller);
        if ("RESOLVED".equals(d.getStatus())) {
            return toResponse(d, true);
        }
        d.setResolvedAt(Instant.now());
        d.setResolvedById(caller.getId());
        d.setStatus("RESOLVED");
        d = doubtRepository.save(d);
        notifier.dispatchResolved(d, caller);
        return toResponse(d, true);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private DoubtRequest loadForTrainer(UUID id, User caller) {
        ensureTrainer(caller);
        DoubtRequest d = doubtRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doubt not found: " + id));
        InternLifecycle lc = lifecycleRepository.findById(d.getInternLifecycleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Lifecycle missing for doubt " + id));
        trainerScopeGuard.requireTrainerOwnership(lc, caller);
        return d;
    }

    private static void ensureTrainer(User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        if (caller.getRoles() == null
                || (!caller.getRoles().contains(UserRole.TRAINER)
                    && !caller.getRoles().contains(UserRole.SUPER_ADMIN))) {
            throw new ForbiddenException("TRAINER role required");
        }
    }

    private DoubtDtos.DoubtResponse toResponse(DoubtRequest d, boolean includeStartUrl) {
        // List.of(...) throws NullPointerException on null elements. On a
        // freshly-created (PENDING) doubt repliedById + resolvedById are
        // both null, which would NPE here. Use Arrays.asList — which
        // permits nulls — and let bulkNames filter them out.
        Map<UUID, String> names = bulkNames(java.util.Arrays.asList(
                d.getInternUserId(), d.getTrainerUserId(),
                d.getRepliedById(), d.getResolvedById()));
        String internName = names.get(d.getInternUserId());
        String trainerName = names.get(d.getTrainerUserId());
        String repliedByName = names.get(d.getRepliedById());
        String resolvedByName = names.get(d.getResolvedById());

        String projectTitle = null;
        if (d.getProjectId() != null) {
            projectTitle = projectRepository.findById(d.getProjectId())
                    .map(Project::getTitle).orElse(null);
        }

        String attachmentFileName = null;
        if (d.getAttachmentDocumentId() != null) {
            attachmentFileName = documentRepository.findById(d.getAttachmentDocumentId())
                    .map(Document::getFileName).orElse(null);
        }

        return new DoubtDtos.DoubtResponse(
                d.getId(),
                d.getInternUserId(), internName,
                d.getTrainerUserId(), trainerName,
                d.getProjectId(), projectTitle,
                d.getProjectAssignmentId(),
                d.getText(),
                d.getAttachmentDocumentId(), attachmentFileName,
                d.getStatus(),
                d.getTrainerReply(), d.getRepliedAt(), repliedByName,
                d.getZoomMeetingId(),
                d.getZoomJoinUrl(),
                includeStartUrl ? d.getZoomStartUrl() : null,
                includeStartUrl ? d.getZoomPassword() : null,
                d.getSessionScheduledFor(),
                d.getSessionDurationMinutes(),
                d.getSessionTimezone(),
                d.getResolvedAt(), resolvedByName,
                d.getCreatedAt(), d.getUpdatedAt());
    }

    private Map<UUID, String> bulkNames(List<UUID> ids) {
        List<UUID> nonNull = new ArrayList<>();
        for (UUID id : ids) if (id != null && !nonNull.contains(id)) nonNull.add(id);
        Map<UUID, String> out = new HashMap<>();
        if (nonNull.isEmpty()) return out;
        for (User u : userRepository.findAllById(nonNull)) {
            if (u.getId() != null) out.put(u.getId(), u.getFullName());
        }
        return out;
    }
}
