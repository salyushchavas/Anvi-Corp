package com.anvicorp.api.erm.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.ApplicationDecisionLog;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Resume;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.InternLifecycleStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.ReasonCode;
import com.anvicorp.api.erm.offer.SelectionAckPolicy;
import com.anvicorp.api.event.ApplicationDecisionEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.intern.InternLifecycleService;
import com.anvicorp.api.repository.ApplicationDecisionLogRepository;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ERM Phase 2 — application inbox + 4-decision flow.
 *
 * <p>Doc §5: ERM reviews each application, takes Shortlist / Hold /
 * Request Info / Reject. Every decision requires a {@link ReasonCode}
 * (HOLD/REJECT/REQUEST_INFO; SHORTLIST optional) plus optional free
 * text. HOLD/REJECT/REQUEST_INFO fire the matching
 * {@code CommunicationTemplate} via {@code ApplicationDecisionListener}.
 * Manager notified on shortlist per doc.</p>
 *
 * <p>SHORTLIST advances {@code users.lifecycle_status} to SHORTLISTED;
 * other decisions don't change lifecycle status (applicant can re-apply
 * to other jobs).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmApplicationService {

    private static final int APPLICATION_URGENT_DAYS = 5;
    private static final int BULK_DECISION_MAX = 50;
    private static final int INTERNAL_NOTE_MIN = 5;
    private static final int INTERNAL_NOTE_MAX = 5000;

    private static final Set<String> VALID_INFO_FIELDS = Set.of(
            "resume", "workAuth", "education", "other");

    private static final Set<ApplicationStatus> DECIDABLE_FROM = EnumSet.of(
            ApplicationStatus.APPLIED);

    private final ApplicationRepository applicationRepository;
    private final ApplicationDecisionLogRepository decisionLogRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final InternLifecycleService internLifecycleService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final SelectionAckPolicy selectionAckPolicy;

    // ── Inbox list ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ErmApplicationDtos.ErmApplicationListPage list(
            ErmApplicationDtos.InboxFilters filters, User caller,
            int page, int pageSize) {
        String scope = filters != null && filters.scope() != null
                ? filters.scope() : "mine";
        Pageable pageable = PageRequest.of(
                Math.max(0, page), Math.min(Math.max(1, pageSize), 100));

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (filters != null && filters.stages() != null && !filters.stages().isEmpty()) {
            where.append(" AND a.status IN (")
                    .append(placeholders(filters.stages().size())).append(") ");
            for (ApplicationStatus s : filters.stages()) params.add(s.name());
        }
        if (filters != null && filters.jobIds() != null && !filters.jobIds().isEmpty()) {
            where.append(" AND a.job_posting_id IN (")
                    .append(placeholders(filters.jobIds().size())).append(") ");
            params.addAll(filters.jobIds());
        }
        if (filters != null && filters.jobType() != null && !filters.jobType().isBlank()) {
            where.append(" AND jp.job_type = ? ");
            params.add(filters.jobType().trim());
        }
        if (filters != null && filters.search() != null && !filters.search().isBlank()) {
            String q = "%" + filters.search().trim().toLowerCase() + "%";
            if (q.length() > 102) q = q.substring(0, 102);
            where.append(" AND (LOWER(u.full_name) LIKE ? OR LOWER(u.email) LIKE ? "
                    + "OR LOWER(COALESCE(u.applicant_id,'')) LIKE ?) ");
            params.add(q); params.add(q); params.add(q);
        }
        if ("mine".equalsIgnoreCase(scope)) {
            where.append(" AND (a.erm_owner_id IS NULL OR a.erm_owner_id = ?) ");
            params.add(caller.getId());
        } else if ("unassigned".equalsIgnoreCase(scope)) {
            where.append(" AND a.erm_owner_id IS NULL ");
        }
        // scope=all → no additional clause; gated by @PreAuthorize.

        String select = "SELECT a.id AS app_id, a.status, a.applied_at, a.last_decision_at, "
                + "a.erm_owner_id, "
                + "u.id AS user_id, u.full_name, u.email, u.applicant_id, "
                + "jp.id AS job_id, jp.title AS job_title, jp.job_type, "
                + "c.expected_track, c.validity_date, "
                + "a.resume_id, "
                + "(SELECT u2.full_name FROM users u2 WHERE u2.id = a.erm_owner_id) AS erm_owner_name "
                + "FROM applications a "
                + "JOIN candidates c ON c.id = a.candidate_id "
                + "JOIN users u ON u.id = c.user_id "
                + "JOIN job_postings jp ON jp.id = a.job_posting_id "
                + where;

        String count = "SELECT COUNT(*) FROM applications a "
                + "JOIN candidates c ON c.id = a.candidate_id "
                + "JOIN users u ON u.id = c.user_id "
                + "JOIN job_postings jp ON jp.id = a.job_posting_id "
                + where;

        long total;
        try {
            Long v = jdbc.queryForObject(count, Long.class, params.toArray());
            total = v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ErmApplications] count failed (non-fatal): {}", e.getMessage());
            total = 0L;
        }

        // Phase 8.5 — newest-first inbox UX. applied_at is set in @PrePersist
        // so it's effectively non-null, but NULLS LAST keeps any legacy rows
        // from clobbering the top of the queue. Tie-break by id for deterministic
        // pagination.
        String sql = select
                + " ORDER BY a.applied_at DESC NULLS LAST, a.id DESC "
                + " LIMIT " + pageable.getPageSize()
                + " OFFSET " + (pageable.getPageNumber() * pageable.getPageSize());

        List<ErmApplicationDtos.ErmApplicationRow> rows = new ArrayList<>();
        try {
            rows = jdbc.query(sql, params.toArray(), (rs, n) -> {
                Instant appliedAt = rs.getTimestamp("applied_at") != null
                        ? rs.getTimestamp("applied_at").toInstant() : null;
                long ageDays = appliedAt != null
                        ? ChronoUnit.DAYS.between(appliedAt, Instant.now()) : 0L;
                ApplicationStatus stage = ApplicationStatus.valueOf(rs.getString("status"));
                String validity = rs.getString("validity_date");
                return new ErmApplicationDtos.ErmApplicationRow(
                        UUID.fromString(rs.getString("app_id")),
                        rs.getString("applicant_id"),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        UUID.fromString(rs.getString("job_id")),
                        rs.getString("job_title"),
                        rs.getString("job_type"),
                        null, // technology — not on JobPosting today; left null
                        stage,
                        rs.getTimestamp("last_decision_at") != null
                                ? rs.getTimestamp("last_decision_at").toInstant() : null,
                        ageDays,
                        rs.getString("expected_track"),
                        validity,
                        nullableUuid(rs.getString("erm_owner_id")),
                        rs.getString("erm_owner_name"),
                        rs.getString("resume_id") != null,
                        stage == ApplicationStatus.APPLIED && ageDays > APPLICATION_URGENT_DAYS);
            });
        } catch (Exception e) {
            log.warn("[ErmApplications] list query failed (non-fatal): {}", e.getMessage());
        }

        Page<ErmApplicationDtos.ErmApplicationRow> p =
                new PageImpl<>(rows, pageable, total);
        return new ErmApplicationDtos.ErmApplicationListPage(
                p.getContent(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }

    // ── Detail ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ErmApplicationDtos.ErmApplicationDetail getDetail(UUID applicationId, User caller) {
        Application app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + applicationId));
        return toDetail(app);
    }

    // ── Decide ─────────────────────────────────────────────────────────────

    @Transactional
    public ErmApplicationDtos.ErmApplicationDetail decide(
            UUID applicationId,
            ErmApplicationDtos.ErmDecisionRequest req,
            User caller) {
        if (req == null || req.decision() == null) {
            throw new BadRequestException("decision is required");
        }
        String decision = req.decision().trim().toUpperCase();
        if (!Set.of("SHORTLIST", "HOLD", "REQUEST_INFO", "REJECT").contains(decision)) {
            throw new BadRequestException(
                    "decision must be SHORTLIST | HOLD | REQUEST_INFO | REJECT");
        }

        Application app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + applicationId));

        ApplicationStatus previous = app.getStatus();
        if (!DECIDABLE_FROM.contains(previous)) {
            throw new ConflictException(
                    "Decisions only allowed from APPLIED (current: " + previous + ")");
        }

        ReasonCode reasonCode = validateAndResolveReason(decision, req);
        if (decision.equals("REQUEST_INFO")) {
            validateInfoFields(req.infoRequestedFields());
        }

        ApplicationStatus newStage = switch (decision) {
            case "SHORTLIST" -> ApplicationStatus.SHORTLISTED;
            case "HOLD" -> ApplicationStatus.HOLD;
            case "REQUEST_INFO" -> ApplicationStatus.INFO_REQUESTED;
            case "REJECT" -> ApplicationStatus.REJECTED;
            default -> throw new IllegalStateException();
        };

        Instant now = Instant.now();
        app.setStatus(newStage);
        app.setStatusUpdatedBy(caller.getId());
        app.setLastDecisionReasonCode(reasonCode != null ? reasonCode.name() : null);
        app.setLastDecisionReasonText(trimOrNull(req.reasonText()));
        app.setLastDecisionAt(now);
        app.setLastDecisionById(caller.getId());
        if (app.getErmOwnerId() == null) app.setErmOwnerId(caller.getId());
        if (decision.equals("REQUEST_INFO")) {
            app.setInfoRequestedFieldsCsv(String.join(",", req.infoRequestedFields()));
            app.setInfoRequestedAt(now);
        } else {
            app.setInfoRequestedFieldsCsv(null);
            app.setInfoRequestedAt(null);
        }
        applicationRepository.save(app);

        // SHORTLIST advances lifecycle status; others don't.
        if (decision.equals("SHORTLIST") && app.getCandidate() != null
                && app.getCandidate().getUser() != null) {
            internLifecycleService.advance(app.getCandidate().getUser(),
                    InternLifecycleStatus.SHORTLISTED, caller.getId());
        }

        ApplicationDecisionLog logRow = ApplicationDecisionLog.builder()
                .applicationId(app.getId())
                .decidedById(caller.getId())
                .decision(decision)
                .reasonCode(reasonCode != null ? reasonCode.name() : null)
                .reasonText(trimOrNull(req.reasonText()))
                .previousStage(previous.name())
                .newStage(newStage.name())
                .decidedAt(now)
                .build();

        // The listener renders + sends + dispatches in-app. We populate the
        // log row's applicantVisibleMessage in the listener after rendering.
        decisionLogRepository.save(logRow);

        Map<String, Object> afterMap = new LinkedHashMap<>();
        afterMap.put("status", newStage.name());
        afterMap.put("decision", decision);
        afterMap.put("reasonCode", reasonCode != null ? reasonCode.name() : null);
        writeAudit(caller.getId(), candidateUserId(app),
                "APPLICATION_STAGE_CHANGED",
                "Application", app.getId(),
                Map.of("status", previous.name()),
                afterMap);

        try {
            eventPublisher.publishEvent(new ApplicationDecisionEvent(
                    decision,
                    app.getId(),
                    candidateUserId(app),
                    caller.getId(),
                    reasonCode != null ? reasonCode.name() : null,
                    decision.equals("REQUEST_INFO") ? req.infoRequestedFields() : List.of(),
                    null, null));
        } catch (Exception e) {
            log.warn("[ErmApplications] event publish failed (non-fatal): {}", e.getMessage());
        }

        return toDetail(app);
    }

    // ── Resume from hold ──────────────────────────────────────────────────

    @Transactional
    public ErmApplicationDtos.ErmApplicationDetail resumeFromHold(UUID applicationId, User caller) {
        Application app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + applicationId));
        if (app.getStatus() != ApplicationStatus.HOLD) {
            throw new ConflictException(
                    "Resume only allowed from HOLD (current: " + app.getStatus() + ")");
        }
        ApplicationStatus previous = app.getStatus();
        Instant now = Instant.now();
        app.setStatus(ApplicationStatus.APPLIED);
        app.setStatusUpdatedBy(caller.getId());
        app.setLastDecisionAt(now);
        app.setLastDecisionById(caller.getId());
        applicationRepository.save(app);

        decisionLogRepository.save(ApplicationDecisionLog.builder()
                .applicationId(app.getId())
                .decidedById(caller.getId())
                .decision("RESUME_FROM_HOLD")
                .previousStage(previous.name())
                .newStage(ApplicationStatus.APPLIED.name())
                .decidedAt(now)
                .build());

        writeAudit(caller.getId(), candidateUserId(app),
                "APPLICATION_RESUMED_FROM_HOLD",
                "Application", app.getId(),
                Map.of("status", previous.name()),
                Map.of("status", ApplicationStatus.APPLIED.name()));
        return toDetail(app);
    }

    // ── Bulk decide ───────────────────────────────────────────────────────

    public ErmApplicationDtos.BulkDecisionResult bulkDecide(
            ErmApplicationDtos.BulkDecisionRequest req, User caller) {
        if (req == null || req.applicationIds() == null || req.applicationIds().isEmpty()) {
            throw new BadRequestException("applicationIds is required");
        }
        if (req.applicationIds().size() > BULK_DECISION_MAX) {
            throw new BadRequestException(
                    "bulk decision capped at " + BULK_DECISION_MAX + " applications");
        }
        List<UUID> succeeded = new ArrayList<>();
        List<ErmApplicationDtos.BulkDecisionResult.BulkFailure> failed = new ArrayList<>();
        for (UUID id : req.applicationIds()) {
            try {
                decide(id, req.decision(), caller);
                succeeded.add(id);
            } catch (Exception e) {
                failed.add(new ErmApplicationDtos.BulkDecisionResult.BulkFailure(
                        id, e.getMessage()));
            }
        }
        return new ErmApplicationDtos.BulkDecisionResult(succeeded, failed);
    }

    // ── Internal note + owner assignment ──────────────────────────────────

    @Transactional
    public void appendInternalNote(UUID applicationId, String note, User caller) {
        if (note == null || note.trim().length() < INTERNAL_NOTE_MIN) {
            throw new BadRequestException(
                    "note must be at least " + INTERNAL_NOTE_MIN + " characters");
        }
        if (note.length() > INTERNAL_NOTE_MAX) {
            throw new BadRequestException(
                    "note cannot exceed " + INTERNAL_NOTE_MAX + " characters");
        }
        Application app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + applicationId));
        String stamp = LocalDateTime.now(ZoneOffset.UTC).toString().substring(0, 19) + "Z";
        String authorName = caller.getFullName() != null ? caller.getFullName() : "ERM";
        String entry = "[" + stamp + " · " + authorName + "]\n" + note.trim();
        String existing = app.getInternalNotes();
        app.setInternalNotes(existing == null || existing.isBlank()
                ? entry : existing + "\n\n" + entry);
        applicationRepository.save(app);

        writeAudit(caller.getId(), candidateUserId(app),
                "APPLICATION_INTERNAL_NOTE_ADDED",
                "Application", app.getId(),
                null, Map.of("length", note.length()));
    }

    @Transactional
    public void assignOwner(UUID applicationId, UUID ermUserId, User caller) {
        if (ermUserId == null) {
            throw new BadRequestException("ermUserId is required");
        }
        User target = userRepository.findById(ermUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: " + ermUserId));
        if (target.getRoles() == null || !target.getRoles().contains(UserRole.ERM)) {
            throw new BadRequestException("Target user must have ERM role");
        }
        Application app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found: " + applicationId));
        UUID previous = app.getErmOwnerId();
        app.setErmOwnerId(ermUserId);
        applicationRepository.save(app);
        writeAudit(caller.getId(), candidateUserId(app),
                "APPLICATION_OWNER_ASSIGNED",
                "Application", app.getId(),
                previous != null ? Map.of("ermOwnerId", previous.toString()) : null,
                Map.of("ermOwnerId", ermUserId.toString()));
    }

    // ── Reason codes for the UI ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ErmApplicationDtos.ReasonCodeGroup> listReasonCodesForDecision(String decision) {
        Set<ReasonCode.Category> categories;
        if (decision == null || decision.isBlank()) {
            categories = EnumSet.allOf(ReasonCode.Category.class);
        } else {
            categories = switch (decision.toUpperCase()) {
                case "HOLD" -> EnumSet.of(ReasonCode.Category.APPLICATION_HOLD);
                case "REJECT" -> EnumSet.of(ReasonCode.Category.APPLICATION_REJECT);
                case "REQUEST_INFO" -> EnumSet.of(ReasonCode.Category.APPLICATION_REQUEST_INFO);
                case "SHORTLIST" -> EnumSet.noneOf(ReasonCode.Category.class);
                default -> EnumSet.allOf(ReasonCode.Category.class);
            };
        }
        Map<ReasonCode.Category, List<ErmApplicationDtos.ReasonCodeOption>> bucket =
                new LinkedHashMap<>();
        for (ReasonCode rc : ReasonCode.values()) {
            if (!categories.contains(rc.category())) continue;
            bucket.computeIfAbsent(rc.category(), k -> new ArrayList<>())
                    .add(new ErmApplicationDtos.ReasonCodeOption(
                            rc.name(), rc.humanLabel(), rc.requiresFreeText()));
        }
        List<ErmApplicationDtos.ReasonCodeGroup> out = new ArrayList<>();
        for (var e : bucket.entrySet()) {
            out.add(new ErmApplicationDtos.ReasonCodeGroup(e.getKey().name(), e.getValue()));
        }
        return out;
    }

    // ── Mapping ───────────────────────────────────────────────────────────

    private ErmApplicationDtos.ErmApplicationDetail toDetail(Application app) {
        Candidate c = app.getCandidate();
        User u = c != null ? c.getUser() : null;
        JobPosting jp = app.getJobPosting();
        Resume rs = app.getResume();
        StaffingEntity ent = jp != null ? jp.getEntity() : null;
        User ermOwner = app.getErmOwnerId() != null
                ? userRepository.findById(app.getErmOwnerId()).orElse(null) : null;
        User lastBy = app.getLastDecisionById() != null
                ? userRepository.findById(app.getLastDecisionById()).orElse(null) : null;

        ErmApplicationDtos.ApplicationView appView = new ErmApplicationDtos.ApplicationView(
                app.getId(),
                app.getStatus(),
                app.getAppliedAt(),
                app.getStatusUpdatedAt(),
                app.getErmOwnerId(),
                ermOwner != null ? ermOwner.getFullName() : null,
                app.getInternalNotes(),
                app.getLastDecisionReasonCode(),
                app.getLastDecisionReasonText(),
                app.getLastDecisionAt(),
                app.getLastDecisionById(),
                lastBy != null ? lastBy.getFullName() : null,
                app.getInfoRequestedFieldsCsv(),
                app.getInfoRequestedAt(),
                app.getStatementOfInterest(),
                app.getApplicantVisibleFeedback()
        );
        ErmApplicationDtos.ApplicantView applicant = u == null ? null
                : new ErmApplicationDtos.ApplicantView(
                        u.getId(),
                        firstName(u), lastName(u),
                        u.getEmail(), u.getPhoneNumber(),
                        u.getApplicantId(), u.getEmployeeId(),
                        u.getEmailVerified());
        ErmApplicationDtos.ApplicantProfileView profile = c == null ? null
                : new ErmApplicationDtos.ApplicantProfileView(
                        c.getEducation(), c.getSchool(), c.getDegree(),
                        c.getDegreeLevel() != null ? c.getDegreeLevel().name() : null,
                        c.getSpecialization(),
                        c.getGraduationYear(),
                        c.getSkillset(),
                        c.getExpectedTrack() != null ? c.getExpectedTrack().name() : null,
                        c.getAuthorizedToWork(),
                        c.getSponsorshipNeeded(),
                        c.getValidityDate() != null ? c.getValidityDate().toString() : null,
                        c.getValidityStartDate() != null ? c.getValidityStartDate().toString() : null,
                        app.getStatementOfInterest());
        ErmApplicationDtos.ResumeView resume = rs == null ? null
                : new ErmApplicationDtos.ResumeView(
                        rs.getId(), rs.getFileName(), rs.getFileSize(),
                        rs.getContentType(),
                        "/api/v1/resumes/" + rs.getId() + "/download");
        ErmApplicationDtos.JobView job = jp == null ? null
                : new ErmApplicationDtos.JobView(
                        jp.getId(), jp.getTitle(),
                        jp.getEmploymentType() != null ? jp.getEmploymentType().name() : null,
                        null,
                        jp.getLocation(), null,
                        descriptionExcerpt(jp.getDescription()));

        List<ErmApplicationDtos.DecisionLogEntry> history = decisionLogRepository
                .findByApplicationIdOrderByDecidedAtDesc(app.getId())
                .stream()
                .map(this::toDecisionLogEntry)
                .toList();

        ErmApplicationDtos.AvailableActions actions = availableActions(app);

        return new ErmApplicationDtos.ErmApplicationDetail(
                appView, applicant, profile, resume, job, history, actions);
    }

    private ErmApplicationDtos.DecisionLogEntry toDecisionLogEntry(ApplicationDecisionLog l) {
        User by = l.getDecidedById() != null
                ? userRepository.findById(l.getDecidedById()).orElse(null) : null;
        String reasonLabel = null;
        if (l.getReasonCode() != null) {
            try { reasonLabel = ReasonCode.valueOf(l.getReasonCode()).humanLabel(); }
            catch (Exception ignored) {}
        }
        return new ErmApplicationDtos.DecisionLogEntry(
                l.getId(),
                l.getDecision(),
                l.getReasonCode(),
                reasonLabel,
                l.getReasonText(),
                l.getPreviousStage(),
                l.getNewStage(),
                l.getApplicantVisibleMessage(),
                l.getDecidedById(),
                by != null ? by.getFullName() : null,
                l.getDecidedAt());
    }

    private ErmApplicationDtos.AvailableActions availableActions(Application app) {
        ApplicationStatus s = app.getStatus();
        boolean isApplied = s == ApplicationStatus.APPLIED;
        boolean isHold = s == ApplicationStatus.HOLD;
        // Read the same predicate the Send-Offer 409 uses — the
        // create-offer UI disables Submit + shows a warning when true,
        // so the ERM doesn't click into a guaranteed 409.
        boolean needsAck = selectionAckPolicy.needsAck(app);
        return new ErmApplicationDtos.AvailableActions(
                isApplied, isApplied, isApplied, isApplied, isHold, needsAck);
    }

    // ── Validation helpers ────────────────────────────────────────────────

    private ReasonCode validateAndResolveReason(String decision,
                                                  ErmApplicationDtos.ErmDecisionRequest req) {
        if (decision.equals("SHORTLIST")) {
            if (req.reasonCode() != null && !req.reasonCode().isBlank()) {
                // Optional — accept if parseable; ignore if not.
                try { return ReasonCode.valueOf(req.reasonCode().trim().toUpperCase()); }
                catch (Exception e) { return null; }
            }
            return null;
        }
        if (req.reasonCode() == null || req.reasonCode().isBlank()) {
            throw new BadRequestException("reasonCode is required for " + decision);
        }
        ReasonCode rc;
        try {
            rc = ReasonCode.valueOf(req.reasonCode().trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException("Unknown reasonCode: " + req.reasonCode());
        }
        ReasonCode.Category expected = switch (decision) {
            case "HOLD" -> ReasonCode.Category.APPLICATION_HOLD;
            case "REJECT" -> ReasonCode.Category.APPLICATION_REJECT;
            case "REQUEST_INFO" -> ReasonCode.Category.APPLICATION_REQUEST_INFO;
            default -> null;
        };
        if (expected != null && rc.category() != expected) {
            throw new BadRequestException(
                    "reasonCode category " + rc.category()
                            + " does not match decision " + decision);
        }
        if (rc.requiresFreeText()
                && (req.reasonText() == null || req.reasonText().trim().isEmpty())) {
            throw new BadRequestException(
                    "reasonText is required when reasonCode is " + rc.name());
        }
        return rc;
    }

    private void validateInfoFields(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new BadRequestException(
                    "infoRequestedFields must include at least one of " + VALID_INFO_FIELDS);
        }
        for (String f : fields) {
            if (!VALID_INFO_FIELDS.contains(f)) {
                throw new BadRequestException(
                        "infoRequestedFields contains invalid value '" + f
                                + "' (allowed: " + VALID_INFO_FIELDS + ")");
            }
        }
    }

    // ── Misc helpers ──────────────────────────────────────────────────────

    private static UUID candidateUserId(Application app) {
        if (app == null || app.getCandidate() == null
                || app.getCandidate().getUser() == null) return null;
        return app.getCandidate().getUser().getId();
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstName(User u) {
        String full = u.getFullName();
        if (full == null || full.isBlank()) return "";
        return full.trim().split("\\s+", 2)[0];
    }

    private static String lastName(User u) {
        String full = u.getFullName();
        if (full == null || full.isBlank()) return "";
        String[] parts = full.trim().split("\\s+", 2);
        return parts.length > 1 ? parts[1] : "";
    }

    private static String descriptionExcerpt(String desc) {
        if (desc == null) return null;
        String s = desc.trim();
        return s.length() <= 280 ? s : s.substring(0, 280) + "…";
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }

    private static UUID nullableUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private void writeAudit(UUID actorId, UUID subjectUserId, String action,
                             String entityType, UUID entityId,
                             Map<String, Object> before, Map<String, Object> after) {
        try {
            AuditLog row = AuditLog.builder()
                    .userId(actorId)
                    .subjectUserId(subjectUserId)
                    .entityType(entityType)
                    .entityId(entityId)
                    .action(action)
                    .beforeJson(before != null ? objectMapper.writeValueAsString(before) : null)
                    .afterJson(after != null ? objectMapper.writeValueAsString(after) : null)
                    .build();
            auditLogRepository.save(row);
        } catch (JsonProcessingException jpe) {
            log.warn("[ErmApplications] audit JSON failed for {}: {}", action, jpe.getMessage());
        } catch (Exception e) {
            log.warn("[ErmApplications] audit write failed: {}", e.getMessage());
        }
    }

    /** Public helper for the listener to backfill applicant_visible_message after render. */
    @Transactional
    public void recordRenderedMessage(UUID applicationId, String message) {
        if (applicationId == null || message == null || message.isBlank()) return;
        try {
            decisionLogRepository.findByApplicationIdOrderByDecidedAtDesc(applicationId)
                    .stream().findFirst()
                    .ifPresent(l -> {
                        l.setApplicantVisibleMessage(message);
                        decisionLogRepository.save(l);
                    });
        } catch (Exception e) {
            log.debug("[ErmApplications] message backfill failed: {}", e.getMessage());
        }
    }
}
