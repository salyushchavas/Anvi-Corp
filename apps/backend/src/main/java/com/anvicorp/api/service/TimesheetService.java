package com.anvicorp.api.service;

import com.anvicorp.api.dto.supervised.InternTimesheetMonthResponse;
import com.anvicorp.api.dto.supervised.LogTimesheetRequest;
import com.anvicorp.api.dto.supervised.RejectTimesheetRequest;
import com.anvicorp.api.dto.supervised.TimesheetDayResponse;
import com.anvicorp.api.dto.supervised.TimesheetListResponse;
import com.anvicorp.api.dto.supervised.TimesheetResponse;
import com.anvicorp.api.dto.supervised.TimesheetWeekResponse;
import com.anvicorp.api.dto.supervised.UpdateTimesheetRequest;
import com.anvicorp.api.service.timesheet.MonthWeeks;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.Timesheet;
import com.anvicorp.api.entity.TimesheetDay;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WeeklyReport;
import com.anvicorp.api.enums.TimesheetStatus;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.enums.WeeklyReportStatus;
import com.anvicorp.api.event.TimesheetApprovedEvent;
import com.anvicorp.api.event.TimesheetRejectedEvent;
import com.anvicorp.api.event.TimesheetSubmittedEvent;
import com.anvicorp.api.event.TimesheetVerifiedEvent;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.TimesheetDayRepository;
import com.anvicorp.api.repository.TimesheetRepository;
import com.anvicorp.api.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TimesheetService {

    private static final BigDecimal MAX_HOURS = new BigDecimal("168");

    private final TimesheetRepository timesheetRepository;
    private final TimesheetDayRepository timesheetDayRepository;
    private final CandidateRepository candidateRepository;
    private final EngagementService engagementService;
    private final LifecycleAccessPolicy lifecycleAccessPolicy;
    private final InternLifecycleRepository internLifecycleRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final WeeklyReportRepository weeklyReportRepository;

    /**
     * Weekly-report gate: an intern cannot submit the timesheet for a
     * given week until they have SUBMITTED (or a downstream RETURNED /
     * APPROVED) the {@link WeeklyReport} for the same {@code weekStart}.
     * DRAFT reports and missing rows both fail this gate. Message wording
     * is stable so the frontend can pattern-match if it ever needs to.
     */
    private void ensureWeeklyReportSubmitted(Candidate intern, LocalDate weekStart) {
        if (intern == null || weekStart == null) return;
        java.util.Optional<WeeklyReport> report = weeklyReportRepository
                .findByInternIdAndWeekStart(intern.getId(), weekStart);
        // A missing row means the intern hasn't started the weekly report
        // at all; a DRAFT row means they started but didn't hit Submit.
        // Both block the timesheet submit — the operator asked for the
        // report to precede the timesheet, and DRAFT reports never reach
        // a reviewer.
        boolean submitted = report.isPresent()
                && report.get().getStatus() != null
                && report.get().getStatus() != WeeklyReportStatus.DRAFT;
        if (!submitted) {
            throw new BadRequestException(
                    "Submit this week's weekly report before submitting the timesheet.");
        }
    }

    @Transactional
    public TimesheetResponse logHours(LogTimesheetRequest req, User caller) {
        // Phase 8: block new timesheet creation for inactive interns.
        lifecycleAccessPolicy.ensureCanWrite(caller, caller.getId(),
                LifecycleAccessPolicy.WriteIntent.CREATE_NEW);
        validateHours(req.getHours());
        Candidate intern = candidateRepository.findByUserId(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Candidate profile not found for user " + caller.getId()));

        boolean submitNow = Boolean.TRUE.equals(req.getSubmit());
        // Same gate as the /submit endpoint: if the intern is creating +
        // submitting in one shot, the week's WeeklyReport must already
        // be past DRAFT. Doesn't apply to plain draft-save.
        if (submitNow) {
            ensureWeeklyReportSubmitted(intern, req.getWeekStart());
        }
        // Phase 3 step 8 — link to the intern's active engagement when one
        // exists. Null is fine: row stays reachable via the intern-keyed
        // queries; step-11 backfill (opt-in) handles legacy rows.
        Engagement engagement = engagementService
                .resolveActiveForCandidate(intern.getId())
                .orElse(null);
        Timesheet t = Timesheet.builder()
                .intern(intern)
                .engagement(engagement)
                .weekStart(req.getWeekStart())
                .hours(req.getHours())
                .description(req.getDescription())
                .status(submitNow ? TimesheetStatus.SUBMITTED : TimesheetStatus.DRAFT)
                .build();
        t = timesheetRepository.save(t);
        return toResponse(timesheetRepository.findByIdWithGraph(t.getId())
                .orElseThrow(() -> new IllegalStateException("Just-created timesheet vanished")));
    }

    @Transactional
    public TimesheetResponse update(UUID id, UpdateTimesheetRequest req, User caller) {
        validateHours(req.getHours());
        Timesheet t = loadOwned(id, caller);
        if (t.getStatus() != TimesheetStatus.DRAFT && t.getStatus() != TimesheetStatus.REJECTED) {
            throw new BadRequestException(
                    "Only DRAFT or REJECTED timesheets can be edited (current: " + t.getStatus() + ")");
        }
        t.setHours(req.getHours());
        t.setDescription(req.getDescription());
        timesheetRepository.save(t);
        return toResponse(t);
    }

    @Transactional
    public TimesheetResponse submit(UUID id, User caller) {
        // Phase 8: intern submit blocked when lifecycle inactive.
        lifecycleAccessPolicy.ensureCanWrite(caller, caller.getId(),
                LifecycleAccessPolicy.WriteIntent.CREATE_NEW);
        Timesheet t = loadOwned(id, caller);
        if (t.getStatus() == TimesheetStatus.SUBMITTED || t.getStatus() == TimesheetStatus.APPROVED) {
            // Idempotent for SUBMITTED; APPROVED is a no-op too.
            return toResponse(t);
        }
        if (t.getStatus() != TimesheetStatus.DRAFT && t.getStatus() != TimesheetStatus.REJECTED) {
            throw new BadRequestException(
                    "Only DRAFT or REJECTED timesheets can be submitted (current: " + t.getStatus() + ")");
        }
        // Weekly-report-precedes-timesheet gate: the intern must have
        // submitted their weekly report for this week before the
        // timesheet can move to SUBMITTED. Runs after the status
        // pre-checks so an already-SUBMITTED / APPROVED row stays
        // idempotent even if the report was later un-done somehow.
        ensureWeeklyReportSubmitted(t.getIntern(), t.getWeekStart());
        // Clear any stale rejection reason on resubmit.
        t.setReviewNote(null);
        t.setStatus(TimesheetStatus.SUBMITTED);
        // Clear stale verified stamp on resubmit (status moved backward).
        t.setVerifiedBy(null);
        t.setVerifiedAt(null);
        timesheetRepository.save(t);
        publishChainEvent(new TimesheetSubmittedEvent(
                t.getId(), internUserIdOf(t), caller.getId()));
        return toResponse(t);
    }

    @Transactional(readOnly = true)
    public TimesheetListResponse listMine(User caller) {
        List<TimesheetResponse> entries = timesheetRepository.findForCandidateUser(caller.getId())
                .stream().map(this::toResponse).toList();
        BigDecimal total = timesheetRepository.sumApprovedHoursForCandidateUser(caller.getId());
        return TimesheetListResponse.builder()
                .entries(entries)
                .totalApprovedHours(total != null ? total : BigDecimal.ZERO)
                .build();
    }

    @Transactional(readOnly = true)
    public TimesheetListResponse listForIntern(UUID candidateId) {
        if (!candidateRepository.existsById(candidateId)) {
            throw new ResourceNotFoundException("Candidate not found: " + candidateId);
        }
        List<TimesheetResponse> entries = timesheetRepository.findForIntern(candidateId)
                .stream().map(this::toResponse).toList();
        BigDecimal total = timesheetRepository.sumApprovedHoursForIntern(candidateId);
        return TimesheetListResponse.builder()
                .entries(entries)
                .totalApprovedHours(total != null ? total : BigDecimal.ZERO)
                .build();
    }

    /**
     * Phase B2 — ERM-side verification. Transition {@code SUBMITTED → VERIFIED}
     * and stamp {@code verified_by} / {@code verified_at}. The Manager
     * approve gate downstream now requires VERIFIED, so verification is
     * the mandatory middle stage of the chain.
     *
     * <p>The role + ERM-scope gate lives on {@code ErmTimesheetVerifyService}
     * (mirrors {@code ManagerTimesheetApprovalService}); this method just
     * enforces the state machine + access policy.</p>
     */
    @Transactional
    public TimesheetResponse verify(UUID id, User caller) {
        Timesheet t = timesheetRepository.findByIdWithGraph(id)
                .orElseThrow(() -> new ResourceNotFoundException("Timesheet not found: " + id));
        Candidate ownerForCheck = t.getIntern();
        UUID ownerUserId = ownerForCheck != null && ownerForCheck.getUser() != null
                ? ownerForCheck.getUser().getId() : null;
        lifecycleAccessPolicy.ensureCanWrite(caller, ownerUserId,
                LifecycleAccessPolicy.WriteIntent.RESOLVE_EXISTING);
        if (t.getStatus() == TimesheetStatus.VERIFIED
                || t.getStatus() == TimesheetStatus.APPROVED) {
            // Idempotent on re-verify; already-approved is also a no-op.
            return toResponse(t);
        }
        if (t.getStatus() != TimesheetStatus.SUBMITTED) {
            throw new BadRequestException(
                    "Only SUBMITTED timesheets can be verified (current: " + t.getStatus() + ")");
        }
        t.setStatus(TimesheetStatus.VERIFIED);
        t.setVerifiedBy(caller);
        t.setVerifiedAt(Instant.now());
        t.setReviewNote(null);
        timesheetRepository.save(t);
        publishChainEvent(new TimesheetVerifiedEvent(
                t.getId(), internUserIdOf(t), caller.getId()));
        return toResponse(t);
    }

    /**
     * Phase B2 — Manager approve now requires {@link TimesheetStatus#VERIFIED}.
     * Attempting to approve a still-SUBMITTED row returns 400 with a clear
     * message — the ERM verify step is mandatory.
     */
    @Transactional
    public TimesheetResponse approve(UUID id, User caller) {
        Timesheet t = timesheetRepository.findByIdWithGraph(id)
                .orElseThrow(() -> new ResourceNotFoundException("Timesheet not found: " + id));
        // Phase 8: approver action on an exited intern is RESOLVE_EXISTING — allowed
        // for 30 days post-exit (cleanup window).
        Candidate ownerForCheck = t.getIntern();
        UUID ownerUserId = ownerForCheck != null && ownerForCheck.getUser() != null
                ? ownerForCheck.getUser().getId() : null;
        // IDOR guard: only the intern's assigned trainer / reporting
        // manager / ERM (or any MANAGER — org-wide by design per
        // ManagerTimesheetApprovalService) may approve. Unowned callers
        // get 404, not 403, so cross-owner probing can't confirm the row
        // exists. SUPER_ADMIN bypasses.
        ensureTimesheetOwnsOr404(t, ownerUserId, caller, id, "timesheet.approve");
        lifecycleAccessPolicy.ensureCanWrite(caller, ownerUserId,
                LifecycleAccessPolicy.WriteIntent.RESOLVE_EXISTING);
        if (t.getStatus() == TimesheetStatus.APPROVED) {
            return toResponse(t); // idempotent
        }
        if (t.getStatus() != TimesheetStatus.VERIFIED) {
            throw new BadRequestException(
                    "Only VERIFIED timesheets can be approved (current: " + t.getStatus()
                            + "). The ERM must verify the week first.");
        }
        t.setStatus(TimesheetStatus.APPROVED);
        t.setApprovedBy(caller);
        t.setApprovedAt(Instant.now());
        t.setReviewNote(null);
        timesheetRepository.save(t);
        publishChainEvent(new TimesheetApprovedEvent(
                t.getId(), internUserIdOf(t), caller.getId()));
        return toResponse(t);
    }

    /**
     * Reviewer rejects a SUBMITTED (ERM stage) or VERIFIED (Manager stage)
     * timesheet back to the intern. Reason is required and shown verbatim
     * on the intern's REJECTED card (B1's reviewNote channel). Clears any
     * approver/verifier stamps so the response doesn't leak stale
     * attribution.
     */
    @Transactional
    public TimesheetResponse reject(UUID id, RejectTimesheetRequest req, User caller) {
        Timesheet t = timesheetRepository.findByIdWithGraph(id)
                .orElseThrow(() -> new ResourceNotFoundException("Timesheet not found: " + id));
        Candidate ownerForCheck = t.getIntern();
        UUID ownerUserId = ownerForCheck != null && ownerForCheck.getUser() != null
                ? ownerForCheck.getUser().getId() : null;
        // IDOR guard mirror of approve().
        ensureTimesheetOwnsOr404(t, ownerUserId, caller, id, "timesheet.reject");
        lifecycleAccessPolicy.ensureCanWrite(caller, ownerUserId,
                LifecycleAccessPolicy.WriteIntent.RESOLVE_EXISTING);
        if (t.getStatus() != TimesheetStatus.SUBMITTED
                && t.getStatus() != TimesheetStatus.VERIFIED) {
            throw new BadRequestException(
                    "Only SUBMITTED or VERIFIED timesheets can be rejected (current: "
                            + t.getStatus() + ")");
        }
        String reason = req != null ? req.getReason() : null;
        if (reason == null || reason.trim().length() < 5) {
            throw new BadRequestException(
                    "A reason of at least 5 characters is required to reject a timesheet.");
        }
        TimesheetStatus from = t.getStatus();
        t.setStatus(TimesheetStatus.REJECTED);
        t.setReviewNote(reason.trim());
        t.setApprovedBy(null);
        t.setApprovedAt(null);
        t.setVerifiedBy(null);
        t.setVerifiedAt(null);
        timesheetRepository.save(t);
        publishChainEvent(new TimesheetRejectedEvent(
                t.getId(), internUserIdOf(t), caller.getId(),
                from.name(), reason.trim()));
        return toResponse(t);
    }

    private UUID internUserIdOf(Timesheet t) {
        return t.getIntern() != null && t.getIntern().getUser() != null
                ? t.getIntern().getUser().getId() : null;
    }

    private void publishChainEvent(Object event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception ignored) {
            // Listeners are best-effort; the event publisher itself
            // shouldn't crash a state transition.
        }
    }

    /**
     * IDOR guard for the reviewer paths (approve / reject). Resolves the
     * intern's lifecycle and requires the caller to be one of:
     *
     * <ul>
     *   <li>SUPER_ADMIN — always bypasses.</li>
     *   <li>Any MANAGER — org-wide oversight per the canonical design
     *       (see {@code ManagerTimesheetApprovalService}).</li>
     *   <li>The intern's assigned {@code trainer_id}, {@code manager_id},
     *       or {@code erm_id} on the lifecycle row.</li>
     * </ul>
     *
     * <p>If the lifecycle is missing or has null slots for all three roles
     * (single-org / pre-Phase-3 legacy), any TRAINER / REPORTING_MANAGER
     * / ERM caller (the controller's coarse role gate) passes — mirrors
     * the {@link com.anvicorp.api.trainer.TrainerScopeGuard} +
     * {@link com.anvicorp.api.evaluator.EvaluatorScopeGuard} null-FK
     * fallback so we don't lock reviewers out of newly-active interns
     * whose reporting structure hasn't been fully populated yet.</p>
     *
     * <p>On mismatch throws {@link ResourceNotFoundException} — no 403 —
     * so cross-owner probing can't confirm the row exists.</p>
     */
    private void ensureTimesheetOwnsOr404(Timesheet t, UUID internUserId, User caller,
                                           UUID timesheetId, String endpointName) {
        if (caller == null) throw new ForbiddenException("Authentication required.");
        if (isSuperAdmin(caller)) return;
        // MANAGER role passes org-wide by design.
        if (hasRole(caller, UserRole.MANAGER)) return;

        InternLifecycle lc = internUserId != null
                ? internLifecycleRepository.findByUserId(internUserId).orElse(null)
                : null;

        if (lc == null) {
            // Pre-lifecycle / applicant intern — no scope to enforce; the
            // coarse role gate already required TRAINER/REPORTING_MANAGER/ERM.
            log.debug("[TimesheetService] no lifecycle for internUserId={} — "
                    + "allowing {} caller {} on timesheet {}",
                    internUserId, endpointName, caller.getId(), timesheetId);
            return;
        }

        UUID callerId = caller.getId();
        boolean isTrainerSlot = lc.getTrainerId() != null
                && lc.getTrainerId().equals(callerId);
        boolean isManagerSlot = lc.getManagerId() != null
                && lc.getManagerId().equals(callerId);
        boolean isErmSlot = lc.getErmId() != null
                && lc.getErmId().equals(callerId);
        if (isTrainerSlot || isManagerSlot || isErmSlot) return;

        // Null-slot fallback: if every reviewer slot is null, treat any
        // role-gated caller as the de-facto owner (parallels the
        // TrainerScopeGuard / EvaluatorScopeGuard null-FK fallback for
        // single-org setups).
        if (lc.getTrainerId() == null && lc.getManagerId() == null
                && lc.getErmId() == null) {
            log.debug("[TimesheetService] all reviewer slots null on lifecycle={} — "
                    + "allowing {} caller {} as de-facto owner",
                    lc.getId(), endpointName, callerId);
            return;
        }

        log.warn("[IDOR-guard] {} caller={} resource={} lifecycle={} reason=not-in-reviewer-slots",
                endpointName, callerId, timesheetId, lc.getId());
        throw new ResourceNotFoundException("Timesheet not found: " + timesheetId);
    }

    /**
     * Loads with the full graph so the candidate-side ownership check on
     * {@code t.intern.user.id} doesn't hit a detached lazy proxy (resume-download
     * lesson — fetch what we touch).
     */
    private Timesheet loadOwned(UUID id, User caller) {
        Timesheet t = timesheetRepository.findByIdWithGraph(id)
                .orElseThrow(() -> new ResourceNotFoundException("Timesheet not found: " + id));
        Candidate intern = t.getIntern();
        if (intern == null || intern.getUser() == null
                || !intern.getUser().getId().equals(caller.getId())) {
            throw new ForbiddenException("Timesheet does not belong to this user");
        }
        return t;
    }

    private void validateHours(BigDecimal hours) {
        if (hours == null
                || hours.compareTo(BigDecimal.ZERO) <= 0
                || hours.compareTo(MAX_HOURS) > 0) {
            throw new BadRequestException("hours must be > 0 and <= 168");
        }
    }

    private TimesheetResponse toResponse(Timesheet t) {
        User approver = t.getApprovedBy();
        return TimesheetResponse.builder()
                .id(t.getId())
                .weekStart(t.getWeekStart())
                .hours(t.getHours())
                .description(t.getDescription())
                .status(t.getStatus())
                .approvedByName(approver != null ? approver.getFullName() : null)
                .approvedAt(t.getApprovedAt())
                .reviewNote(t.getReviewNote())
                .createdAt(t.getCreatedAt())
                .build();
    }

    // ─── Day-by-day week-mode API ───────────────────────────────────────────
    //
    // Additive surface for the intern timesheet entry page + the RM approval
    // queue. The parent {@link Timesheet} row is reused as the weekly
    // container — its {@code hours} is recomputed from the day rows on every
    // PATCH so the legacy weekly-total readers stay correct.

    /**
     * Find-or-create the intern caller's timesheet for {@code weekStart}. A
     * weekStart must be a Monday — otherwise we'd risk multiple rows pointing
     * at overlapping weeks. Newly-created weeks start in DRAFT with no day
     * rows; the UI calls {@link #patchDay} for each cell as the intern types.
     */
    @Transactional
    public TimesheetWeekResponse getOrCreateWeek(LocalDate weekStart, User caller) {
        if (weekStart == null) {
            throw new BadRequestException("weekStart is required.");
        }
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new BadRequestException("weekStart must fall on a Monday.");
        }
        Timesheet t = timesheetRepository
                .findByCandidateUserAndWeek(caller.getId(), weekStart)
                .orElse(null);
        if (t == null) {
            Candidate intern = candidateRepository.findByUserId(caller.getId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Candidate profile not found for user " + caller.getId()));
            Engagement engagement = engagementService
                    .resolveActiveForCandidate(intern.getId())
                    .orElse(null);
            t = Timesheet.builder()
                    .intern(intern)
                    .engagement(engagement)
                    .weekStart(weekStart)
                    .hours(BigDecimal.ZERO)
                    .status(TimesheetStatus.DRAFT)
                    .build();
            t = timesheetRepository.save(t);
        }
        return toWeekResponse(t);
    }

    /**
     * Intern upserts one day of their own week. Recomputes the parent's
     * {@code hours} total. Only DRAFT / REJECTED weeks are editable —
     * SUBMITTED + APPROVED are locked (server enforced).
     */
    @Transactional
    public TimesheetWeekResponse patchDay(UUID timesheetId, DayOfWeek dayOfWeek,
                                          BigDecimal hours, String notes, User caller) {
        // Phase 8: intern editing daily cells blocked when inactive.
        lifecycleAccessPolicy.ensureCanWrite(caller, caller.getId(),
                LifecycleAccessPolicy.WriteIntent.CREATE_NEW);
        if (dayOfWeek == null) {
            throw new BadRequestException("dayOfWeek is required.");
        }
        if (hours == null || hours.compareTo(BigDecimal.ZERO) < 0
                || hours.compareTo(MAX_DAILY_HOURS) > 0) {
            throw new BadRequestException("hours must be between 0 and 24.");
        }
        Timesheet t = loadOwned(timesheetId, caller);
        if (t.getStatus() != TimesheetStatus.DRAFT
                && t.getStatus() != TimesheetStatus.REJECTED) {
            throw new BadRequestException(
                    "Only DRAFT or REJECTED weeks can be edited (current: " + t.getStatus() + ")");
        }

        TimesheetDay day = timesheetDayRepository
                .findByTimesheetIdAndDayOfWeek(t.getId(), dayOfWeek)
                .orElseGet(() -> TimesheetDay.builder()
                        .timesheet(t)
                        .dayOfWeek(dayOfWeek)
                        .hours(BigDecimal.ZERO)
                        .build());
        day.setHours(hours);
        day.setNotes(trimToNull(notes));
        timesheetDayRepository.save(day);

        // Recompute the parent total so legacy readers stay accurate.
        BigDecimal total = timesheetDayRepository.findByTimesheetIdOrderByDayOfWeekAsc(t.getId())
                .stream()
                .map(d -> d.getId() != null && d.getId().equals(day.getId()) ? hours : d.getHours())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        if (total.compareTo(MAX_HOURS) > 0) {
            throw new BadRequestException("Weekly total cannot exceed 168 hours.");
        }
        t.setHours(total);
        timesheetRepository.save(t);

        return toWeekResponse(t);
    }

    /**
     * Phase B1 — month-roster for the intern entry grid. Returns every
     * Mon–Fri work-week touching the requested month (edge weeks
     * partial), each carrying the intern's existing timesheet row if
     * one's been started. Read-only; the UI calls {@link #saveDayForWeek}
     * to upsert cells and {@link #submit} to lock a week.
     */
    @Transactional(readOnly = true)
    public InternTimesheetMonthResponse getMyMonth(YearMonth period, User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required.");
        if (period == null) throw new BadRequestException("year + month are required");

        List<MonthWeeks.WorkWeek> weeks = MonthWeeks.workWeeksOf(period);
        // Bulk-fetch the intern's existing rows for these weeks so we
        // don't N+1 the UI.
        Map<LocalDate, Timesheet> existing = new HashMap<>();
        for (MonthWeeks.WorkWeek w : weeks) {
            timesheetRepository
                    .findByCandidateUserAndWeek(caller.getId(), w.weekStart())
                    .ifPresent(t -> existing.put(w.weekStart(), t));
        }

        List<InternTimesheetMonthResponse.WeekEntry> entries = new ArrayList<>(weeks.size());
        BigDecimal monthTotal = BigDecimal.ZERO;
        int submitted = 0;
        for (MonthWeeks.WorkWeek w : weeks) {
            Timesheet t = existing.get(w.weekStart());
            TimesheetWeekResponse weekResp = null;
            if (t != null) {
                weekResp = toWeekResponse(t);
                // Under the Monday's-month rule each week belongs wholly
                // to one month — sum the parent total directly.
                if (t.getHours() != null) {
                    monthTotal = monthTotal.add(t.getHours());
                }
                TimesheetStatus s = t.getStatus();
                if (s != TimesheetStatus.DRAFT && s != TimesheetStatus.REJECTED) {
                    submitted++;
                }
            }
            entries.add(new InternTimesheetMonthResponse.WeekEntry(
                    w.weekStart(), w.weekNumber(), w.daysInMonth(), weekResp));
        }
        monthTotal = monthTotal.setScale(2, RoundingMode.HALF_UP);
        return new InternTimesheetMonthResponse(
                period.toString(), monthTotal, submitted, weeks.size(), entries);
    }

    /**
     * Upsert one day for the intern's week, getting-or-creating the
     * parent timesheet row on the fly. Same lock + range rules as
     * {@link #patchDay}; collapses the two API hops the UI would
     * otherwise need on the first edit of each week.
     */
    @Transactional
    public TimesheetWeekResponse saveDayForWeek(
            LocalDate weekStart, DayOfWeek dayOfWeek,
            BigDecimal hours, String notes, User caller) {
        if (weekStart == null) {
            throw new BadRequestException("weekStart is required.");
        }
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new BadRequestException("weekStart must fall on a Monday.");
        }
        // getOrCreateWeek enforces own-data + creates a DRAFT shell if needed.
        TimesheetWeekResponse shell = getOrCreateWeek(weekStart, caller);
        return patchDay(shell.id(), dayOfWeek, hours, notes, caller);
    }

    /**
     * Queue of SUBMITTED timesheets — any REPORTING_MANAGER (or SUPER_ADMIN)
     * sees the full queue across all engagements. Per-engagement RM FK is
     * no longer the filter.
     */
    @Transactional(readOnly = true)
    public List<TimesheetWeekResponse> listPendingApproval(User caller) {
        if (caller == null) throw new ForbiddenException("Authentication required.");
        if (!isSuperAdmin(caller) && !hasRole(caller, UserRole.REPORTING_MANAGER)) {
            throw new ForbiddenException(
                    "Only REPORTING_MANAGER or SUPER_ADMIN can view the approval queue.");
        }
        return timesheetRepository.findAllSubmitted().stream()
                .map(this::toWeekResponse)
                .toList();
    }

    /**
     * Detail view for the RM approval page. Role-based: the intern who owns
     * the row, any TECHNICAL_EVALUATOR / REPORTING_MANAGER / OPERATIONS /
     * HR / SUPER_ADMIN. No per-engagement FK match.
     */
    @Transactional(readOnly = true)
    public TimesheetWeekResponse getWeek(UUID timesheetId, User caller) {
        Timesheet t = timesheetRepository.findByIdWithGraph(timesheetId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Timesheet not found: " + timesheetId));
        if (caller == null) throw new ForbiddenException("Authentication required.");
        UUID callerId = caller.getId();
        boolean isOwner = t.getIntern() != null
                && t.getIntern().getUser() != null
                && t.getIntern().getUser().getId().equals(callerId);
        if (!isOwner
                && !isSuperAdmin(caller)
                && !hasRole(caller, UserRole.REPORTING_MANAGER)
                && !hasRole(caller, UserRole.TRAINER)
                && !hasRole(caller, UserRole.ERM)
                && !hasRole(caller, UserRole.ERM)) {
            throw new ForbiddenException("Not authorised to view this timesheet.");
        }
        return toWeekResponse(t);
    }

    private TimesheetWeekResponse toWeekResponse(Timesheet t) {
        List<TimesheetDay> days = timesheetDayRepository
                .findByTimesheetIdOrderByDayOfWeekAsc(t.getId());
        List<TimesheetDayResponse> dayDtos = new ArrayList<>(days.size());
        for (TimesheetDay d : days) {
            dayDtos.add(new TimesheetDayResponse(
                    d.getId(), d.getDayOfWeek(), d.getHours(), d.getNotes()));
        }
        dayDtos.sort(Comparator.comparing(TimesheetDayResponse::dayOfWeek));
        User approver = t.getApprovedBy();
        var intern = t.getIntern();
        var internUser = intern != null ? intern.getUser() : null;
        return new TimesheetWeekResponse(
                t.getId(),
                internUser != null ? internUser.getId() : null,
                internUser != null ? internUser.getFullName() : null,
                t.getWeekStart(),
                t.getStatus(),
                t.getHours(),
                dayDtos,
                t.getReviewNote(),
                approver != null ? approver.getFullName() : null,
                t.getApprovedAt(),
                null,
                t.getCreatedAt()
        );
    }

    private static final BigDecimal MAX_DAILY_HOURS = new BigDecimal("24");

    private static boolean isSuperAdmin(User u) {
        return u.getRoles() != null && u.getRoles().contains(UserRole.SUPER_ADMIN);
    }

    private static boolean hasRole(User u, UserRole role) {
        return u.getRoles() != null && u.getRoles().contains(role);
    }

    private static String trimToNull(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }
}
