package com.anvicorp.api.service;

import com.anvicorp.api.dto.compliance.AlertSeverity;
import com.anvicorp.api.dto.compliance.ComplianceAlert;
import com.anvicorp.api.dto.compliance.ComplianceOverviewResponse;
import com.anvicorp.api.dto.compliance.ComplianceStats;
import com.anvicorp.api.dto.compliance.RecentAction;
import com.anvicorp.api.dto.compliance.UpcomingDeadline;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.EVerifyCase;
import com.anvicorp.api.entity.I983Plan;
import com.anvicorp.api.entity.I9Form;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.EVerifyStatus;
import com.anvicorp.api.enums.I983Status;
import com.anvicorp.api.enums.I9Status;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.EVerifyCaseRepository;
import com.anvicorp.api.repository.I983PlanRepository;
import com.anvicorp.api.repository.I9FormRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregates compliance state across I-9, I-983, E-Verify, and Offer modules
 * into a single HR dashboard payload (stats + alerts + deadlines + recent
 * activity). In-memory reads for v1 demo scale.
 */
@Service
@RequiredArgsConstructor
public class ComplianceOverviewService {

    private static final int SECTION_2_BUSINESS_DAYS = 3;
    private static final long MS_30_DAYS = 30L * 86_400_000L;

    private final I9FormRepository i9FormRepository;
    private final I983PlanRepository i983PlanRepository;
    private final EVerifyCaseRepository everifyCaseRepository;
    private final OfferRepository offerRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public ComplianceOverviewResponse getOverview() {
        List<I9Form> i9s = i9FormRepository.findAll();
        List<I983Plan> i983s = i983PlanRepository.findAll();
        List<EVerifyCase> everifys = everifyCaseRepository.findAll();
        List<Offer> offers = offerRepository.findAll();

        ComplianceStats stats = buildStats(i9s, i983s, everifys, offers);
        List<ComplianceAlert> alerts = buildAlerts(i9s, i983s, everifys);
        List<UpcomingDeadline> deadlines = buildDeadlines(i9s, i983s);
        List<RecentAction> actions = buildRecentActions();

        return ComplianceOverviewResponse.builder()
                .stats(stats)
                .alerts(alerts)
                .upcomingDeadlines(deadlines)
                .recentActions(actions)
                .build();
    }

    // ── Stats ───────────────────────────────────────────────────────────────

    private ComplianceStats buildStats(List<I9Form> i9s, List<I983Plan> i983s,
                                       List<EVerifyCase> everifys, List<Offer> offers) {
        LocalDate today = LocalDate.now();
        Instant cutoff30d = Instant.now().minusMillis(MS_30_DAYS);

        long i9Total = i9s.size();
        long i9Pending = i9s.stream().filter(f -> f.getStatus() != I9Status.COMPLETED).count();
        long i9Completed = i9s.stream().filter(f -> f.getStatus() == I9Status.COMPLETED).count();
        long i9Overdue = i9s.stream().filter(f -> isI9Overdue(f, today)).count();

        long i983Total = i983s.size();
        long i983Draft = countI983(i983s, I983Status.DRAFT)
                + countI983(i983s, I983Status.AMENDMENT_REQUESTED);
        long i983Complete = countI983(i983s, I983Status.COMPLETE);
        long i983SubmittedDso = countI983(i983s, I983Status.SUBMITTED_TO_DSO);
        long i983Approved = countI983(i983s, I983Status.DSO_APPROVED);
        long i983Rejected = countI983(i983s, I983Status.DSO_REJECTED);
        long i983Amendment = countI983(i983s, I983Status.AMENDMENT_REQUESTED);

        long evTotal = everifys.size();
        long evPending = countEverify(everifys, EVerifyStatus.PENDING_SUBMISSION);
        long evOpen = countEverify(everifys, EVerifyStatus.OPEN);
        long evTnc = countEverify(everifys, EVerifyStatus.TENTATIVE_NONCONFIRMATION)
                + countEverify(everifys, EVerifyStatus.FINAL_NONCONFIRMATION);
        long evAuthorized = countEverify(everifys, EVerifyStatus.EMPLOYMENT_AUTHORIZED);
        long evClosed = countEverify(everifys, EVerifyStatus.CLOSED);

        long offersActive = offers.stream().filter(o -> o.getStatus() == OfferStatus.SENT).count();
        long offersPending = offersActive;
        long offersAccepted = offers.stream()
                .filter(o -> o.getStatus() == OfferStatus.ACCEPTED
                        && o.getRespondedAt() != null
                        && !o.getRespondedAt().isBefore(cutoff30d))
                .count();
        long offersDeclined = offers.stream()
                .filter(o -> o.getStatus() == OfferStatus.DECLINED
                        && o.getRespondedAt() != null
                        && !o.getRespondedAt().isBefore(cutoff30d))
                .count();

        return ComplianceStats.builder()
                .i9(ComplianceStats.I9Stats.builder()
                        .total(i9Total).pending(i9Pending)
                        .completed(i9Completed).overdue(i9Overdue).build())
                .i983(ComplianceStats.I983Stats.builder()
                        .total(i983Total).draft(i983Draft).complete(i983Complete)
                        .submittedToDso(i983SubmittedDso).approved(i983Approved)
                        .rejected(i983Rejected).amendment(i983Amendment).build())
                .everify(ComplianceStats.EverifyStats.builder()
                        .total(evTotal).pendingSubmission(evPending).open(evOpen)
                        .tnc(evTnc).authorized(evAuthorized).closed(evClosed).build())
                .offers(ComplianceStats.OfferStats.builder()
                        .totalActive(offersActive).pending(offersPending)
                        .accepted(offersAccepted).declined(offersDeclined).build())
                .build();
    }

