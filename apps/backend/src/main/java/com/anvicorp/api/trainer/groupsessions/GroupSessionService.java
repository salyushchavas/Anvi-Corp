package com.anvicorp.api.trainer.groupsessions;

import com.anvicorp.api.config.BrandConfig;
import com.anvicorp.api.entity.GroupSession;
import com.anvicorp.api.entity.GroupSessionParticipant;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.integration.meeting.MeetingProvider;
import com.anvicorp.api.integration.meeting.MeetingRequest;
import com.anvicorp.api.integration.meeting.MeetingResponse;
import com.anvicorp.api.notification.InternNotificationService;
import com.anvicorp.api.repository.GroupSessionParticipantRepository;
import com.anvicorp.api.repository.GroupSessionRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.trainer.TrainerScopeGuard;
import com.anvicorp.api.trainer.groupsessions.GroupSessionDtos.CreateGroupSessionRequest;
import com.anvicorp.api.trainer.groupsessions.GroupSessionDtos.InternGroupSessionResponse;
import com.anvicorp.api.trainer.groupsessions.GroupSessionDtos.ParticipantRef;
import com.anvicorp.api.trainer.groupsessions.GroupSessionDtos.TrainerGroupSessionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Trainer-hosted group sessions — one Zoom meeting for many interns.
 *
 * <p>Same shape as {@code QaSessionService.schedule}: reuse
 * {@link MeetingProvider#createMeeting} for the Zoom link, persist the
 * host start URL on the row (never surfaced to interns), and loop over
 * every participant to notify via {@link InternNotificationService}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupSessionService {

    private static final int MIN_DURATION = 15;
    private static final int MAX_DURATION = 240;
    private static final int DEFAULT_DURATION = 60;
    private static final int MAX_PARTICIPANTS = 200;

    private final GroupSessionRepository sessionRepository;
    private final GroupSessionParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final InternLifecycleRepository internLifecycleRepository;
    private final MeetingProvider meetingProvider;
    private final InternNotificationService internNotifications;
    private final TrainerScopeGuard trainerScopeGuard;
    private final BrandConfig brand;

    // ── Trainer-side ───────────────────────────────────────────────────────

    @Transactional
    public TrainerGroupSessionResponse create(CreateGroupSessionRequest req, User caller) {
        requireTrainerOrSuperAdmin(caller);
        if (req == null) throw new BadRequestException("body required");
        if (req.scheduledAt() == null) {
            throw new BadRequestException("scheduledAt is required");
        }
        if (req.scheduledAt().isBefore(Instant.now().minusSeconds(60))) {
            throw new BadRequestException("scheduledAt cannot be in the past");
        }
        String topic = req.topic() == null ? "" : req.topic().trim();
        if (topic.isEmpty()) {
            throw new BadRequestException("topic is required");
        }
        String tz = req.meetingTimezone() == null ? "" : req.meetingTimezone().trim();
        if (tz.isEmpty()) {
            throw new BadRequestException("meetingTimezone is required");
        }
        int duration = req.durationMinutes() == null
                ? DEFAULT_DURATION
                : Math.max(MIN_DURATION, Math.min(MAX_DURATION, req.durationMinutes()));

        List<UUID> lifecycleIds = req.participantLifecycleIds() == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(req.participantLifecycleIds()));
        if (lifecycleIds.isEmpty()) {
            throw new BadRequestException("Select at least one intern.");
        }
        if (lifecycleIds.size() > MAX_PARTICIPANTS) {
            throw new BadRequestException(
                    "A group session cannot have more than " + MAX_PARTICIPANTS + " participants.");
        }

        // Resolve lifecycle → user + validate each: exists, ACTIVE, has
        // INTERN role, and (for non-SUPER_ADMIN trainers) sits in this
        // trainer's roster via the shared TrainerScopeGuard.
        boolean isSuperAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        List<InternLifecycle> lifecycles = internLifecycleRepository.findAllById(lifecycleIds);
        Map<UUID, InternLifecycle> lcById = new HashMap<>();
        for (InternLifecycle lc : lifecycles) lcById.put(lc.getId(), lc);
        List<UUID> internIds = new ArrayList<>();
        for (UUID lcId : lifecycleIds) {
            InternLifecycle lc = lcById.get(lcId);
            if (lc == null) {
                throw new BadRequestException("Intern lifecycle not found: " + lcId);
            }
            if (!"ACTIVE".equals(lc.getActiveStatus())) {
                throw new BadRequestException(
                        "Intern is not active (lifecycle=" + lcId + ")");
            }
            if (!isSuperAdmin) trainerScopeGuard.requireTrainerOwnership(lc, caller);
            if (lc.getUserId() == null) {
                throw new BadRequestException(
                        "Intern lifecycle has no linked user: " + lcId);
            }
            internIds.add(lc.getUserId());
        }
        Map<UUID, User> byId = new HashMap<>();
        for (User u : userRepository.findAllById(internIds)) byId.put(u.getId(), u);
        for (UUID id : internIds) {
            User u = byId.get(id);
            if (u == null) {
                throw new BadRequestException("Participant user not found: " + id);
            }
            if (u.getRoles() == null || !u.getRoles().contains(UserRole.INTERN)) {
                throw new BadRequestException(
                        "Participant is not an intern: " + u.getFullName());
            }
        }

        // Best-effort Zoom creation. Same pattern as QaSessionService: a
        // Zoom outage falls back to persisting the session without links
        // so the trainer can add them out-of-band later.
        String zoomId = null, joinUrl = null, startUrl = null, password = null;
        if (meetingProvider.isReady()) {
            try {
                String hostId = caller.getZoomEmail() != null
                        && !caller.getZoomEmail().isBlank()
                        ? caller.getZoomEmail() : "me";
                MeetingResponse z = meetingProvider.createMeeting(
                        new MeetingRequest(hostId, topic, req.scheduledAt(),
                                duration, tz, req.description()));
                zoomId = z.providerMeetingId();
                joinUrl = z.joinUrl();
                startUrl = z.startUrl();
                password = z.password();
                log.info("[GroupSession] {} meeting created id={} for trainer={} participants={}",
                        meetingProvider.providerName(), zoomId, caller.getId(), internIds.size());
            } catch (Exception e) {
                log.warn("[GroupSession] {} meeting create failed (non-fatal): {}",
                        meetingProvider.providerName(), e.getMessage());
            }
        }

        GroupSession session = GroupSession.builder()
                .trainerId(caller.getId())
                .topic(topic)
                .description(req.description() != null && !req.description().isBlank()
                        ? req.description().trim() : null)
                .scheduledAt(req.scheduledAt())
                .meetingTimezone(tz)
                .durationMinutes(duration)
                .zoomMeetingId(zoomId)
                .zoomJoinUrl(joinUrl)
                .zoomStartUrl(startUrl)
                .zoomPassword(password)
                .status("SCHEDULED")
                .build();
        session = sessionRepository.save(session);

        for (UUID id : internIds) {
            GroupSessionParticipant p = GroupSessionParticipant.builder()
                    .groupSession(session)
                    .internUserId(id)
                    .build();
            participantRepository.save(p);
        }

        // Notify every participant (best-effort per intern).
        for (UUID id : internIds) {
            try {
                notifyParticipantOfSchedule(id, session, byId.get(id), caller);
            } catch (Exception e) {
                log.warn("[GroupSession] notify participant {} failed (non-fatal): {}",
                        id, e.getMessage());
            }
        }

        return toTrainerResponse(session, buildParticipantRefs(internIds, byId));
    }

    @Transactional(readOnly = true)
    public List<TrainerGroupSessionResponse> listForTrainer(User caller) {
        requireTrainerOrSuperAdmin(caller);
        // SUPER_ADMIN sees only their own hosted sessions here — cross-trainer
        // visibility is a separate concern we defer to a future admin view.
        List<GroupSession> rows = sessionRepository
                .findByTrainerIdOrderByScheduledAtDesc(caller.getId());
        return rows.stream().map(this::toTrainerResponseWithParticipants).toList();
    }

    @Transactional(readOnly = true)
    public TrainerGroupSessionResponse getDetail(UUID sessionId, User caller) {
        GroupSession session = loadSession(sessionId);
        requireHostOrSuperAdmin(session, caller);
        return toTrainerResponseWithParticipants(session);
    }

    @Transactional
    public TrainerGroupSessionResponse cancel(UUID sessionId, User caller) {
        GroupSession session = loadSession(sessionId);
        requireHostOrSuperAdmin(session, caller);
        if ("CANCELLED".equals(session.getStatus())) {
            return toTrainerResponseWithParticipants(session);
        }
        if ("COMPLETED".equals(session.getStatus())) {
            throw new BadRequestException("Session is already completed and cannot be cancelled.");
        }
        session.setStatus("CANCELLED");
        session.setCancelledAt(Instant.now());
        sessionRepository.save(session);

        // Best-effort delete on the Zoom side so the meeting doesn't sit
        // orphaned. A delete failure isn't fatal — the row is already
        // marked CANCELLED and interns will see the state change.
        if (session.getZoomMeetingId() != null && meetingProvider.isReady()) {
            try {
                meetingProvider.deleteMeeting(session.getZoomMeetingId());
            } catch (Exception e) {
                log.warn("[GroupSession] provider deleteMeeting failed (non-fatal) session={}: {}",
                        session.getId(), e.getMessage());
            }
        }

        List<GroupSessionParticipant> parts = participantRepository.findByGroupSession(session);
        for (GroupSessionParticipant p : parts) {
            try {
                notifyParticipantOfCancel(p.getInternUserId(), session, caller);
            } catch (Exception e) {
                log.warn("[GroupSession] cancel-notify participant {} failed (non-fatal): {}",
                        p.getInternUserId(), e.getMessage());
            }
        }
        return toTrainerResponseWithParticipants(session);
    }

    // ── Intern-side ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<InternGroupSessionResponse> listForIntern(User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        List<GroupSessionParticipant> parts = participantRepository
                .findByInternUserIdOrderByCreatedAtDesc(caller.getId());
        if (parts.isEmpty()) return List.of();
        // Bulk-load trainer full names for the "hostName" projection.
        Set<UUID> trainerIds = new LinkedHashSet<>();
        for (GroupSessionParticipant p : parts) {
            if (p.getGroupSession() != null) {
                trainerIds.add(p.getGroupSession().getTrainerId());
            }
        }
        Map<UUID, String> trainerNames = new HashMap<>();
        for (User u : userRepository.findAllById(trainerIds)) {
            trainerNames.put(u.getId(), u.getFullName());
        }
        List<InternGroupSessionResponse> out = new ArrayList<>();
        for (GroupSessionParticipant p : parts) {
            GroupSession s = p.getGroupSession();
            if (s == null) continue;
            int count = participantRepository.findByGroupSession(s).size();
            out.add(new InternGroupSessionResponse(
                    s.getId(),
                    s.getTopic(),
                    s.getDescription(),
                    s.getScheduledAt(),
                    s.getMeetingTimezone(),
                    s.getDurationMinutes(),
                    s.getStatus(),
                    s.getZoomJoinUrl(),
                    s.getZoomPassword(),
                    trainerNames.get(s.getTrainerId()),
                    count));
        }
        out.sort((a, b) -> Objects.compare(b.scheduledAt(), a.scheduledAt(),
                java.util.Comparator.nullsLast(Instant::compareTo)));
        return out;
    }

    // ── Notification composition ───────────────────────────────────────────

    private void notifyParticipantOfSchedule(UUID internUserId, GroupSession session,
                                              User internUser, User host) {
        String hostName = host != null && host.getFullName() != null
                && !host.getFullName().isBlank()
                ? host.getFullName() : "Your Trainer";
        String whenLine = "Scheduled for " + session.getScheduledAt()
                + " (" + session.getMeetingTimezone() + ")"
                + (session.getDurationMinutes() != null
                    ? " — " + session.getDurationMinutes() + " min" : "");
        String joinLine = session.getZoomJoinUrl() != null
                && !session.getZoomJoinUrl().isBlank()
                ? "\n\nJoin the session: " + session.getZoomJoinUrl()
                : "";
        String subject = "Group session scheduled — " + session.getTopic();
        String body = "Hi,\n\n"
                + hostName + " scheduled a group session: \""
                + session.getTopic() + "\"."
                + "\n\n" + whenLine
                + (session.getDescription() != null && !session.getDescription().isBlank()
                    ? "\n\n" + session.getDescription() : "")
                + joinLine
                + "\n\nOpen your group sessions: /careers/intern/group-sessions"
                + "\n\n" + brand.signoff();
        internNotifications.notifyInternFromRole(internUserId,
                com.anvicorp.api.notification.NotificationSenderRoles.TRAINER,
                subject, body, null);
    }

    private void notifyParticipantOfCancel(UUID internUserId, GroupSession session, User host) {
        String hostName = host != null && host.getFullName() != null
                && !host.getFullName().isBlank()
                ? host.getFullName() : "Your Trainer";
        String subject = "Group session cancelled — " + session.getTopic();
        String body = "Hi,\n\n"
                + hostName + " cancelled the group session \""
                + session.getTopic() + "\" originally scheduled for "
                + session.getScheduledAt()
                + " (" + session.getMeetingTimezone() + ")."
                + "\n\n" + brand.signoff();
        internNotifications.notifyInternFromRole(internUserId,
                com.anvicorp.api.notification.NotificationSenderRoles.TRAINER,
                subject, body, null);
    }

    // ── Projections ────────────────────────────────────────────────────────

    private TrainerGroupSessionResponse toTrainerResponseWithParticipants(GroupSession session) {
        List<GroupSessionParticipant> parts = participantRepository.findByGroupSession(session);
        List<UUID> ids = parts.stream().map(GroupSessionParticipant::getInternUserId).toList();
        Map<UUID, User> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            for (User u : userRepository.findAllById(ids)) byId.put(u.getId(), u);
        }
        return toTrainerResponse(session, buildParticipantRefs(ids, byId));
    }

    private TrainerGroupSessionResponse toTrainerResponse(
            GroupSession session, List<ParticipantRef> participants) {
        return new TrainerGroupSessionResponse(
                session.getId(),
                session.getTopic(),
                session.getDescription(),
                session.getScheduledAt(),
                session.getMeetingTimezone(),
                session.getDurationMinutes(),
                session.getStatus(),
                session.getZoomMeetingId(),
                session.getZoomJoinUrl(),
                session.getZoomStartUrl(),
                session.getZoomPassword(),
                session.getCancelledAt(),
                participants,
                session.getCreatedAt(),
                session.getUpdatedAt());
    }

    private static List<ParticipantRef> buildParticipantRefs(
            List<UUID> ids, Map<UUID, User> byId) {
        List<ParticipantRef> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            User u = byId.get(id);
            if (u == null) {
                out.add(new ParticipantRef(id, null, null));
            } else {
                out.add(new ParticipantRef(u.getId(), u.getFullName(), u.getEmail()));
            }
        }
        return out;
    }

    // ── Guards ─────────────────────────────────────────────────────────────

    private static void requireTrainerOrSuperAdmin(User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        if (caller.getRoles() == null
                || (!caller.getRoles().contains(UserRole.TRAINER)
                    && !caller.getRoles().contains(UserRole.SUPER_ADMIN))) {
            throw new ForbiddenException(
                    "TRAINER or SUPER_ADMIN role required.");
        }
    }

    private static void requireHostOrSuperAdmin(GroupSession session, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required");
        boolean isSuperAdmin = caller.getRoles() != null
                && caller.getRoles().contains(UserRole.SUPER_ADMIN);
        if (isSuperAdmin) return;
        if (caller.getRoles() == null
                || !caller.getRoles().contains(UserRole.TRAINER)) {
            throw new ForbiddenException("TRAINER role required.");
        }
        if (!caller.getId().equals(session.getTrainerId())) {
            throw new ForbiddenException(
                    "You are not the host of this group session.");
        }
    }

    private GroupSession loadSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Group session not found: " + sessionId));
    }
}
