package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.everify.CloseCaseRequest;
import com.anvicorp.api.dto.everify.CreateEVerifyCaseRequest;
import com.anvicorp.api.dto.everify.EVerifyCaseResponse;
import com.anvicorp.api.dto.everify.EVerifyCaseSummaryResponse;
import com.anvicorp.api.dto.everify.EVerifyHistoryEntryResponse;
import com.anvicorp.api.dto.everify.UpdateCaseRequest;
import com.anvicorp.api.dto.everify.UpdateStatusRequest;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.EVerifyCase;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.I9Form;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.EVerifyStatus;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.I9Status;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.I9NotCompleteException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.EVerifyCaseRepository;
import com.anvicorp.api.repository.I9FormRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manual E-Verify case tracker. HR submits to real E-Verify externally; this
 * module records the case number + outcome and writes every change to AuditLog
 * for compliance. Real API integration is a Sprint 4 follow-up.
 *
 * Status lifecycle:
 *   PENDING_SUBMISSION -> OPEN
 *   OPEN -> EMPLOYMENT_AUTHORIZED | TENTATIVE_NONCONFIRMATION
 *   TENTATIVE_NONCONFIRMATION -> EMPLOYMENT_AUTHORIZED | FINAL_NONCONFIRMATION
 *   EMPLOYMENT_AUTHORIZED -> CLOSED
 *   FINAL_NONCONFIRMATION -> CLOSED
 *   any -> CLOSED  (admin escape, audit-logged)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EVerifyService {

    private static final Map<EVerifyStatus, Set<EVerifyStatus>> ALLOWED_TRANSITIONS =
            buildTransitions();

    private static Map<EVerifyStatus, Set<EVerifyStatus>> buildTransitions() {
        Map<EVerifyStatus, Set<EVerifyStatus>> m = new EnumMap<>(EVerifyStatus.class);
        m.put(EVerifyStatus.PENDING_SUBMISSION, EnumSet.of(EVerifyStatus.OPEN, EVerifyStatus.CLOSED));
        m.put(EVerifyStatus.OPEN, EnumSet.of(EVerifyStatus.EMPLOYMENT_AUTHORIZED, EVerifyStatus.TENTATIVE_NONCONFIRMATION, EVerifyStatus.CLOSED));
        m.put(EVerifyStatus.TENTATIVE_NONCONFIRMATION, EnumSet.of(EVerifyStatus.EMPLOYMENT_AUTHORIZED, EVerifyStatus.FINAL_NONCONFIRMATION, EVerifyStatus.CLOSED));
        m.put(EVerifyStatus.EMPLOYMENT_AUTHORIZED, EnumSet.of(EVerifyStatus.CLOSED));
        m.put(EVerifyStatus.FINAL_NONCONFIRMATION, EnumSet.of(EVerifyStatus.CLOSED));
        m.put(EVerifyStatus.CLOSED, EnumSet.noneOf(EVerifyStatus.class));
        return m;
    }

    private final EVerifyCaseRepository caseRepository;
    private final I9FormRepository i9FormRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final com.anvicorp.api.notification.NotificationService notificationService;
    private final ObjectMapper objectMapper;

    // ── Commands ────────────────────────────────────────────────────────────

    @Transactional
    public EVerifyCaseResponse createCase(CreateEVerifyCaseRequest req, User creator) {
        I9Form i9 = i9FormRepository.findById(req.getI9FormId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "I-9 form not found: " + req.getI9FormId()));

        // Phase 3 step 7 / GAP A2 — federal sequencing: E-Verify case follows
        // I-9, not the other way around. Hard 403 with code I9_NOT_COMPLETE so
        // the HR UI can render a clean blocker and stay aligned with PED rule 4
        // ("E-Verify is not a prescreening tool"). Audit the denied attempt
        // before throwing so forensic review can see who tried what.
        if (i9.getStatus() != I9Status.COMPLETED) {
            writeGateAudit(i9.getId(), "EVERIFY_BLOCKED_I9_NOT_COMPLETE",
                    creator != null ? creator.getId() : null,
                    "i9.status=" + i9.getStatus());
            throw new I9NotCompleteException(
                    "E-Verify can be created only after Form I-9 is completed.");
        }
        // Defensive: an engagement in BLOCKED_NO_AUTHORIZATION means the
        // candidate is on hold pending HR/legal review. No E-Verify case should
        // be opened in that state even if the I-9 row is somehow COMPLETED.
        Engagement engagement = i9.getEngagement();
        if (engagement != null
                && engagement.getStatus() == EngagementStatus.BLOCKED_NO_AUTHORIZATION) {
            writeGateAudit(i9.getId(), "EVERIFY_BLOCKED_ENGAGEMENT",
                    creator != null ? creator.getId() : null,
                    "engagement=BLOCKED_NO_AUTHORIZATION");
            throw new I9NotCompleteException(
                    "Engagement is on hold pending HR review. E-Verify isn't available.");
        }
        if (caseRepository.existsByI9FormId(i9.getId())) {
            throw new BadRequestException(
                    "E-Verify case already exists for this I-9");
        }

        // Phase 3 step 7 — dueBy = (engagement.actualStartDate ?? plannedStartDate)
        // + 3 business days. Engagement reachability is via the linked I-9 (step 5
        // added i9.engagement). Legacy I-9s with no engagement fall back to
        // firstDayOfEmployment, which the I-9 has populated in either flow.
        LocalDate startDate = resolveStartDate(i9);
        LocalDate dueBy = startDate != null
                ? I9FormService.plusBusinessDays(startDate, 3)
                : null;

        EVerifyCase c = EVerifyCase.builder()
                .i9Form(i9)
                .status(EVerifyStatus.PENDING_SUBMISSION)
                .dueBy(dueBy)
                .photoMatchRequired(false)
                .additionalVerificationRequired(false)
                .createdBy(creator.getId())
                .build();
        c = caseRepository.save(c);
        writeAudit(c.getId(), "CREATE", creator.getId(), null, snapshot(c));
        return toResponse(c);
    }

    /**
     * Resolve the candidate's first-day-of-work. Prefers engagement state
     * (actual over planned) as the source of truth; falls back to the I-9's
     * own firstDayOfEmployment for legacy rows.
     */
    private LocalDate resolveStartDate(I9Form i9) {
        Engagement engagement = i9.getEngagement();
        if (engagement != null) {
            if (engagement.getActualStartDate() != null) return engagement.getActualStartDate();
            if (engagement.getPlannedStartDate() != null) return engagement.getPlannedStartDate();
        }
        return i9.getFirstDayOfEmployment();
    }

    @Transactional
    public EVerifyCaseResponse updateFields(UUID caseId, UpdateCaseRequest req, User actor) {
        EVerifyCase c = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "E-Verify case not found: " + caseId));

        Map<String, Object> before = snapshot(c);

        boolean newCaseNumberJustSet =
                isBlank(c.getCaseNumber())
                        && req.getCaseNumber() != null
                        && !req.getCaseNumber().isBlank();

        if (req.getCaseNumber() != null) c.setCaseNumber(req.getCaseNumber());
        if (req.getPhotoMatchRequired() != null)
            c.setPhotoMatchRequired(req.getPhotoMatchRequired());
        if (req.getPhotoMatchResult() != null)
            c.setPhotoMatchResult(req.getPhotoMatchResult());
        if (req.getAdditionalVerificationRequired() != null)
            c.setAdditionalVerificationRequired(req.getAdditionalVerificationRequired());
        if (req.getNotes() != null) c.setNotes(req.getNotes());

        // Auto-promote PENDING_SUBMISSION → OPEN when a case number is first set.
        boolean transitionedToOpen = false;
        if (newCaseNumberJustSet && c.getStatus() == EVerifyStatus.PENDING_SUBMISSION) {
            c.setStatus(EVerifyStatus.OPEN);
            c.setOpenedAt(Instant.now());
            transitionedToOpen = true;
        }

        c = caseRepository.save(c);
        writeAudit(c.getId(), "UPDATE", actor.getId(), before, snapshot(c));

        // Batch-2 — case opened. Fire only on the real transition, not on
        // subsequent updates against an already-OPEN case. Idempotent per
        // (event, case_id); best-effort.
        if (transitionedToOpen) {
            try {
                notificationService.sendEVerifyCaseOpened(c);
            } catch (Exception e) {
                log.warn("EVERIFY_CASE_OPENED notify failed (non-fatal) for case {}: {}",
                        c.getId(), e.getMessage());
            }
        }
        return toResponse(c);
    }

    @Transactional
    public EVerifyCaseResponse updateStatus(UUID caseId, UpdateStatusRequest req, User actor) {
        EVerifyCase c = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "E-Verify case not found: " + caseId));

        EVerifyStatus from = c.getStatus();
        EVerifyStatus to = req.getStatus();
        Set<EVerifyStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(
                from, EnumSet.noneOf(EVerifyStatus.class));
        if (!allowed.contains(to)) {
            throw new BadRequestException(
                    "Invalid status transition: " + from + " → " + to);
        }

        Map<String, Object> before = snapshot(c);
        c.setStatus(to);

        if (from == EVerifyStatus.PENDING_SUBMISSION && to == EVerifyStatus.OPEN
                && c.getOpenedAt() == null) {
            c.setOpenedAt(Instant.now());
        }
        if (to == EVerifyStatus.CLOSED && c.getClosedAt() == null) {
            c.setClosedAt(Instant.now());
        }
        if (req.getNotes() != null && !req.getNotes().isBlank()) {
            c.setNotes(appendNote(c.getNotes(), req.getNotes(), actor));
        }

        c = caseRepository.save(c);
        writeAudit(c.getId(), "STATUS_CHANGE", actor.getId(), before, snapshot(c));

        // Batch-2 — status-driven notifications. Three branches:
        //  - case just opened (PENDING_SUBMISSION → OPEN): same as the
        //    updateFields auto-promote path
        //  - TNC: high-priority alert
        //  - EMPLOYMENT_AUTHORIZED: favorable clearance
        // Each is idempotent per (event, case_id). Failures never roll back
        // the status change.
        try {
            if (from == EVerifyStatus.PENDING_SUBMISSION && to == EVerifyStatus.OPEN) {
                notificationService.sendEVerifyCaseOpened(c);
            } else if (to == EVerifyStatus.TENTATIVE_NONCONFIRMATION) {
                notificationService.sendEVerifyTncAlert(c);
            } else if (to == EVerifyStatus.EMPLOYMENT_AUTHORIZED) {
                notificationService.sendEVerifyCleared(c);
            }
        } catch (Exception e) {
            log.warn("E-Verify status notify failed (non-fatal) for case {} {} -> {}: {}",
                    c.getId(), from, to, e.getMessage());
        }
        return toResponse(c);
    }

    @Transactional
    public EVerifyCaseResponse closeCase(UUID caseId, CloseCaseRequest req, User actor) {
        EVerifyCase c = caseRepository.findById(caseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "E-Verify case not found: " + caseId));

        if (c.getStatus() == EVerifyStatus.CLOSED) {
            throw new BadRequestException("Case is already closed");
        }

        Map<String, Object> before = snapshot(c);
        c.setStatus(EVerifyStatus.CLOSED);
        c.setClosedAt(Instant.now());
        c.setClosureReason(req.getClosureReason());
        if (req.getNotes() != null && !req.getNotes().isBlank()) {
            c.setNotes(appendNote(c.getNotes(), req.getNotes(), actor));
        }
        c = caseRepository.save(c);
        writeAudit(c.getId(), "CLOSE", actor.getId(), before, snapshot(c));
        return toResponse(c);
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public EVerifyCaseResponse getById(UUID caseId) {
        // Phase-3 sweep — fetch graph eagerly so toResponse's candidate-name
        // reads through the I-9 don't 500 under open-in-view=false.
        return toResponse(caseRepository.findByIdWithGraph(caseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "E-Verify case not found: " + caseId)));
    }

    @Transactional(readOnly = true)
    public EVerifyCaseResponse getByI9FormId(UUID i9FormId) {
        return toResponse(caseRepository.findByI9FormIdWithGraph(i9FormId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No E-Verify case for I-9: " + i9FormId)));
    }

    @Transactional(readOnly = true)
    public Page<EVerifyCaseSummaryResponse> list(EVerifyStatus status, Pageable pageable) {
        // Phase-3 sweep — fetch graph (I-9 → candidate → user) eagerly so the
        // HR list doesn't 500 mid-page-map under open-in-view=false.
        Page<EVerifyCase> page = status != null
                ? caseRepository.findByStatusWithGraph(status, pageable)
                : caseRepository.findAllWithGraph(pageable);
        return page.map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public java.util.List<EVerifyHistoryEntryResponse> getHistory(UUID caseId) {
        if (!caseRepository.existsById(caseId)) {
            throw new ResourceNotFoundException("E-Verify case not found: " + caseId);
        }
        return auditLogRepository
                .findByEntityTypeAndEntityIdOrderByTimestampDesc("EVerifyCase", caseId)
                .stream()
                .map(this::toHistoryEntry)
                .toList();
    }

    private EVerifyHistoryEntryResponse toHistoryEntry(AuditLog entry) {
        User performer = entry.getUserId() != null
                ? userRepository.findById(entry.getUserId()).orElse(null)
                : null;
        String performerName = performer != null ? performer.getFullName() : "System";
        String performerRole = performer != null && performer.getRoles() != null
                && !performer.getRoles().isEmpty()
                ? performer.getRoles().iterator().next().name()
                : null;
        String summary = switch (entry.getAction()) {
            case "CREATE" -> "E-Verify case created";
            case "UPDATE" -> "Case details updated by " + performerName;
            case "STATUS_CHANGE" -> "Status changed by " + performerName;
            case "CLOSE" -> "Case closed by " + performerName;
            default -> entry.getAction();
        };
        return EVerifyHistoryEntryResponse.builder()
                .auditId(entry.getId())
                .timestamp(entry.getTimestamp())
                .action(entry.getAction())
                .performedByName(performerName)
                .performedByRole(performerRole)
                .summary(summary)
                .build();
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    public EVerifyCaseResponse toResponse(EVerifyCase c) {
        I9Form i9 = c.getI9Form();
        Candidate candidate = i9 != null ? i9.getCandidate() : null;
        User candidateUser = candidate != null ? candidate.getUser() : null;
        String phase = derivePhase(c.getStatus());
        boolean overdue = isOverdue(c.getDueBy(), phase);
        return EVerifyCaseResponse.builder()
                .id(c.getId())
                .i9FormId(i9 != null ? i9.getId() : null)
                .candidateName(candidateUser != null ? candidateUser.getFullName() : null)
                .candidateEmail(candidateUser != null ? candidateUser.getEmail() : null)
                .candidateId(candidate != null ? candidate.getId() : null)
                .caseNumber(c.getCaseNumber())
                .status(c.getStatus())
                .closureReason(c.getClosureReason())
                .openedAt(c.getOpenedAt())
                .closedAt(c.getClosedAt())
                .photoMatchRequired(c.getPhotoMatchRequired())
                .photoMatchResult(c.getPhotoMatchResult())
                .additionalVerificationRequired(c.getAdditionalVerificationRequired())
                .notes(c.getNotes())
                .daysOpen(computeDaysOpen(c))
                .dueBy(c.getDueBy())
                .phase(phase)
                .overdue(overdue)
                .lastSyncedAt(c.getLastSyncedAt())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .createdByName(lookupUserName(c.getCreatedBy()))
                .build();
    }

    public EVerifyCaseSummaryResponse toSummary(EVerifyCase c) {
        I9Form i9 = c.getI9Form();
        Candidate candidate = i9 != null ? i9.getCandidate() : null;
        User candidateUser = candidate != null ? candidate.getUser() : null;
        String phase = derivePhase(c.getStatus());
        boolean overdue = isOverdue(c.getDueBy(), phase);
        return EVerifyCaseSummaryResponse.builder()
                .id(c.getId())
                .i9FormId(i9 != null ? i9.getId() : null)
                .candidateName(candidateUser != null ? candidateUser.getFullName() : null)
                .candidateEmail(candidateUser != null ? candidateUser.getEmail() : null)
                .caseNumber(c.getCaseNumber())
                .status(c.getStatus())
                .openedAt(c.getOpenedAt())
                .closedAt(c.getClosedAt())
                .daysOpen(computeDaysOpen(c))
                .dueBy(c.getDueBy())
                .phase(phase)
                .overdue(overdue)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    /**
     * Phase 3 step 7 — UI-friendly coarse phase. The rich EVerifyStatus enum
     * stays the source of truth in audits + workflow; this is the shorthand
     * the dashboard renders.
     */
    private static String derivePhase(EVerifyStatus status) {
        if (status == null) return null;
        return switch (status) {
            case PENDING_SUBMISSION, OPEN -> "CREATED";
            case EMPLOYMENT_AUTHORIZED -> "AUTHORIZED";
            case TENTATIVE_NONCONFIRMATION -> "IN_REVIEW";
            case FINAL_NONCONFIRMATION -> "NOT_AUTHORIZED";
            case CLOSED -> "CLOSED";
        };
    }

    private static boolean isOverdue(LocalDate dueBy, String phase) {
        if (dueBy == null) return false;
        if ("AUTHORIZED".equals(phase)) return false;
        return dueBy.isBefore(LocalDate.now());
    }

    private Long computeDaysOpen(EVerifyCase c) {
        Instant start = c.getOpenedAt() != null ? c.getOpenedAt() : c.getCreatedAt();
        if (start == null) return null;
        Instant end = c.getClosedAt() != null ? c.getClosedAt() : Instant.now();
        return ChronoUnit.DAYS.between(start, end);
    }

    private String lookupUserName(UUID userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(User::getFullName).orElse(null);
    }

    // ── Notes append ────────────────────────────────────────────────────────

    private String appendNote(String existing, String newNote, User actor) {
        String prefix = "[" + LocalDate.now() + "] "
                + (actor != null ? actor.getFullName() : "System") + ": ";
        String entry = prefix + newNote.trim();
        if (existing == null || existing.isBlank()) {
            return entry;
        }
        return entry + "\n\n" + existing;
    }

    // ── Audit log ───────────────────────────────────────────────────────────

    private Map<String, Object> snapshot(EVerifyCase c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("i9FormId", c.getI9Form() != null ? c.getI9Form().getId() : null);
        m.put("caseNumber", c.getCaseNumber());
        m.put("status", c.getStatus());
        m.put("closureReason", c.getClosureReason());
        m.put("openedAt", c.getOpenedAt());
        m.put("closedAt", c.getClosedAt());
        m.put("dueBy", c.getDueBy());
        m.put("lastSyncedAt", c.getLastSyncedAt());
        m.put("photoMatchRequired", c.getPhotoMatchRequired());
        m.put("photoMatchResult", c.getPhotoMatchResult());
        m.put("additionalVerificationRequired", c.getAdditionalVerificationRequired());
        m.put("notes", c.getNotes());
        return m;
    }

    private void writeAudit(UUID caseId, String action, UUID userId,
                            Map<String, Object> before, Map<String, Object> after) {
        AuditLog entry = AuditLog.builder()
                .entityType("EVerifyCase")
                .entityId(caseId)
                .action(action)
                .userId(userId)
                .beforeJson(serialize(before))
                .afterJson(serialize(after))
                .build();
        auditLogRepository.save(entry);
    }

    /**
     * GAP A2 — audit row for a denied E-Verify create attempt. Keyed on the
     * I-9 form id (no EVerifyCase exists yet at block time). afterJson carries
     * the structured reason so forensic review can see why.
     */
    private void writeGateAudit(UUID i9FormId, String action, UUID actorId, String reason) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reason", reason);
        AuditLog entry = AuditLog.builder()
                .entityType("I9Form")
                .entityId(i9FormId)
                .action(action)
                .userId(actorId)
                .afterJson(serialize(after))
                .build();
        auditLogRepository.save(entry);
    }

    private String serialize(Map<String, Object> snapshot) {
        if (snapshot == null) return null;
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize E-Verify audit snapshot: {}", e.getMessage());
            return new HashMap<>(snapshot).toString();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