    // ── Alerts ──────────────────────────────────────────────────────────────

    private List<ComplianceAlert> buildAlerts(List<I9Form> i9s, List<I983Plan> i983s,
                                              List<EVerifyCase> everifys) {
        List<ComplianceAlert> alerts = new ArrayList<>();
        LocalDate today = LocalDate.now();
        Instant now = Instant.now();

        // CRITICAL: overdue I-9s
        for (I9Form f : i9s) {
            if (!isI9Overdue(f, today)) continue;
            LocalDate due = section2DueDate(f);
            long daysOverdue = due != null
                    ? Math.abs(ChronoUnit.DAYS.between(today, due))
                    : 0L;
            String name = candidateName(f.getCandidate());
            alerts.add(ComplianceAlert.builder()
                    .severity(AlertSeverity.CRITICAL)
                    .title("I-9 overdue for " + name)
                    .description("Section 2 was due " + due + " (" + daysOverdue + " days ago)")
                    .linkUrl("/careers/erm/i9-everify/i9/" + f.getId())
                    .count(null)
                    .build());
        }

        // CRITICAL: TNC cases open > 8 days
        for (EVerifyCase c : everifys) {
            if (c.getStatus() != EVerifyStatus.TENTATIVE_NONCONFIRMATION) continue;
            Instant since = c.getUpdatedAt() != null ? c.getUpdatedAt() : c.getCreatedAt();
            if (since == null) continue;
            long days = Duration.between(since, now).toDays();
            if (days <= 8) continue;
            String name = candidateName(
                    c.getI9Form() != null ? c.getI9Form().getCandidate() : null);
            alerts.add(ComplianceAlert.builder()
                    .severity(AlertSeverity.CRITICAL)
                    .title("E-Verify TNC needs resolution")
                    .description(name + " has had a TNC for " + days
                            + " days. Employee deadline is approaching.")
                    .linkUrl("/careers/erm/i9-everify/everify/" + c.getId())
                    .build());
        }

        // WARNING: I-9 due in next 2 days
        for (I9Form f : i9s) {
            if (f.getStatus() == I9Status.COMPLETED) continue;
            LocalDate due = section2DueDate(f);
            if (due == null || due.isBefore(today)) continue;
            long daysUntil = ChronoUnit.DAYS.between(today, due);
            if (daysUntil > 2) continue;
            String name = candidateName(f.getCandidate());
            alerts.add(ComplianceAlert.builder()
                    .severity(AlertSeverity.WARNING)
                    .title("I-9 due soon for " + name)
                    .description("Section 2 due in " + daysUntil + " day(s) on " + due)
                    .linkUrl("/careers/erm/i9-everify/i9/" + f.getId())
                    .build());
        }

        // WARNING: I-983 amendment requested
        for (I983Plan p : i983s) {
            if (p.getStatus() != I983Status.AMENDMENT_REQUESTED) continue;
            String name = candidateName(p.getCandidate());
            alerts.add(ComplianceAlert.builder()
                    .severity(AlertSeverity.WARNING)
                    .title("I-983 amendment requested for " + name)
                    .description("DSO requested changes — coordinate with ERM")
                    .linkUrl("/careers/erm/training-plans/" + p.getId())
                    .build());
        }

        // WARNING: I-983 COMPLETE > 7 days (not yet submitted to DSO)
        for (I983Plan p : i983s) {
            if (p.getStatus() != I983Status.COMPLETE) continue;
            Instant signed = p.getUpdatedAt();
            if (signed == null) continue;
            long days = Duration.between(signed, now).toDays();
            if (days <= 7) continue;
            String name = candidateName(p.getCandidate());
            alerts.add(ComplianceAlert.builder()
                    .severity(AlertSeverity.WARNING)
                    .title("I-983 ready for DSO submission")
                    .description(name + "'s plan has been complete for " + days + " days")
                    .linkUrl("/careers/erm/training-plans/" + p.getId())
                    .build());
        }

        // INFO: E-Verify PENDING_SUBMISSION > 3 days
        for (EVerifyCase c : everifys) {
            if (c.getStatus() != EVerifyStatus.PENDING_SUBMISSION) continue;
            if (c.getCreatedAt() == null) continue;
            long days = Duration.between(c.getCreatedAt(), now).toDays();
            if (days <= 3) continue;
            String name = candidateName(
                    c.getI9Form() != null ? c.getI9Form().getCandidate() : null);
            alerts.add(ComplianceAlert.builder()
                    .severity(AlertSeverity.INFO)
                    .title("E-Verify case to submit")
                    .description(name + "'s case has been pending for " + days + " days")
                    .linkUrl("/careers/erm/i9-everify/everify/" + c.getId())
                    .build());
        }

        // Sort: severity (CRITICAL > WARNING > INFO), then keep insertion order.
        alerts.sort(Comparator.comparing(ComplianceAlert::getSeverity));

        // Cap at 20
        return alerts.size() > 20 ? alerts.subList(0, 20) : alerts;
    }

