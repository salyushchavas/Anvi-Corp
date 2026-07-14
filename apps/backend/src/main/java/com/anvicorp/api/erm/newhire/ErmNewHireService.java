package com.anvicorp.api.erm.newhire;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.erm.offer.ErmOfferDtos;
import com.anvicorp.api.event.ReportingStructureAssignedEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ConflictException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.OfferRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ERM Phase 4 — Prospective New Hire List + atomic reporting-structure
 * assignment. Per doc §3: ERM must wire Trainer + Evaluator + Manager
 * before the system advances lifecycle from EMPLOYEE_ID_CREATED to
 * ONBOARDING_ASSIGNED (enforcement happens server-side in
 * {@code OnboardingService.assignPacket}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErmNewHireService {

    private final InternLifecycleRepository lifecycleRepository;
    private final UserRepository userRepository;
    private final OfferRepository offerRepository;
    private final AuditLogRepository auditLogRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final OnboardingTrackerService onboardingTracker;
    private final com.anvicorp.api.notification.InternNotificationService internNotifications;
    private final com.anvicorp.api.config.BrandConfig brand;

    // ── List ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ErmOfferDtos.NewHireListPage list(String tab, User caller,
                                              int page, int pageSize) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page), Math.min(Math.max(1, pageSize), 100));
        // tab=all surfaces every signed-offer intern (PROSPECTIVE + ACTIVE)
        // so the unified status table can show "Active" as a stage filter.
        // The narrower tabs stay PROSPECTIVE-only since they're scoped to
        // in-flight onboarding work that doesn't apply to activated interns.
        boolean includeActive = "all".equalsIgnoreCase(tab);
        StringBuilder where = new StringBuilder(includeActive
                ? " WHERE il.active_status IN ('PROSPECTIVE', 'ACTIVE') "
                : " WHERE il.active_status = 'PROSPECTIVE' ");
        List<Object> params = new ArrayList<>();
        if ("pending".equalsIgnoreCase(tab)) {
            // Legacy filter — pre-Phase-8.6.4 this surfaced interns waiting
            // for ERM to pick T/E/M manually. Auto-link from system config
            // means this set is almost always empty now; kept for parity
            // with any deployment that has DEFAULT_*_EMAIL unset.
            where.append(" AND il.reporting_structure_complete = FALSE ");
        } else if ("ready".equalsIgnoreCase(tab)
                || "pending-document-assignment".equalsIgnoreCase(tab)) {
            // Phase 8.6.4 — gate dropped: any signed-offer intern with no
            // active packet shows up here. Trainer + Evaluator are
            // auto-linked at offer sign so reporting_structure_complete
            // is no longer the right filter (would exclude interns whose
            // env vars weren't configured at sign time — still fine to
            // assign docs to).
            where.append(" AND NOT EXISTS (SELECT 1 FROM document_packets pk "
                    + "                   WHERE pk.intern_lifecycle_id = il.id "
                    + "                     AND pk.status NOT IN ('COMPLETED','CANCELLED')) ");
        } else if ("in-progress".equalsIgnoreCase(tab)) {
            // Packet assigned, intern actively filling / ERM actively reviewing.
            where.append(" AND EXISTS (SELECT 1 FROM document_packets pk "
                    + "                   WHERE pk.intern_lifecycle_id = il.id "
                    + "                     AND pk.status IN ('ASSIGNED','IN_PROGRESS','ALL_SUBMITTED')) ");
        }
        String base = "FROM intern_lifecycles il "
                + "JOIN users u ON u.id = il.user_id "
                + "LEFT JOIN users t ON t.id = il.trainer_id "
                + "LEFT JOIN users ev ON ev.id = il.evaluator_id "
                + "LEFT JOIN users m ON m.id = il.manager_id ";
        long total;
        try {
            Long v = jdbc.queryForObject("SELECT COUNT(*) " + base + where,
                    Long.class, params.toArray());
            total = v != null ? v : 0L;
        } catch (Exception e) {
            log.warn("[ErmNewHire] count failed: {}", e.getMessage());
            total = 0L;
        }
        String select = "SELECT il.id, il.user_id, il.employee_id, il.hired_at, "
                + "il.tentative_start_date, il.reporting_structure_complete, "
                + "u.full_name AS intern_name, u.email AS intern_email, "
                + "t.full_name AS trainer_name, ev.full_name AS evaluator_name, "
                + "m.full_name AS manager_name, "
                + "EXISTS (SELECT 1 FROM document_packets pk "
                + "          WHERE pk.intern_lifecycle_id = il.id) AS onboarding_assigned "
                + base + where
                + " ORDER BY il.hired_at DESC "
                + " LIMIT " + pageable.getPageSize()
                + " OFFSET " + (pageable.getPageNumber() * pageable.getPageSize());
        List<ErmOfferDtos.NewHireRow> rows = new ArrayList<>();
        try {
            rows = jdbc.query(select, params.toArray(), (rs, n) -> new ErmOfferDtos.NewHireRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("user_id")),
                    rs.getString("intern_name"),
                    rs.getString("intern_email"),
                    rs.getString("employee_id"),
                    rs.getTimestamp("hired_at") != null ? rs.getTimestamp("hired_at").toInstant() : null,
                    rs.getDate("tentative_start_date") != null
                            ? rs.getDate("tentative_start_date").toLocalDate() : null,
                    rs.getString("trainer_name"),
                    rs.getString("evaluator_name"),
                    rs.getString("manager_name"),
                    rs.getBoolean("reporting_structure_complete"),
                    rs.getBoolean("onboarding_assigned"),
                    // Tracker fields are filled in by enrichWithTrackerProgress
                    // below — left null here so the row constructor stays a
                    // pure projection of the SQL columns.
                    null, null, null, null, null));
        } catch (Exception e) {
            log.warn("[ErmNewHire] list query failed: {}", e.getMessage());
        }
        rows = enrichWithTrackerProgress(rows);
        Page<ErmOfferDtos.NewHireRow> p = new PageImpl<>(rows, pageable, total);
        return new ErmOfferDtos.NewHireListPage(p.getContent(), p.getNumber(),
                p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    /**
     * Per-row enrichment with onboarding-tracker progress so the list can
     * render the "N/6 · needs X" badge. Best-effort: a per-row failure
     * leaves the tracker fields null (older-client behaviour) without
     * failing the whole list call.
     *
     * <p>Page size is bounded at 100 → ≤100 lifecycle lookups + tracker
     * computations per call, which is fine for ERM-facing dashboards.</p>
     */
    private List<ErmOfferDtos.NewHireRow> enrichWithTrackerProgress(
            List<ErmOfferDtos.NewHireRow> rows) {
        if (rows == null || rows.isEmpty()) return rows;
        List<ErmOfferDtos.NewHireRow> out = new ArrayList<>(rows.size());
        for (ErmOfferDtos.NewHireRow r : rows) {
            try {
                InternLifecycle lc = lifecycleRepository.findById(r.internLifecycleId())
                        .orElse(null);
                if (lc == null) { out.add(r); continue; }
                var t = onboardingTracker.computeForLifecycle(lc);
                out.add(new ErmOfferDtos.NewHireRow(
                        r.internLifecycleId(), r.internUserId(), r.internName(),
                        r.internEmail(), r.employeeId(), r.signedAt(),
                        r.tentativeStartDate(), r.trainerName(), r.evaluatorName(),
                        r.managerName(), r.reportingStructureComplete(),
                        r.onboardingAssigned(),
                        t.stepsCompleted(), t.stepsTotal(),
                        t.nextStepLabel(), t.canActivate(),
                        t.currentStepId() == null ? null : t.currentStepId().name()));
            } catch (Exception e) {
                log.warn("[ErmNewHire] tracker enrich failed for {}: {}",
                        r.internLifecycleId(), e.getMessage());
                out.add(r);
            }
        }
        return out;
    }

    // ── Detail ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ErmOfferDtos.NewHireDetail detail(UUID lifecycleId) {
        InternLifecycle lc = lifecycleRepository.findById(lifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + lifecycleId));
        User intern = userRepository.findById(lc.getUserId()).orElse(null);
        Offer signedOffer = null;
        if (intern != null) {
            signedOffer = offerRepository
                    .findByApplication_Candidate_User_IdOrderByCreatedAtDesc(intern.getId())
                    .stream()
                    .filter(o -> o.getSignedAt() != null)
                    .findFirst().orElse(null);
        }
        boolean onboardingAssigned = false;
        try {
            Long v = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM document_packets WHERE intern_lifecycle_id = ?",
                    Long.class, lc.getId());
            onboardingAssigned = v != null && v > 0;
        } catch (Exception ignored) {}

        return new ErmOfferDtos.NewHireDetail(
                lc.getId(),
                lc.getUserId(),
                intern != null ? intern.getFullName() : null,
                intern != null ? intern.getEmail() : null,
                lc.getEmployeeId(),
                lc.getActiveStatus(),
                lc.getHiredAt(),
                lc.getStartedAt(),
                lc.getTentativeStartDate(),
                Boolean.TRUE.equals(lc.getReportingStructureComplete()),
                lc.getReportingStructureCompletedAt(),
                lc.getReportingStructureCompletedById(),
                toStub(lc.getTrainerId()),
                toStub(lc.getEvaluatorId()),
                toStub(lc.getManagerId()),
                toStub(lc.getErmId()),
                signedOffer == null ? null : new ErmOfferDtos.OfferSummaryStub(
                        signedOffer.getId(), signedOffer.getRoleTitle(),
                        signedOffer.getCompensationSummary(), signedOffer.getWorksite(),
                        signedOffer.getExpectedHoursPerWeek(), signedOffer.getStartDate(),
                        signedOffer.getSignedAt(), signedOffer.getSignedPdfDocumentId()),
                onboardingAssigned,
                // Phase 8.9 — surface the ERM "Activate now" override only
                // when doc verification is complete (ONBOARDING_ACCEPTED) and
                // a signed offer exists. Never true at ONBOARDING_ASSIGNED,
                // so the override can never bypass document verification.
                intern != null
                        && intern.getLifecycleStatus()
                                == com.anvicorp.api.enums.InternLifecycleStatus.ONBOARDING_ACCEPTED
                        && signedOffer != null,
                lc.getJoiningDate(),
                intern != null
                        && intern.getLifecycleStatus()
                                == com.anvicorp.api.enums.InternLifecycleStatus.ONBOARDING_ACCEPTED,
                // Mail bridge Phase 5 — surface the handover state +
                // archived personal email so the ERM page can render the
                // "Assign company email" section (PERSONAL + employeeId)
                // or the status chip (PENDING_ACTIVATION / ACTIVATED).
                intern != null && intern.getMailHandoverState() != null
                        ? intern.getMailHandoverState().name() : null,
                intern != null ? intern.getPersonalEmail() : null);
    }

    // ── Assign reporting structure (legacy — kept for one-off corrections) ─

    /**
     * Phase 8.6.4 — Trainer + Evaluator are auto-linked at offer sign from
     * system config ({@code DEFAULT_TRAINER_EMAIL} / {@code DEFAULT_EVALUATOR_EMAIL}),
     * and Manager is assigned inline later via {@link #assignManager}. This
     * endpoint stays for ERM to correct mis-linked T/E or backfill on
     * legacy InternLifecycle rows. Manager is now optional in the request
     * body; the {@code reporting_structure_complete} flag flips on T+E
     * presence (Manager excluded).
     */
    @Transactional
    public ErmOfferDtos.NewHireDetail assignReportingStructure(
            UUID lifecycleId, ErmOfferDtos.AssignReportingRequest req, User caller) {
        if (req == null) throw new BadRequestException("body required");
        if (req.trainerUserId() == null || req.evaluatorUserId() == null) {
            throw new BadRequestException(
                    "trainerUserId + evaluatorUserId are required (managerUserId optional)");
        }
        InternLifecycle lc = lifecycleRepository.findById(lifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + lifecycleId));
        if (!"PROSPECTIVE".equals(lc.getActiveStatus())
                && !"ACTIVE".equals(lc.getActiveStatus())) {
            throw new ConflictException(
                    "Assignment allowed only when active_status IN "
                            + "(PROSPECTIVE, ACTIVE) — current: "
                            + lc.getActiveStatus() + ")");
        }
        User trainer = requireRole(req.trainerUserId(), UserRole.TRAINER, "Trainer");
        // Accept EVALUATOR or REPORTING_MANAGER — mirrors the eligible
        // list which unions both roles. Without this an admin who
        // created an "Evaluator" with the EVALUATOR role wouldn't be
        // assignable even though they appear in the picker.
        User evaluator = requireAnyRole(req.evaluatorUserId(),
                java.util.EnumSet.of(UserRole.EVALUATOR, UserRole.REPORTING_MANAGER),
                "Evaluator");
        User manager = req.managerUserId() != null
                ? requireRole(req.managerUserId(), UserRole.MANAGER, "Manager")
                : null;

        // Capture previous IDs so we can detect mid-engagement reassignments
        // and notify the intern via internal mail. First-time assignments
        // (previous == null) are part of onboarding and surface through the
        // activation team-intro mail instead — we only ping when an existing
        // staff role changes hands.
        UUID prevTrainerId = lc.getTrainerId();
        UUID prevEvaluatorId = lc.getEvaluatorId();
        UUID prevManagerId = lc.getManagerId();

        lc.setTrainerId(trainer.getId());
        lc.setEvaluatorId(evaluator.getId());
        if (manager != null) lc.setManagerId(manager.getId());
        // T + E set → reporting structure considered complete.
        lc.setReportingStructureComplete(Boolean.TRUE);
        lc.setReportingStructureCompletedAt(Instant.now());
        lc.setReportingStructureCompletedById(caller.getId());
        lifecycleRepository.save(lc);

        Map<String, Object> after = new java.util.LinkedHashMap<>();
        after.put("trainerId", trainer.getId().toString());
        after.put("evaluatorId", evaluator.getId().toString());
        after.put("managerId", manager != null ? manager.getId().toString() : null);
        writeAudit(caller.getId(), lc.getUserId(),
                "REPORTING_STRUCTURE_ASSIGNED", "InternLifecycle", lc.getId(),
                null, after);
        try {
            eventPublisher.publishEvent(new ReportingStructureAssignedEvent(
                    lc.getId(), lc.getUserId(),
                    trainer.getId(), evaluator.getId(),
                    manager != null ? manager.getId() : null,
                    caller.getId()));
        } catch (Exception e) {
            log.warn("[ErmNewHire] reporting structure event publish failed: {}", e.getMessage());
        }

        // Tier A — mid-engagement reassignment notify (intern internal mail).
        // Only fires when (a) lifecycle is ACTIVE (so the intern has a
        // company mailbox the bridge can route to) and (b) the role
        // actually changed hands (previous != null AND previous != new).
        if ("ACTIVE".equals(lc.getActiveStatus()) && lc.getUserId() != null) {
            notifyInternOfReassignment(lc.getUserId(), prevTrainerId, trainer.getId(),
                    "Trainer", trainer);
            notifyInternOfReassignment(lc.getUserId(), prevEvaluatorId, evaluator.getId(),
                    "Evaluator", evaluator);
            if (manager != null) {
                notifyInternOfReassignment(lc.getUserId(), prevManagerId, manager.getId(),
                        "Manager", manager);
            }
        }
        return detail(lifecycleId);
    }

    /**
     * Send an intern internal-mail when a staff role's owner changes
     * mid-engagement. Skips silently when this is a first-time assignment
     * (previousId == null — covered by the activation team-intro mail) or
     * when nothing actually changed (previousId.equals(newId)).
     */
    private void notifyInternOfReassignment(UUID internUserId, UUID previousId,
                                             UUID newId, String roleWord, User newStaff) {
        if (previousId == null) return;            // first-time assignment
        if (previousId.equals(newId)) return;       // no change
        if (newStaff == null) return;
        try {
            String newName = newStaff.getFullName() != null && !newStaff.getFullName().isBlank()
                    ? newStaff.getFullName() : ("your new " + roleWord);
            String subject = "Your " + roleWord + " has changed — meet " + newName;
            String plain = "Hi,\n\n" + newName + " is now your " + roleWord
                    + " at " + brand.getName() + ". They'll pick up where the previous " + roleWord
                    + " left off — feel free to reach out with anything in-flight."
                    + "\n\nOpen your dashboard: /careers/intern"
                    + "\n\n" + brand.signoff();
            internNotifications.notifyIntern(internUserId, subject, plain, null);
        } catch (Exception e) {
            log.warn("[ErmNewHire] reassignment intern-mail failed (non-fatal) intern={} role={}: {}",
                    internUserId, roleWord, e.getMessage());
        }
    }

    /**
     * Phase 8.6.4 — inline Manager assignment. Non-blocking; Manager can be
     * set / changed / cleared at any lifecycle point. Doesn't touch the
     * {@code reporting_structure_complete} flag (which now means T+E only).
     */
    @Transactional
    public ErmOfferDtos.NewHireDetail assignManager(
            UUID lifecycleId, UUID managerUserId, User caller) {
        InternLifecycle lc = lifecycleRepository.findById(lifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + lifecycleId));
        UUID previous = lc.getManagerId();
        User newManager = null;
        if (managerUserId == null) {
            lc.setManagerId(null);
        } else {
            newManager = requireRole(managerUserId, UserRole.MANAGER, "Manager");
            lc.setManagerId(newManager.getId());
        }
        lifecycleRepository.save(lc);

        Map<String, Object> before = new java.util.LinkedHashMap<>();
        before.put("managerId", previous != null ? previous.toString() : null);
        Map<String, Object> after = new java.util.LinkedHashMap<>();
        after.put("managerId", lc.getManagerId() != null ? lc.getManagerId().toString() : null);
        writeAudit(caller.getId(), lc.getUserId(),
                "MANAGER_ASSIGNED", "InternLifecycle", lc.getId(),
                before, after);

        // Tier A — mid-engagement Manager reassignment notify.
        if ("ACTIVE".equals(lc.getActiveStatus()) && lc.getUserId() != null && newManager != null) {
            notifyInternOfReassignment(lc.getUserId(), previous, newManager.getId(),
                    "Manager", newManager);
        }
        return detail(lifecycleId);
    }

    // ── Update tentative start date (lifecycle-side) ──────────────────────

    @Transactional
    public ErmOfferDtos.NewHireDetail updateTentativeStartDate(
            UUID lifecycleId, java.time.LocalDate newDate, User caller) {
        if (newDate == null || !newDate.isAfter(java.time.LocalDate.now())) {
            throw new BadRequestException("newDate must be at least tomorrow");
        }
        InternLifecycle lc = lifecycleRepository.findById(lifecycleId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "InternLifecycle not found: " + lifecycleId));
        java.time.LocalDate previous = lc.getTentativeStartDate();
        lc.setTentativeStartDate(newDate);
        lifecycleRepository.save(lc);
        Map<String, Object> startBefore = new LinkedHashMap<>();
        startBefore.put("tentativeStartDate", previous != null ? previous.toString() : null);
        writeAudit(caller.getId(), lc.getUserId(),
                "TENTATIVE_START_DATE_UPDATED", "InternLifecycle", lc.getId(),
                startBefore,
                Map.of("tentativeStartDate", newDate.toString()));
        try {
            eventPublisher.publishEvent(
                    new com.anvicorp.api.event.TentativeStartDateUpdatedEvent(
                            lc.getId(), lc.getUserId(), caller.getId(), previous, newDate));
        } catch (Exception e) {
            log.warn("[ErmNewHire] start-date event publish failed: {}", e.getMessage());
        }
        return detail(lifecycleId);
    }

    // ── Eligible users (with workload hint) ───────────────────────────────

    @Transactional(readOnly = true)
    public List<ErmOfferDtos.UserStub> listEligible(UserRole role) {
        return listEligibleAny(java.util.EnumSet.of(role), role);
    }

    /**
     * Union of users across multiple roles, de-duplicated. Used by the
     * evaluator selector — admin can create staff with either
     * {@link UserRole#EVALUATOR} OR {@link UserRole#REPORTING_MANAGER}
     * (the codebase has historical overlap between the two for the
     * evaluator function), so the eligible list must include both.
     *
     * @param roles roles to union
     * @param countingRole role to use for the workload-hint join (the
     *        intern_lifecycles column is keyed off this single role —
     *        for evaluators we count by evaluator_id regardless of
     *        which underlying role the user holds)
     */
    @Transactional(readOnly = true)
    public List<ErmOfferDtos.UserStub> listEligibleAny(java.util.Set<UserRole> roles,
                                                       UserRole countingRole) {
        if (roles == null || roles.isEmpty()) return List.of();
        java.util.Map<UUID, User> deduped = new java.util.LinkedHashMap<>();
        for (UserRole r : roles) {
            for (User u : userRepository.findByRole(r)) {
                if (u == null || u.getId() == null) continue;
                if (!Boolean.TRUE.equals(u.getActive())) continue;
                deduped.putIfAbsent(u.getId(), u);
            }
        }
        List<ErmOfferDtos.UserStub> out = new ArrayList<>(deduped.size());
        for (User u : deduped.values()) {
            int count = countAssignedInterns(u.getId(), countingRole);
            // Surface the friendliest single role label — prefer the
            // primary role the ERM expects to find here. Falls back to
            // any matched role on the user if the primary isn't held.
            String roleLabel = u.getRoles() != null && u.getRoles().contains(countingRole)
                    ? countingRole.name()
                    : roles.stream()
                            .filter(r -> u.getRoles() != null && u.getRoles().contains(r))
                            .findFirst().map(UserRole::name).orElse(countingRole.name());
            out.add(new ErmOfferDtos.UserStub(
                    u.getId(), u.getFullName(), u.getEmail(), roleLabel, count));
        }
        return out;
    }

    private int countAssignedInterns(UUID userId, UserRole role) {
        String col = switch (role) {
            case TRAINER -> "trainer_id";
            case REPORTING_MANAGER -> "evaluator_id";
            case MANAGER -> "manager_id";
            default -> null;
        };
        if (col == null) return 0;
        try {
            Long v = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM intern_lifecycles "
                            + "WHERE active_status IN ('PROSPECTIVE','ACTIVE') "
                            + "  AND " + col + " = ?",
                    Long.class, userId);
            return v != null ? v.intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private User requireRole(UUID userId, UserRole role, String label) {
        return requireAnyRole(userId, java.util.EnumSet.of(role), label);
    }

    /**
     * Accept-any-of-N variant of {@link #requireRole}. Used for the
     * evaluator slot because admin can create accounts with either
     * {@link UserRole#EVALUATOR} or {@link UserRole#REPORTING_MANAGER}
     * (historical role overlap) — the assigned user need only hold one
     * of them.
     */
    private User requireAnyRole(UUID userId, java.util.Set<UserRole> acceptable, String label) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        label + " user not found: " + userId));
        if (u.getRoles() == null
                || acceptable.stream().noneMatch(u.getRoles()::contains)) {
            String roleList = acceptable.stream()
                    .map(UserRole::name)
                    .collect(java.util.stream.Collectors.joining(" or "));
            throw new BadRequestException(
                    label + " must hold role " + roleList + " (user " + userId + ")");
        }
        return u;
    }

    private ErmOfferDtos.UserStub toStub(UUID userId) {
        if (userId == null) return null;
        User u = userRepository.findById(userId).orElse(null);
        if (u == null) return null;
        String role = u.getRoles() != null && !u.getRoles().isEmpty()
                ? u.getRoles().iterator().next().name() : null;
        return new ErmOfferDtos.UserStub(u.getId(), u.getFullName(), u.getEmail(), role, 0);
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
        } catch (Exception e) {
            log.warn("[ErmNewHire] audit write failed: {}", e.getMessage());
        }
    }
}