    // ── Upcoming deadlines ──────────────────────────────────────────────────

    private List<UpcomingDeadline> buildDeadlines(List<I9Form> i9s, List<I983Plan> i983s) {
        List<UpcomingDeadline> deadlines = new ArrayList<>();
        LocalDate today = LocalDate.now();
        LocalDate i9Window = today.plusDays(14);
        LocalDate i983Window = today.plusDays(90);

        for (I9Form f : i9s) {
            if (f.getStatus() == I9Status.COMPLETED) continue;
            LocalDate due = section2DueDate(f);
            if (due == null || due.isAfter(i9Window)) continue;
            String name = candidateName(f.getCandidate());
            deadlines.add(UpcomingDeadline.builder()
                    .label("I-9 Section 2 due for " + name)
                    .dueDate(due)
                    .daysUntilDue(ChronoUnit.DAYS.between(today, due))
                    .candidateName(name)
                    .linkUrl("/careers/erm/i9-everify/i9/" + f.getId())
                    .build());
        }

        for (I983Plan p : i983s) {
            LocalDate end = p.getOptEndDate();
            if (end == null) continue;
            if (end.isBefore(today) || end.isAfter(i983Window)) continue;
            String name = candidateName(p.getCandidate());
            deadlines.add(UpcomingDeadline.builder()
                    .label("STEM OPT ending for " + name)
                    .dueDate(end)
                    .daysUntilDue(ChronoUnit.DAYS.between(today, end))
                    .candidateName(name)
                    .linkUrl("/careers/erm/training-plans/" + p.getId())
                    .build());
        }

        deadlines.sort(Comparator.comparing(UpcomingDeadline::getDueDate,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return deadlines.size() > 15 ? deadlines.subList(0, 15) : deadlines;
    }

    // ── Recent actions ──────────────────────────────────────────────────────

    private List<RecentAction> buildRecentActions() {
        List<AuditLog> entries = auditLogRepository.findTop25ByOrderByTimestampDesc();
        // Pre-fetch users to avoid N+1 — cheap for 25 rows.
        Map<UUID, User> userCache = new HashMap<>();
        List<RecentAction> out = new ArrayList<>(entries.size());
        for (AuditLog a : entries) {
            User performer = null;
            if (a.getUserId() != null) {
                performer = userCache.computeIfAbsent(
                        a.getUserId(),
                        id -> userRepository.findById(id).orElse(null));
            }
            String performerName = performer != null ? performer.getFullName() : "System";
            String performerRole = performer != null && performer.getRoles() != null
                    && !performer.getRoles().isEmpty()
                    ? performer.getRoles().iterator().next().name()
                    : null;
            out.add(RecentAction.builder()
                    .timestamp(a.getTimestamp())
                    .summary(summarize(a, performerName))
                    .performedByName(performerName)
                    .performedByRole(performerRole)
                    .entityType(a.getEntityType())
                    .entityLinkUrl(linkFor(a))
                    .build());
        }
        return out;
    }

    private String summarize(AuditLog a, String performerName) {
        String type = a.getEntityType();
        String action = a.getAction();
        if (type == null) return action;
        return switch (type) {
            case "I9Form" -> switch (action) {
                case "CREATE" -> "I-9 form created";
                case "SECTION_1_DRAFT_SAVE" -> "I-9 Section 1 draft saved";
                case "SECTION_1_SUBMIT" -> "I-9 Section 1 submitted by " + performerName;
                case "SECTION_2_DRAFT_SAVE" -> "I-9 Section 2 draft saved";
                case "SECTION_2_SUBMIT" -> "I-9 Section 2 completed by " + performerName;
                case "REOPEN" -> "I-9 reopened by " + performerName;
                default -> "I-9 " + action;
            };
            case "I983Plan" -> switch (action) {
                case "CREATE" -> "I-983 plan created by " + performerName;
                case "UPDATE_FIELDS" -> "I-983 fields updated by " + performerName;
                case "SIGN_EMPLOYER" -> "I-983 signed by employer (" + performerName + ")";
                case "SIGN_STUDENT" -> "I-983 signed by student (" + performerName + ")";
                case "SUBMIT_TO_DSO" -> "I-983 submitted to DSO by " + performerName;
                case "DSO_RESPONSE" -> "I-983 DSO response recorded by " + performerName;
                default -> "I-983 " + action;
            };
            case "Offer" -> switch (action) {
                case "CREATE" -> "Offer letter drafted by " + performerName;
                case "UPDATE" -> "Offer letter updated by " + performerName;
                case "SEND" -> "Offer sent by " + performerName;
                case "ACCEPT" -> "Offer accepted by " + performerName;
                case "DECLINE" -> "Offer declined by " + performerName;
                case "REVOKE" -> "Offer revoked by " + performerName;
                case "EXPIRE" -> "Offer expired";
                case "DELETE" -> "Offer deleted by " + performerName;
                default -> "Offer " + action;
            };
            case "EVerifyCase" -> switch (action) {
                case "CREATE" -> "E-Verify case created";
                case "UPDATE" -> "E-Verify case updated by " + performerName;
                case "STATUS_CHANGE" -> "E-Verify case status changed by " + performerName;
                case "CLOSE" -> "E-Verify case closed by " + performerName;
                default -> "E-Verify " + action;
            };
            case "Application" -> switch (action) {
                case "STATUS_CHANGE" -> "Application status changed by " + performerName;
                default -> "Application " + action;
            };
            case "Interview" -> switch (action) {
                case "SCHEDULE" -> "Interview scheduled by " + performerName;
                case "SUBMIT_FEEDBACK" -> "Interview feedback submitted by " + performerName;
                case "STATUS_CHANGE" -> "Interview status changed by " + performerName;
                case "UPDATE" -> "Interview updated by " + performerName;
                case "DELETE" -> "Interview deleted by " + performerName;
                default -> "Interview " + action;
            };
            case "OnboardingTask" -> switch (action) {
                case "STATUS_CHANGE" -> "Onboarding task updated by " + performerName;
                default -> "Onboarding " + action;
            };
            default -> action + " (" + type + ")";
        };
    }

    private String linkFor(AuditLog a) {
        if (a.getEntityType() == null || a.getEntityId() == null) return null;
        return switch (a.getEntityType()) {
            case "I9Form" -> "/careers/erm/i9-everify/i9/" + a.getEntityId();
            case "I983Plan" -> "/careers/erm/training-plans/" + a.getEntityId();
            case "Offer" -> "/careers/erm/offers/" + a.getEntityId();
            case "EVerifyCase" -> "/careers/erm/i9-everify/everify/" + a.getEntityId();
            case "Interview" -> "/careers/erm/interviews/" + a.getEntityId();
            default -> null;
        };
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static long countI983(List<I983Plan> plans, I983Status status) {
        return plans.stream().filter(p -> p.getStatus() == status).count();
    }

    private static long countEverify(List<EVerifyCase> cases, EVerifyStatus status) {
        return cases.stream().filter(c -> c.getStatus() == status).count();
    }

    private static String candidateName(Candidate c) {
        if (c == null || c.getUser() == null) return "(unnamed candidate)";
        String name = c.getUser().getFullName();
        return name == null || name.isBlank() ? "(unnamed candidate)" : name;
    }

    private static boolean isI9Overdue(I9Form f, LocalDate today) {
        if (f.getStatus() == I9Status.COMPLETED) return false;
        LocalDate due = section2DueDate(f);
        return due != null && due.isBefore(today);
    }

    private static LocalDate section2DueDate(I9Form f) {
        if (f.getFirstDayOfEmployment() == null) return null;
        return I9FormService.plusBusinessDays(
                f.getFirstDayOfEmployment(), SECTION_2_BUSINESS_DAYS);
    }
}
