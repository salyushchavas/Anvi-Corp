package com.anvicorp.api.service;

import com.anvicorp.api.application.ApplicationLifecycle;
import com.anvicorp.api.dto.candidate.CandidateDashboardResponse;
import com.anvicorp.api.dto.candidate.DashboardStatusDTO;
import com.anvicorp.api.notification.NotificationEventType;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.EVerifyCase;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.I983Plan;
import com.anvicorp.api.entity.I9Form;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.OnboardingTask;
import com.anvicorp.api.entity.Resume;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.ApplicationStatus;
import com.anvicorp.api.enums.EVerifyStatus;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.I983Status;
import com.anvicorp.api.enums.I9Status;
import com.anvicorp.api.enums.InterviewStatus;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.enums.OnboardingTaskStatus;
import com.anvicorp.api.enums.WorkAuthTrack;
import com.anvicorp.api.repository.ApplicationRepository;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EVerifyCaseRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.I9FormRepository;
import com.anvicorp.api.repository.I983PlanRepository;
import com.anvicorp.api.repository.InterviewRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.OnboardingTaskRepository;
import com.anvicorp.api.repository.ProjectRepository;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.enums.ProjectStatus;
import com.anvicorp.api.repository.ResumeRepository;
import com.anvicorp.api.repository.ScreeningRepository;
import com.anvicorp.api.repository.SentNotificationRepository;
import com.anvicorp.api.repository.TimesheetRepository;
import com.anvicorp.api.repository.UserRepository;
import com.anvicorp.api.repository.WeeklyReportRepository;
import com.anvicorp.api.entity.Timesheet;
import com.anvicorp.api.entity.WeeklyReport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the aggregate payload for the candidate dashboard. All reads run in
 * a single read-only transaction; every dependency query is fetch-joined so
 * the DTO mappers never touch a lazy proxy. Empty-state is a fully-formed
 * response — never a 404, never a null list.
 *
 * Stage map (0..4, capped at 4):
 *   APPLIED                 -> 0
 *   SHORTLISTED             -> 1
 *   INTERVIEW_SCHEDULED|INTERVIEWED -> 2
 *   OFFERED|ACCEPTED        -> 3   (spec names "OFFER_EXTENDED"/"OFFER_ACCEPTED")
 *   ONBOARDING|ACTIVE|HIRED|COMPLETED -> 4
 * Exit statuses (REJECTED|WITHDRAWN|LAPSED|NO_SHOW) flip isExited=true and
 * stageIndex=-1 — the entity doesn't store the pre-exit stage, so we don't
 * fake one. The frontend renders exited rows with a separate visual treatment.
 */
@Service
@RequiredArgsConstructor
public class CandidateDashboardService {

    private static final int RECENT_ACTIVITY_LIMIT = 5;

    private final CandidateRepository candidateRepository;
    private final ApplicationRepository applicationRepository;
    private final OfferRepository offerRepository;
    private final InterviewRepository interviewRepository;
    private final OnboardingTaskRepository onboardingTaskRepository;
    private final ResumeRepository resumeRepository;
    private final AuditLogRepository auditLogRepository;
    private final ScreeningRepository screeningRepository;
    private final EngagementRepository engagementRepository;
    private final I9FormRepository i9FormRepository;
    private final I983PlanRepository i983PlanRepository;
    private final EVerifyCaseRepository everifyCaseRepository;
    private final UserRepository userRepository;
    // Phase-2 weekly-cycle reads — only used on the ACTIVE-engagement face.
    // WeeklyMaterial + MaterialAcknowledgement repositories removed in
    // Trainer Phase 0 — the concept is not in the Trainer doc spec.
    private final WeeklyReportRepository weeklyReportRepository;
    private final TimesheetRepository timesheetRepository;
    private final ProjectRepository projectRepository;
    private final com.anvicorp.api.repository.EvaluationRepository evaluationRepository;
    private final ComplianceRoutingService complianceRoutingService;
    private final SentNotificationRepository sentNotificationRepository;

    @Transactional(readOnly = true)
    public CandidateDashboardResponse build(User caller) {
        // Defensive: an unauthenticated principal yields a fully-empty shape
        // rather than a 500. @PreAuthorize on the controller already blocks
        // this path; the guard is belt-and-braces.
        if (caller == null || caller.getId() == null) {
            return empty(null);
        }

        UUID userId = caller.getId();
        String candidateName = caller.getFullName();

        // A user with the CANDIDATE role might not yet have a Candidate row
        // (registration lazy-creates on first apply / resume upload). Treat
        // that as "brand-new candidate" and return the empty shape with the
        // "Complete your profile" / "Browse openings" next step.
        Candidate candidate = candidateRepository.findByUserId(userId).orElse(null);
        if (candidate == null) {
            return empty(caller);
        }

        // ── Profile completeness ─────────────────────────────────────────────
        // Phase 1.4 — factors expanded to include intake fields + the neutral
        // self-attestation. dateOfBirth + resume are kept so the existing 0-100
        // semantics don't regress for candidates who already filled them in.
        boolean hasName = caller.getFullName() != null && !caller.getFullName().isBlank();
        boolean hasPhone = caller.getPhoneNumber() != null && !caller.getPhoneNumber().isBlank();
        boolean hasDob = candidate.getDateOfBirth() != null;
        boolean hasResume = candidate.getDefaultResumeId() != null
                || !resumeRepository.findByCandidateId(candidate.getId()).isEmpty();
        boolean hasSkillset = candidate.getSkillset() != null && !candidate.getSkillset().isBlank();
        // Either the freeform education line OR the school/degree pair counts —
        // candidates entering one or the other shouldn't be penalised.
        boolean hasEducation =
                (candidate.getEducation() != null && !candidate.getEducation().isBlank())
                || (candidate.getSchool() != null && !candidate.getSchool().isBlank())
                || (candidate.getDegree() != null && !candidate.getDegree().isBlank());
        // "Attestation answered" = the candidate has expressed a position on
        // the primary authorised-to-work prompt. sponsorshipNeeded/expectedTrack/
        // validityDate are not required to count as answered.
        boolean hasAttestation = candidate.getAuthorizedToWork() != null;
        int profileComplete = percentFilled(
                hasName, hasPhone, hasDob, hasResume,
                hasSkillset, hasEducation, hasAttestation);

        // ── Applications + stage mapping ─────────────────────────────────────
        List<Application> apps = applicationRepository
                .findByCandidateIdWithPosting(candidate.getId());
        List<CandidateDashboardResponse.ApplicationSummary> appSummaries = apps.stream()
                .map(this::toApplicationSummary)
                .toList();

        // ── Offers + upcoming interviews + onboarding (single-fetch each) ────
        List<Offer> offers = offerRepository
                .findByCandidateUserIdWithGraph(userId);
        Instant now = Instant.now();
        List<Interview> upcomingInterviews = interviewRepository
                .findAllForCandidateUser(userId).stream()
                .filter(i -> i.getStatus() == InterviewStatus.SCHEDULED
                        && i.getScheduledAt() != null
                        && !i.getScheduledAt().isBefore(now))
                .sorted(Comparator.comparing(Interview::getScheduledAt))
                .toList();
        List<OnboardingTask> tasks = onboardingTaskRepository
                .findByCandidateIdOrderBySortOrderAsc(candidate.getId());

        // Phase 3 step 10 — the candidate's active engagement, if any. Source
        // of truth for post-offer state on the dashboard; pickNextStep falls
        // back to Application post-offer statuses when engagement is null.
        // For nextStep we ignore BLOCKED/TERMINATED. For the stepper override
        // we want to know about a BLOCKED engagement so the final dot can be
        // rendered with a blocked indicator — collected separately below.
        Engagement engagement = engagementRepository.findByCandidateId(candidate.getId()).stream()
                .filter(e -> e.getStatus() != EngagementStatus.BLOCKED_NO_AUTHORIZATION
                        && e.getStatus() != EngagementStatus.TERMINATED)
                .findFirst()
                .orElse(null);
        Engagement stepperEngagement = engagement != null
                ? engagement
                : engagementRepository.findByCandidateId(candidate.getId()).stream()
                        .filter(e -> e.getStatus() != EngagementStatus.TERMINATED)
                        .findFirst()
                        .orElse(null);

        // ── nextStep priority ladder ─────────────────────────────────────────
        CandidateDashboardResponse.NextStep nextStep = pickNextStep(
                apps, offers, upcomingInterviews, tasks, profileComplete, engagement);

        // ── Upcoming items ────────────────────────────────────────────────────
        List<CandidateDashboardResponse.UpcomingItem> upcoming = assembleUpcoming(
                offers, upcomingInterviews, tasks);

        // ── Recent activity from AuditLog ────────────────────────────────────
        List<CandidateDashboardResponse.ActivityItem> recentActivity =
                assembleRecentActivity(apps, offers);

        CandidateDashboardResponse.EngagementSummary engagementSummary =
                buildEngagementSummary(stepperEngagement, tasks);

        List<CandidateDashboardResponse.ComplianceItem> compliance =
                buildComplianceItems(candidate.getId(), stepperEngagement, offers);

        // SPEC §3 — six-macro-step journey bar payload. Computed AFTER all
        // upstream state has been resolved so the current-stage sub-step list
        // can pull from offers/interviews/tasks without re-fetching.
        CandidateDashboardResponse.Journey journey = buildJourney(
                candidate, apps, offers, upcomingInterviews, tasks,
                stepperEngagement, profileComplete);

        // SPEC §6 — resume info for the 3-up status card row.
        CandidateDashboardResponse.ResumeInfo resumeInfo = buildResumeInfo(candidate);

        // Phase-2 intern face — ONLY when the engagement is ACTIVE. Two effects:
        //   1. We build a weeklyCockpit (this week's material + report +
        //      timesheet + auth snapshot) the frontend uses to render the
        //      intern cockpit instead of the applicant 3-up cards.
        //   2. We swap the 6-stage applicant journey for the 4-stage Phase-2
        //      journey (Setup → Active weeks → Evaluation → Completed). Same
        //      JourneyBar component consumes it — no FE code change.
        //   3. We replace nextStep with an intern-priority chain (acknowledge /
        //      submit report / log timesheet / waiting on supervisor).
        CandidateDashboardResponse.WeeklyCockpit weeklyCockpit = null;
        if (engagement != null && engagement.getStatus() == EngagementStatus.ACTIVE) {
            weeklyCockpit = buildWeeklyCockpit(candidate, engagement);
            journey = buildInternJourney(weeklyCockpit, engagement);
            CandidateDashboardResponse.NextStep internNext = pickInternNextStep(weeklyCockpit);
            if (internNext != null) nextStep = internNext;
        }

        return CandidateDashboardResponse.builder()
                .candidateName(candidateName)
                .profileComplete(profileComplete)
                .nextStep(nextStep)
                .applications(appSummaries)
                .upcoming(upcoming)
                .recentActivity(recentActivity)
                .engagement(engagementSummary)
                .compliance(compliance)
                .journey(journey)
                .resume(resumeInfo)
                .weeklyCockpit(weeklyCockpit)
                .build();
    }

    // ── SPEC §6 — resume status card ─────────────────────────────────────────

    private CandidateDashboardResponse.ResumeInfo buildResumeInfo(Candidate candidate) {
        if (candidate == null) return null;
        // Prefer the candidate's explicitly-set default resume; fall back to
        // most-recent if defaultResumeId is missing (legacy candidates).
        List<Resume> resumes = resumeRepository.findByCandidateId(candidate.getId());
        if (resumes.isEmpty()) return null;
        Resume r = null;
        if (candidate.getDefaultResumeId() != null) {
            for (Resume x : resumes) {
                if (candidate.getDefaultResumeId().equals(x.getId())) { r = x; break; }
            }
        }
        if (r == null) {
            r = resumes.stream()
                    .max(Comparator.comparing(Resume::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(resumes.get(0));
        }
        return CandidateDashboardResponse.ResumeInfo.builder()
                .id(r.getId())
                .fileName(r.getFileName())
                .uploadedAt(r.getCreatedAt())
                .build();
    }

    // ── SPEC §3 + §4 — six-macro-step journey bar ────────────────────────────

    /** Stable keys for the applicant-face macro stages. Order matters. */
    private static final List<String> APPLICANT_STAGE_KEYS = List.of(
            "APPLIED", "SCREENING", "INTERVIEW", "OFFER", "ONBOARDING", "HIRED");
    private static final java.util.Map<String, String> APPLICANT_STAGE_LABELS = java.util.Map.of(
            "APPLIED", "Applied",
            "SCREENING", "Screening",
            "INTERVIEW", "Interview",
            "OFFER", "Offer",
            "ONBOARDING", "Onboarding",
            "HIRED", "Hired");

    /**
     * Walks the candidate's furthest non-exited application + engagement to
     * decide which macro stage is "current". Past stages collapse to done;
     * the current stage carries an expanded sub-step list; future stages stay
     * upcoming. Exit statuses flip {@code isExited=true}.
     */
    private CandidateDashboardResponse.Journey buildJourney(
            Candidate candidate,
            List<Application> apps,
            List<Offer> offers,
            List<Interview> upcomingInterviews,
            List<OnboardingTask> tasks,
            Engagement engagement,
            int profileComplete) {

        // Furthest non-exited application is the anchor; if every app has
        // exited (or none exist), we fall back to "APPLIED" + isExited flag.
        Application anchor = apps.stream()
                .filter(a -> !ApplicationLifecycle.isExited(a.getStatus()))
                .max(Comparator.comparingInt(a -> ApplicationLifecycle.stageIndexOf(a.getStatus())))
                .orElse(null);

        boolean allExited = !apps.isEmpty() && anchor == null;
        String currentKey = resolveCurrentStageKey(anchor, engagement, apps);

        // Build the six stages with state derived from currentKey + engagement.
        int currentIndex = APPLICANT_STAGE_KEYS.indexOf(currentKey);
        if (currentIndex < 0) currentIndex = 0;

        boolean engagementBlocked = engagement != null
                && engagement.getStatus() == EngagementStatus.BLOCKED_NO_AUTHORIZATION;

        List<CandidateDashboardResponse.JourneyStage> stages = new ArrayList<>(6);
        for (int i = 0; i < APPLICANT_STAGE_KEYS.size(); i++) {
            String key = APPLICANT_STAGE_KEYS.get(i);
            String state;
            if (allExited) {
                state = i == 0 ? "blocked" : "upcoming";
            } else if (engagementBlocked && key.equals("ONBOARDING")) {
                state = "blocked";
            } else if (i < currentIndex) {
                state = "done";
            } else if (i == currentIndex) {
                state = "current";
            } else {
                state = "upcoming";
            }
            List<CandidateDashboardResponse.SubStep> subSteps = (i == currentIndex && !allExited)
                    ? buildSubStepsFor(key, candidate, anchor, offers,
                            upcomingInterviews, tasks, engagement, profileComplete)
                    : Collections.emptyList();
            stages.add(CandidateDashboardResponse.JourneyStage.builder()
                    .key(key)
                    .label(APPLICANT_STAGE_LABELS.get(key))
                    .state(state)
                    .subSteps(subSteps)
                    .build());
        }

        return CandidateDashboardResponse.Journey.builder()
                .currentStageKey(allExited ? "EXITED" : currentKey)
                .isExited(allExited)
                .stages(stages)
                .build();
    }

    /**
     * Picks the six-step macro key from the anchor application's status +
     * engagement state. Engagement takes precedence post-offer because the
     * Application status doesn't keep moving past ACCEPTED in the new world
     * (Phase 3 step 10 — Engagement is source of truth for post-offer state).
     */
    private String resolveCurrentStageKey(Application anchor,
                                          Engagement engagement,
                                          List<Application> allApps) {
        if (engagement != null) {
            EngagementStatus es = engagement.getStatus();
            if (es == EngagementStatus.ACTIVE
                    || es == EngagementStatus.COMPLETED
                    || es == EngagementStatus.READY_TO_START) {
                return "HIRED";
            }
            // PENDING_COMPLIANCE / BLOCKED_NO_AUTHORIZATION → still "ONBOARDING"
            // on the applicant-face bar. BLOCKED gets a separate visual via
            // the stage's "blocked" state above.
            return "ONBOARDING";
        }
        if (anchor == null) {
            // No active app at all — treat as pre-Applied.
            return allApps.isEmpty() ? "APPLIED" : "APPLIED";
        }
        ApplicationStatus s = anchor.getStatus();
        return switch (s) {
            case APPLIED -> "APPLIED";
            case SCREENING_SENT, SCREENING_COMPLETED, SHORTLISTED -> "SCREENING";
            case INTERVIEW_SCHEDULED, INTERVIEWED, SELECTED_CONDITIONAL -> "INTERVIEW";
            case OFFERED, ACCEPTED -> "OFFER";
            case ONBOARDING -> "ONBOARDING";
            case HIRED, ACTIVE, COMPLETED -> "HIRED";
            default -> "APPLIED";
        };
    }

    /**
     * Sub-step list for the CURRENT macro stage only. The Onboarding stage
     * delegates per-track compliance items to {@link ComplianceRoutingService}
     * — we don't re-derive what's required, we just translate its missing list
     * + the I-9/I-983/E-Verify rows we already loaded into UI rows.
     */
    private List<CandidateDashboardResponse.SubStep> buildSubStepsFor(
            String key,
            Candidate candidate,
            Application anchor,
            List<Offer> offers,
            List<Interview> upcomingInterviews,
            List<OnboardingTask> tasks,
            Engagement engagement,
            int profileComplete) {

        List<CandidateDashboardResponse.SubStep> out = new ArrayList<>();
        switch (key) {
            case "APPLIED" -> {
                out.add(subStep("PROFILE", "Complete your profile",
                        profileComplete >= 100 ? "done" : "current", "you",
                        "/careers/intern/profile",
                        profileComplete + "% complete"));
                boolean hasResume = candidate != null
                        && (candidate.getDefaultResumeId() != null
                                || !resumeRepository.findByCandidateId(candidate.getId()).isEmpty());
                out.add(subStep("RESUME", "Upload a resume",
                        hasResume ? "done" : "current", "you",
                        "/careers/openings",
                        hasResume ? "On file" : "Required to apply"));
                if (anchor != null) {
                    out.add(subStep("APPLY_SUBMITTED", "Application submitted",
                            "done", "system",
                            "/careers/intern/applications",
                            "Awaiting recruiter review"));
                } else {
                    out.add(subStep("APPLY_SUBMIT", "Apply to an internship",
                            "current", "you",
                            "/careers/openings",
                            "Pick a posting that fits"));
                }
            }
            case "SCREENING" -> {
                ApplicationStatus s = anchor != null ? anchor.getStatus() : null;
                if (s == ApplicationStatus.SCREENING_SENT) {
                    UUID screeningId = anchor != null
                            ? screeningRepository.findByApplicationIdWithGraph(anchor.getId())
                                    .map(x -> x.getId()).orElse(null)
                            : null;
                    out.add(subStep("TAKE_SCREENING", "Take the screening questionnaire",
                            "current", "you",
                            screeningId != null ? "/careers/screening/" + screeningId : null,
                            "Sent by the recruiter"));
                } else if (s == ApplicationStatus.SCREENING_COMPLETED) {
                    out.add(subStep("TAKE_SCREENING", "Screening completed",
                            "done", "you", null, "Submitted"));
                    out.add(subStep("AWAIT_SHORTLIST", "Awaiting recruiter review",
                            "waiting", "recruiter", null,
                            "We're reviewing your submission"));
                } else if (s == ApplicationStatus.SHORTLISTED) {
                    out.add(subStep("SHORTLISTED", "You've been shortlisted",
                            "done", "recruiter", null, "Recruiter will reach out"));
                    out.add(subStep("AWAIT_INTERVIEW", "Interview scheduling",
                            "waiting", "recruiter", null, "Expect a calendar invite"));
                }
            }
            case "INTERVIEW" -> {
                ApplicationStatus s = anchor != null ? anchor.getStatus() : null;
                Interview next = upcomingInterviews.stream().findFirst().orElse(null);
                if (next != null) {
                    out.add(subStep("INTERVIEW_SCHEDULED", "Interview scheduled",
                            "current", "you",
                            "/careers/intern/interviews",
                            next.getScheduledAt() != null
                                    ? "On " + formatDate(next.getScheduledAt())
                                    : "Date TBD"));
                } else if (s == ApplicationStatus.INTERVIEW_SCHEDULED) {
                    out.add(subStep("INTERVIEW_SCHEDULED", "Interview scheduled",
                            "current", "you",
                            "/careers/intern/interviews",
                            "Check your calendar invite"));
                }
                if (s == ApplicationStatus.INTERVIEWED) {
                    out.add(subStep("INTERVIEW_COMPLETED", "Interview completed",
                            "done", "you", null, "Awaiting hiring-team decision"));
                    out.add(subStep("AWAIT_DECISION", "Hiring decision",
                            "waiting", "recruiter", null,
                            "Team is reviewing your scorecard"));
                }
                if (s == ApplicationStatus.SELECTED_CONDITIONAL) {
                    out.add(subStep("INTERVIEW_COMPLETED", "Interview completed",
                            "done", "you", null, null));
                    out.add(subStep("CONDITIONAL_SELECT", "Conditionally selected",
                            "done", "recruiter", null,
                            "Formal offer next from HR"));
                    out.add(subStep("AWAIT_OFFER", "Formal offer",
                            "waiting", "employer", null,
                            "HR is drafting your offer"));
                }
            }
            case "OFFER" -> {
                Offer live = offers.stream()
                        .filter(o -> o.getStatus() == OfferStatus.SENT)
                        .findFirst().orElse(null);
                Offer accepted = offers.stream()
                        .filter(o -> o.getStatus() == OfferStatus.ACCEPTED)
                        .findFirst().orElse(null);
                if (live != null) {
                    out.add(subStep("OFFER_REVIEW", "Review & respond to offer",
                            "current", "you",
                            "/careers/intern/offers/" + live.getId(),
                            live.getExpiresAt() != null
                                    ? "Respond by " + formatDate(live.getExpiresAt())
                                    : "Open the offer"));
                } else if (accepted != null) {
                    out.add(subStep("OFFER_ACCEPTED", "Offer accepted",
                            "done", "you", "/careers/intern/offers/" + accepted.getId(),
                            "Onboarding will start shortly"));
                    out.add(subStep("AWAIT_ONBOARDING_SEED", "Onboarding setup",
                            "waiting", "system", null,
                            "Preparing your checklist"));
                } else {
                    // OFFER macro stage with neither SENT nor ACCEPTED → very
                    // brief transitional moment between recruiter-create and
                    // recruiter-send. Show a single waiting row.
                    out.add(subStep("AWAIT_OFFER_SEND", "Offer in preparation",
                            "waiting", "employer", null,
                            "HR is finalizing the offer letter"));
                }
            }
            case "ONBOARDING" -> {
                // Per-track compliance sub-list from ComplianceRoutingService —
                // we DON'T re-derive what's required, we just translate the
                // I-9 / I-983 / E-Verify / CPT rows we already loaded into
                // sub-step UI rows. Each row keeps the same "who owns this"
                // semantics the compliance panel uses.
                out.addAll(buildOnboardingSubSteps(candidate, engagement, tasks));
            }
            case "HIRED" -> {
                if (engagement != null && engagement.getStatus() == EngagementStatus.ACTIVE) {
                    out.add(subStep("WELCOME", "Welcome aboard",
                            "done", "system", null,
                            "Your internship is active"));
                    // Intern face sub-steps are not enumerated here — the
                    // intern face is a separate build (Phase 2 per spec).
                } else if (engagement != null
                        && engagement.getStatus() == EngagementStatus.COMPLETED) {
                    out.add(subStep("COMPLETED", "Internship completed",
                            "done", "you", null, "Congratulations!"));
                } else {
                    out.add(subStep("AWAIT_START", "Awaiting start date",
                            "waiting", "system", null,
                            "Your supervisor will activate you on day 1"));
                }
            }
            default -> { /* no-op */ }
        }
        return out;
    }

    /**
     * Per-track Onboarding sub-list. Universal items always shown (I-9 §1,
     * §2). Track-specific items added when applicable. Mirrors the gating
     * in {@link ComplianceRoutingService#missingRequirements} so the bar and
     * the gate agree on what's required.
     */
    private List<CandidateDashboardResponse.SubStep> buildOnboardingSubSteps(
            Candidate candidate, Engagement engagement, List<OnboardingTask> tasks) {

        List<CandidateDashboardResponse.SubStep> out = new ArrayList<>();
        UUID candidateId = candidate != null ? candidate.getId() : null;

        // BLOCKED_NO_AUTHORIZATION short-circuit — show a single row, not the
        // full per-track checklist.
        if (engagement != null
                && engagement.getStatus() == EngagementStatus.BLOCKED_NO_AUTHORIZATION) {
            out.add(subStep("HR_AUTH_REVIEW", "Pending work-authorization review",
                    "blocked", "employer", null,
                    "HR/legal is reviewing — no productive-work steps yet"));
            return out;
        }

        WorkAuthTrack track = engagement != null ? engagement.getTrack() : null;
        I9Form i9 = candidateId != null
                ? i9FormRepository.findByCandidateId(candidateId).orElse(null) : null;

        // CPT-specific I-20 verify — surfaced first because it's a precondition
        // for productive work on the CPT track.
        if (track == WorkAuthTrack.CPT) {
            OnboardingTask cptTask = candidateId != null && engagement != null
                    && engagement.getOffer() != null
                    ? onboardingTaskRepository.findByCandidateIdAndTaskKeyAndOfferId(
                            candidateId, "CPT_I20_VERIFY", engagement.getOffer().getId())
                            .orElse(null)
                    : null;
            boolean done = cptTask != null
                    && cptTask.getStatus() == OnboardingTaskStatus.COMPLETED;
            out.add(subStep("CPT_I20_VERIFY", "DSO-authorized CPT on Form I-20",
                    done ? "done" : "current", "employer",
                    null,
                    done ? "Verified by HR" : "HR verifies before start"));
        }

        // I-983 — STEM OPT only.
        if (track == WorkAuthTrack.STEM_OPT) {
            I983Plan latest = candidateId != null
                    ? i983PlanRepository.findByCandidateIdOrderByCreatedAtDesc(candidateId)
                            .stream().findFirst().orElse(null)
                    : null;
            String state;
            String owner;
            String sub;
            if (latest == null) {
                state = "current"; owner = "employer";
                sub = "ERM will draft your training plan";
            } else if (latest.getStatus() == I983Status.DSO_APPROVED) {
                state = "done"; owner = "dso";
                sub = "DSO-approved";
            } else if (latest.getStatus() == I983Status.SUBMITTED_TO_DSO) {
                state = "waiting"; owner = "dso";
                sub = "Submitted — awaiting DSO";
            } else {
                state = "current"; owner = "you";
                sub = "Sign & complete: " + latest.getStatus().name();
            }
            out.add(subStep("I983_PLAN", "Form I-983 Training Plan",
                    state, owner, "/careers/intern/training-plans", sub));
        }

        // I-9 §1 — universal, candidate-owned.
        String s1State;
        String s1Sub;
        if (i9 != null && i9.getSection1SignedAt() != null) {
            s1State = "done"; s1Sub = "Signed " + formatDate(i9.getSection1SignedAt());
        } else if (i9 != null && i9.getStatus() != I9Status.NOT_STARTED) {
            s1State = "current"; s1Sub = "Draft in progress";
        } else {
            s1State = "current"; s1Sub = "Sign by your first day";
        }
        out.add(subStep("I9_SECTION_1", "Form I-9 — Section 1",
                s1State, "you", "/careers/intern/i9", s1Sub));

        // I-9 §2 — universal, employer-owned.
        String s2State;
        String s2Sub;
        if (i9 != null && i9.getStatus() == I9Status.COMPLETED) {
            s2State = "done"; s2Sub = "Verified by HR";
        } else if (i9 != null
                && (i9.getStatus() == I9Status.SECTION_2_PENDING
                        || i9.getStatus() == I9Status.SECTION_1_COMPLETE
                        || i9.getStatus() == I9Status.REOPENED)) {
            s2State = "waiting"; s2Sub = "Awaiting HR verification";
        } else {
            s2State = "upcoming"; s2Sub = "Within 3 business days of your start";
        }
        out.add(subStep("I9_SECTION_2", "Form I-9 — Section 2 (HR)",
                s2State, "employer", null, s2Sub));

        // E-Verify — STEM_OPT by default; non-STEM via operator opt-in (we
        // surface the row whenever a case actually exists for the candidate's
        // I-9 to stay aligned with ComplianceRoutingService).
        EVerifyCase everify = i9 != null
                ? everifyCaseRepository.findByI9FormId(i9.getId()).orElse(null)
                : null;
        boolean everifyShown = track == WorkAuthTrack.STEM_OPT || everify != null;
        if (everifyShown) {
            String state;
            String sub;
            if (everify == null) {
                state = "upcoming";
                sub = "HR opens the case after Form I-9 is complete";
            } else if (everify.getStatus() == EVerifyStatus.EMPLOYMENT_AUTHORIZED) {
                state = "done"; sub = "Employment authorized";
            } else if (everify.getStatus() == EVerifyStatus.CLOSED) {
                state = "done"; sub = "E-Verify complete";
            } else if (everify.getStatus() == EVerifyStatus.FINAL_NONCONFIRMATION) {
                // Terminal but unfavorable — render as terminal so the
                // dashboard doesn't claim HR is still working on it.
                state = "done"; sub = "Final nonconfirmation — contact HR";
            } else if (everify.getStatus() == EVerifyStatus.PENDING_SUBMISSION) {
                state = "waiting"; sub = "Case created — HR will submit shortly";
            } else {
                state = "waiting"; sub = "Status: " + everify.getStatus().name();
            }
            out.add(subStep("EVERIFY", "E-Verify",
                    state, "employer", null, sub));
        }

        // Onboarding task bundle — collapse all not-yet-COMPLETED tasks into
        // one row so the bar doesn't explode for candidates with 9 tasks.
        long total = tasks.size();
        long done = tasks.stream()
                .filter(t -> t.getStatus() == OnboardingTaskStatus.COMPLETED)
                .count();
        if (total > 0) {
            out.add(subStep("ONBOARDING_TASKS",
                    "Onboarding tasks (" + done + " of " + total + ")",
                    done >= total ? "done" : "current", "you",
                    "/careers/intern/documents",
                    done >= total
                            ? "All tasks complete"
                            : "Policy acks, GitHub access, supervisor intro"));
        }

        return out;
    }

    private CandidateDashboardResponse.SubStep subStep(
            String key, String label, String state, String owner,
            String href, String subtitle) {
        return CandidateDashboardResponse.SubStep.builder()
                .key(key)
                .label(label)
                .state(state)
                .owner(owner)
                .href(href)
                .subtitle(subtitle)
                .build();
    }

    /**
     * Maps Engagement.status to the frontend final-stage label/state pair so
     * the stepper agrees with the banner. Returns null when there's no
     * engagement (pre-offer / legacy). Onboarding counts are derived from the
     * already-loaded tasks list to avoid an extra round-trip.
     */
    private CandidateDashboardResponse.EngagementSummary buildEngagementSummary(
            Engagement engagement, List<OnboardingTask> tasks) {
        if (engagement == null) return null;
        EngagementStatus status = engagement.getStatus();
        String label;
        String state;
        switch (status) {
            case PENDING_COMPLIANCE, READY_TO_START -> { label = "Onboarding"; state = "current"; }
            case ACTIVE -> { label = "Active"; state = "current"; }
            case COMPLETED -> { label = "Completed"; state = "completed"; }
            case BLOCKED_NO_AUTHORIZATION -> { label = "Blocked"; state = "blocked"; }
            case TERMINATED -> { label = "Ended"; state = "blocked"; }
            default -> { label = "Hired"; state = "current"; }
        }
        long total = tasks.size();
        long done = tasks.stream()
                .filter(t -> t.getStatus() == OnboardingTaskStatus.COMPLETED)
                .count();
        UUID applicationId = engagement.getApplication() != null
                ? engagement.getApplication().getId()
                : null;
        return CandidateDashboardResponse.EngagementSummary.builder()
                .applicationId(applicationId)
                .status(status != null ? status.name() : null)
                .finalStageLabel(label)
                .finalStageState(state)
                .onboardingTotal(total)
                .onboardingCompleted(done)
                .build();
    }

    // ── Mapping helpers ─────────────────────────────────────────────────────

    private CandidateDashboardResponse.ApplicationSummary toApplicationSummary(Application a) {
        JobPosting jp = a.getJobPosting();
        StaffingEntity ent = jp != null ? jp.getEntity() : null;
        boolean exited = ApplicationLifecycle.isExited(a.getStatus());
        int stage = exited ? -1 : ApplicationLifecycle.stageIndexOf(a.getStatus());
        return CandidateDashboardResponse.ApplicationSummary.builder()
                .id(a.getId())
                .position(jp != null ? jp.getTitle() : null)
                .entityName(ent != null ? ent.getName() : null)
                .status(a.getStatus() != null ? a.getStatus().name() : null)
                .stageIndex(stage)
                .isExited(exited)
                .build();
    }

    private int percentFilled(boolean... flags) {
        if (flags.length == 0) return 0;
        int filled = 0;
        for (boolean b : flags) if (b) filled++;
        return (filled * 100) / flags.length;
    }

    // ── Compliance status panel ──────────────────────────────────────────────

    /**
     * Build the candidate-visible compliance status list. Returns an empty
     * list pre-offer (no engagement and no I-9 row) so the frontend hides the
     * panel entirely. Each item names who acted, when, and what's pending —
     * the candidate should never have to ask "did HR finish my I-9 yet?".
     *
     * Items returned (depending on engagement track + state):
     *   I9_SECTION_1 — always when an I-9 row exists
     *   I9_SECTION_2 — always when an I-9 row exists
     *   EVERIFY      — STEM_OPT, or any track when an E-Verify case exists
     *   I983         — STEM_OPT only
     */
    private List<CandidateDashboardResponse.ComplianceItem> buildComplianceItems(
            UUID candidateId, Engagement engagement, List<Offer> offers) {
        List<CandidateDashboardResponse.ComplianceItem> items = new ArrayList<>();
        if (candidateId == null) return items;

        I9Form i9 = i9FormRepository.findByCandidateId(candidateId).orElse(null);
        boolean hasAcceptedOffer = offers.stream()
                .anyMatch(o -> o.getStatus() == OfferStatus.ACCEPTED);
        // Hide the panel entirely until there's actually compliance work to
        // surface (no accepted offer AND no I-9 row → pre-offer noise).
        if (i9 == null && !hasAcceptedOffer && engagement == null) {
            return items;
        }

        // ── I-9 Section 1 (candidate) ────────────────────────────────────────
        if (i9 != null) {
            String s1State;
            String s1Sub;
            Instant s1At = i9.getSection1SignedAt();
            if (s1At != null) {
                s1State = "COMPLETED";
                String who = i9.getSection1SignedByName() != null
                        ? i9.getSection1SignedByName()
                        : "you";
                s1Sub = "Signed by " + who + " on " + formatDate(s1At);
            } else if (i9.getStatus() == I9Status.NOT_STARTED) {
                s1State = "NOT_STARTED";
                s1Sub = "Not started yet";
            } else {
                s1State = "IN_PROGRESS";
                s1Sub = "Draft saved — finish signing to submit";
            }
            items.add(CandidateDashboardResponse.ComplianceItem.builder()
                    .kind("I9_SECTION_1")
                    .label("Form I-9 — Section 1")
                    .state(s1State)
                    .subtitle(s1Sub)
                    .href("/careers/intern/i9")
                    .completedAt(s1At)
                    .build());

            // ── I-9 Section 2 (HR) ───────────────────────────────────────────
            String s2State;
            String s2Sub;
            Instant s2At = i9.getSection2SignedAt();
            if (i9.getStatus() == I9Status.COMPLETED && s2At != null) {
                s2State = "COMPLETED";
                String hrName = lookupUserName(i9.getSection2SignedByUserId());
                s2Sub = hrName != null
                        ? "Verified by " + hrName + " on " + formatDate(s2At)
                        : "Verified by HR on " + formatDate(s2At);
            } else if (i9.getStatus() == I9Status.SECTION_2_PENDING
                    || i9.getStatus() == I9Status.SECTION_1_COMPLETE) {
                s2State = "AWAITING_HR";
                s2Sub = "Awaiting HR verification";
            } else if (i9.getStatus() == I9Status.REOPENED) {
                s2State = "AWAITING_HR";
                s2Sub = "Reopened — awaiting HR re-verification";
            } else {
                s2State = "NOT_STARTED";
                s2Sub = "Section 1 must be completed first";
            }
            items.add(CandidateDashboardResponse.ComplianceItem.builder()
                    .kind("I9_SECTION_2")
                    .label("Form I-9 — Section 2 (HR)")
                    .state(s2State)
                    .subtitle(s2Sub)
                    .href(null)
                    .completedAt(s2At)
                    .build());
        }

        // ── E-Verify (HR, post-I-9) ──────────────────────────────────────────
        WorkAuthTrack track = engagement != null ? engagement.getTrack() : null;
        EVerifyCase everify = i9 != null
                ? everifyCaseRepository.findByI9FormId(i9.getId()).orElse(null)
                : null;
        boolean everifyRelevant = track == WorkAuthTrack.STEM_OPT || everify != null;
        if (everifyRelevant) {
            String state;
            String sub;
            Instant at = null;
            if (everify == null) {
                state = "NOT_STARTED";
                sub = "HR will open the E-Verify case after Form I-9 is complete";
            } else if (everify.getStatus() == EVerifyStatus.EMPLOYMENT_AUTHORIZED) {
                state = "COMPLETED";
                sub = "Employment authorized";
            } else if (everify.getStatus() == EVerifyStatus.CLOSED) {
                state = "COMPLETED";
                sub = "E-Verify complete";
                at = everify.getClosedAt();
            } else if (everify.getStatus() == EVerifyStatus.FINAL_NONCONFIRMATION) {
                // Terminal but unfavorable — render as terminal so the
                // dashboard doesn't claim HR is still working on it.
                state = "COMPLETED";
                sub = "Final nonconfirmation — contact HR";
                at = everify.getClosedAt();
            } else if (everify.getStatus() == EVerifyStatus.PENDING_SUBMISSION) {
                state = "IN_PROGRESS";
                sub = "Case created — HR will submit shortly";
            } else {
                state = "IN_PROGRESS";
                sub = "Case status: " + everify.getStatus().name();
            }
            items.add(CandidateDashboardResponse.ComplianceItem.builder()
                    .kind("EVERIFY")
                    .label("E-Verify")
                    .state(state)
                    .subtitle(sub)
                    .href(null)
                    .completedAt(at)
                    .build());
        }

        // ── I-983 Training Plan (STEM_OPT only) ──────────────────────────────
        if (track == WorkAuthTrack.STEM_OPT) {
            List<I983Plan> plans = i983PlanRepository
                    .findByCandidateIdOrderByCreatedAtDesc(candidateId);
            I983Plan latest = plans.isEmpty() ? null : plans.get(0);
            String state;
            String sub;
            if (latest == null) {
                state = "NOT_STARTED";
                sub = "ERM will draft your training plan";
            } else if (latest.getStatus() == I983Status.DSO_APPROVED) {
                state = "COMPLETED";
                sub = "DSO-approved";
            } else {
                state = "IN_PROGRESS";
                sub = "Status: " + latest.getStatus().name();
            }
            items.add(CandidateDashboardResponse.ComplianceItem.builder()
                    .kind("I983")
                    .label("Form I-983 Training Plan")
                    .state(state)
                    .subtitle(sub)
                    .href("/careers/intern/training-plans")
                    .completedAt(null)
                    .build());
        }

        return items;
    }

    private String formatDate(Instant at) {
        if (at == null) return "—";
        return at.atZone(java.time.ZoneOffset.UTC)
                .toLocalDate()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"));
    }

    private String lookupUserName(UUID userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(User::getFullName).orElse(null);
    }

    // ── nextStep ─────────────────────────────────────────────────────────────

    private CandidateDashboardResponse.NextStep pickNextStep(
            List<Application> apps,
            List<Offer> offers,
            List<Interview> upcomingInterviews,
            List<OnboardingTask> tasks,
            int profileComplete,
            Engagement engagement) {

        List<Application> activeApps = apps.stream()
                .filter(a -> !ApplicationLifecycle.isExited(a.getStatus()))
                .toList();

        // 1. Live SENT offer — the most time-sensitive thing on the dashboard.
        Offer sent = offers.stream()
                .filter(o -> o.getStatus() == OfferStatus.SENT)
                .findFirst()
                .orElse(null);
        if (sent != null) {
            String position = sent.getApplication() != null
                    && sent.getApplication().getJobPosting() != null
                    ? sent.getApplication().getJobPosting().getTitle()
                    : null;
            String subtitle = sent.getExpiresAt() != null
                    ? "Respond by " + sent.getExpiresAt().toString()
                    : "Review your offer";
            return CandidateDashboardResponse.NextStep.builder()
                    .type("OFFER")
                    .title(position != null
                            ? "You have an offer — " + position
                            : "You have an offer")
                    .subtitle(subtitle)
                    .ctaLabel("View offer")
                    .ctaHref("/careers/intern/offers/" + sent.getId())
                    .build();
        }

        // 2. Screening sent — candidate needs to complete it before the
        //    application can advance. Resolve the screening id so the CTA
        //    deep-links to the take-screening page. If the lookup fails (a
        //    stray SCREENING_SENT with no Screening row, which shouldn't
        //    happen post-1.1b but we don't want a 500) we fall through.
        Application withScreening = activeApps.stream()
                .filter(a -> a.getStatus() == ApplicationStatus.SCREENING_SENT)
                .findFirst()
                .orElse(null);
        if (withScreening != null) {
            UUID screeningId = screeningRepository
                    .findByApplicationIdWithGraph(withScreening.getId())
                    .map(s -> s.getId())
                    .orElse(null);
            if (screeningId != null) {
                String position = withScreening.getJobPosting() != null
                        ? withScreening.getJobPosting().getTitle()
                        : null;
                return CandidateDashboardResponse.NextStep.builder()
                        .type("SCREENING")
                        .title(position != null
                                ? "Complete screening — " + position
                                : "Complete screening")
                        .subtitle("A short questionnaire from the recruiter")
                        .ctaLabel("Take screening")
                        .ctaHref("/careers/screening/" + screeningId)
                        .build();
            }
        }

        // 3. Upcoming interview.
        Interview nextInterview = upcomingInterviews.stream().findFirst().orElse(null);
        if (nextInterview != null) {
            String position = nextInterview.getApplication() != null
                    && nextInterview.getApplication().getJobPosting() != null
                    ? nextInterview.getApplication().getJobPosting().getTitle()
                    : null;
            return CandidateDashboardResponse.NextStep.builder()
                    .type("INTERVIEW")
                    .title(position != null
                            ? "Interview scheduled — " + position
                            : "Interview scheduled")
                    .subtitle(nextInterview.getScheduledAt() != null
                            ? nextInterview.getScheduledAt().toString()
                            : null)
                    .ctaLabel("View details")
                    .ctaHref("/careers/intern/interviews")
                    .build();
        }

        // 3. Onboarding incomplete after acceptance/hire.
        // Phase 3 step 10 — engagement.status is the new source of truth.
        // Fallback to Application post-offer statuses for legacy candidates
        // that don't have an engagement yet (step-11 backfill cleans this up).
        boolean hasAcceptedOrHired = engagement != null
                ? (engagement.getStatus() == EngagementStatus.PENDING_COMPLIANCE
                        || engagement.getStatus() == EngagementStatus.READY_TO_START
                        || engagement.getStatus() == EngagementStatus.ACTIVE)
                : activeApps.stream().anyMatch(a -> a.getStatus() == ApplicationStatus.ACCEPTED
                        || a.getStatus() == ApplicationStatus.HIRED
                        || a.getStatus() == ApplicationStatus.ONBOARDING);
        long totalTasks = tasks.size();
        long doneTasks = tasks.stream()
                .filter(t -> t.getStatus() == OnboardingTaskStatus.COMPLETED)
                .count();
        if (hasAcceptedOrHired && totalTasks > 0 && doneTasks < totalTasks) {
            return CandidateDashboardResponse.NextStep.builder()
                    .type("ONBOARDING")
                    .title("Complete onboarding (" + doneTasks + " of " + totalTasks + ")")
                    .subtitle(null)
                    .ctaLabel("Continue onboarding")
                    .ctaHref("/careers/intern/documents")
                    .build();
        }

        // 4. ACTIVE intern — weekly work submission. Engagement-first; legacy
        // fallback for candidates pre-Phase-3 step-3.
        boolean isActiveIntern = engagement != null
                ? engagement.getStatus() == EngagementStatus.ACTIVE
                : activeApps.stream().anyMatch(a -> a.getStatus() == ApplicationStatus.HIRED
                        || a.getStatus() == ApplicationStatus.ACTIVE);
        if (isActiveIntern) {
            return CandidateDashboardResponse.NextStep.builder()
                    .type("WORK")
                    .title("Submit this week's work")
                    .subtitle("Log hours and submit assignments")
                    .ctaLabel("Open My Work")
                    .ctaHref("/careers/intern/work")
                    .build();
        }

        // SPEC §5 — engagement-driven waiting / transition branches.
        if (engagement != null) {
            EngagementStatus es = engagement.getStatus();
            if (es == EngagementStatus.BLOCKED_NO_AUTHORIZATION) {
                return CandidateDashboardResponse.NextStep.builder()
                        .type("EXITED")
                        .title("Pending work-authorization review")
                        .subtitle("HR/legal is reviewing — no productive-work steps yet.")
                        .ctaLabel(null)
                        .ctaHref(null)
                        .isWaiting(true)
                        .waitingFor("HR/legal review of your work authorization")
                        .build();
            }
            if (es == EngagementStatus.COMPLETED) {
                return CandidateDashboardResponse.NextStep.builder()
                        .type("WELCOME")
                        .title("Internship complete — congratulations!")
                        .subtitle("Your final record is stored. Check your evaluations for feedback.")
                        .ctaLabel("View evaluations")
                        .ctaHref("/careers/intern/work")
                        .isWaiting(false)
                        .build();
            }
            if (es == EngagementStatus.READY_TO_START) {
                return CandidateDashboardResponse.NextStep.builder()
                        .type("AWAITING_READY")
                        .title("All set — awaiting your start date")
                        .subtitle(engagement.getPlannedStartDate() != null
                                ? "Planned start: " + engagement.getPlannedStartDate()
                                : "Your supervisor will activate you on day 1")
                        .ctaLabel(null)
                        .ctaHref(null)
                        .isWaiting(true)
                        .waitingFor("Your start date / supervisor activation")
                        .build();
            }
            if (es == EngagementStatus.PENDING_COMPLIANCE) {
                // When the candidate has finished their side and HR has
                // signed off every compliance item, the engagement still
                // sits in PENDING_COMPLIANCE until HR clicks "Activate
                // Engagement" on the operations queue (POST /mark-ready).
                // Render an accurate "awaiting activation" hero rather
                // than the misleading "HR is finalizing…" copy.
                boolean complianceDone = false;
                try {
                    complianceDone = complianceRoutingService.requirementsSatisfied(engagement);
                } catch (Exception ignored) {
                    // Defensive — never crash the dashboard on a check failure.
                }
                if (complianceDone) {
                    return CandidateDashboardResponse.NextStep.builder()
                            .type("AWAITING_HR_ACTIVATION")
                            .title("Onboarding complete")
                            .subtitle("Awaiting HR activation — typically same-day.")
                            .ctaLabel("View onboarding")
                            .ctaHref("/careers/intern/documents")
                            .isWaiting(true)
                            .waitingFor("HR activation")
                            .build();
                }
                // Compliance items pending after candidate has done their part.
                // The Onboarding sub-step list above already shows what — here
                // we just frame the hero as "waiting on HR" so the candidate
                // knows there's nothing for them to click.
                return CandidateDashboardResponse.NextStep.builder()
                        .type("AWAITING_HR_I9")
                        .title("Onboarding compliance in progress")
                        .subtitle("HR is finalizing your I-9 / E-Verify steps.")
                        .ctaLabel("View onboarding")
                        .ctaHref("/careers/intern/documents")
                        .isWaiting(true)
                        .waitingFor("HR completing post-offer compliance")
                        .build();
            }
        }

        // 5. Furthest non-exited application below the offer/interview tier.
        Application furthest = activeApps.stream()
                .max(Comparator.comparingInt(a -> ApplicationLifecycle.stageIndexOf(a.getStatus())))
                .orElse(null);
        if (furthest != null) {
            String position = furthest.getJobPosting() != null
                    ? furthest.getJobPosting().getTitle()
                    : null;
            ApplicationStatus status = furthest.getStatus();
            // SPEC §5 — waiting states. Each branch below is "someone else's
            // turn" — we set isWaiting=true so the hero renders the calm
            // waiting variant (info border, no primary CTA), and name the
            // party we're waiting on so the candidate never feels stuck.
            if (status == ApplicationStatus.SELECTED_CONDITIONAL) {
                return CandidateDashboardResponse.NextStep.builder()
                        .type("SELECTED_CONDITIONAL")
                        .title(position != null
                                ? "Conditionally selected — " + position
                                : "Conditionally selected")
                        .subtitle("Offer pending. HR will send the formal offer next.")
                        .ctaLabel("View application")
                        .ctaHref("/careers/intern/applications")
                        .isWaiting(true)
                        .waitingFor("HR drafting your formal offer")
                        .build();
            }
            if (status == ApplicationStatus.INTERVIEWED) {
                return CandidateDashboardResponse.NextStep.builder()
                        .type("AWAITING_DECISION")
                        .title(position != null
                                ? "Interview complete — " + position
                                : "Interview complete")
                        .subtitle("We're reviewing your interview — expect to hear back soon.")
                        .ctaLabel("View application")
                        .ctaHref("/careers/intern/applications")
                        .isWaiting(true)
                        .waitingFor("Hiring team reviewing your scorecard")
                        .build();
            }
            if (status == ApplicationStatus.SHORTLISTED) {
                return CandidateDashboardResponse.NextStep.builder()
                        .type("SHORTLISTED")
                        .title(position != null
                                ? "You've been shortlisted — " + position
                                : "You've been shortlisted")
                        .subtitle("A recruiter will reach out with next steps")
                        .ctaLabel("View application")
                        .ctaHref("/careers/intern/applications")
                        .isWaiting(true)
                        .waitingFor("Recruiter scheduling your interview")
                        .build();
            }
            if (status == ApplicationStatus.SCREENING_COMPLETED) {
                return CandidateDashboardResponse.NextStep.builder()
                        .type("AWAITING_SCREENING")
                        .title(position != null
                                ? "Screening submitted — " + position
                                : "Screening submitted")
                        .subtitle("Recruiter is reviewing your answers.")
                        .ctaLabel("View application")
                        .ctaHref("/careers/intern/applications")
                        .isWaiting(true)
                        .waitingFor("Recruiter reviewing your screening")
                        .build();
            }
            if (status == ApplicationStatus.APPLIED) {
                return CandidateDashboardResponse.NextStep.builder()
                        .type("APPLIED")
                        .title("Your application is under review")
                        .subtitle(position)
                        .ctaLabel(null)
                        .ctaHref(null)
                        .isWaiting(true)
                        .waitingFor("Recruiter reviewing your application")
                        .build();
            }
        }

        // 6. No applications at all — push profile or browse.
        if (apps.isEmpty()) {
            if (profileComplete < 100) {
                return CandidateDashboardResponse.NextStep.builder()
                        .type("PROFILE")
                        .title("Complete your profile")
                        .subtitle(profileComplete + "% complete")
                        .ctaLabel("Edit profile")
                        .ctaHref("/careers/intern/profile")
                        .build();
            }
            return CandidateDashboardResponse.NextStep.builder()
                    .type("BROWSE")
                    .title("Browse open internships")
                    .subtitle("Find a role that matches your skills")
                    .ctaLabel("View openings")
                    .ctaHref("/careers/openings")
                    .build();
        }

        // SPEC §5 — never null. All apps exited → encourage re-application.
        return CandidateDashboardResponse.NextStep.builder()
                .type("EXITED")
                .title("Find your next opportunity")
                .subtitle("Your previous applications have closed. Browse open internships.")
                .ctaLabel("Browse openings")
                .ctaHref("/careers/openings")
                .isWaiting(false)
                .build();
    }

    // ── Upcoming items ──────────────────────────────────────────────────────

    private List<CandidateDashboardResponse.UpcomingItem> assembleUpcoming(
            List<Offer> offers,
            List<Interview> upcomingInterviews,
            List<OnboardingTask> tasks) {

        List<CandidateDashboardResponse.UpcomingItem> out = new ArrayList<>();

        for (Interview i : upcomingInterviews) {
            JobPosting jp = i.getApplication() != null ? i.getApplication().getJobPosting() : null;
            out.add(CandidateDashboardResponse.UpcomingItem.builder()
                    .type("INTERVIEW")
                    .title("Interview" + (jp != null ? " — " + jp.getTitle() : ""))
                    .subtitle(i.getInterviewer() != null
                            ? "With " + i.getInterviewer().getFullName()
                            : null)
                    .at(i.getScheduledAt())
                    .build());
        }

        for (Offer o : offers) {
            if (o.getStatus() == OfferStatus.SENT && o.getExpiresAt() != null) {
                JobPosting jp = o.getApplication() != null ? o.getApplication().getJobPosting() : null;
                out.add(CandidateDashboardResponse.UpcomingItem.builder()
                        .type("OFFER_EXPIRY")
                        .title("Offer expires" + (jp != null ? " — " + jp.getTitle() : ""))
                        .subtitle("Respond before the deadline")
                        .at(o.getExpiresAt())
                        .build());
            }
        }

        for (OnboardingTask t : tasks) {
            boolean incomplete = t.getStatus() != OnboardingTaskStatus.COMPLETED
                    && t.getStatus() != OnboardingTaskStatus.NOT_APPLICABLE;
            if (incomplete && t.getDueDate() != null) {
                Instant at = t.getDueDate()
                        .atStartOfDay(java.time.ZoneOffset.UTC)
                        .toInstant();
                out.add(CandidateDashboardResponse.UpcomingItem.builder()
                        .type("ONBOARDING_DUE")
                        .title(t.getTitle())
                        .subtitle("Onboarding task")
                        .at(at)
                        .build());
            }
        }

        out.sort(Comparator.comparing(
                CandidateDashboardResponse.UpcomingItem::getAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    // ── Recent activity ─────────────────────────────────────────────────────

    private List<CandidateDashboardResponse.ActivityItem> assembleRecentActivity(
            List<Application> apps, List<Offer> offers) {

        Set<UUID> appIds = apps.stream().map(Application::getId).collect(Collectors.toCollection(HashSet::new));
        Set<UUID> offerIds = offers.stream().map(Offer::getId).collect(Collectors.toCollection(HashSet::new));

        List<AuditLog> rows = new ArrayList<>();
        rows.addAll(safeFetch("Application", appIds));
        rows.addAll(safeFetch("Offer", offerIds));

        rows.sort(Comparator.comparing(AuditLog::getTimestamp,
                Comparator.nullsLast(Comparator.reverseOrder())));

        return rows.stream()
                .limit(RECENT_ACTIVITY_LIMIT)
                .map(this::toActivityItem)
                .collect(Collectors.toList());
    }

    private List<AuditLog> safeFetch(String entityType, Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        return auditLogRepository.findRecentForEntityIds(
                entityType, ids, PageRequest.of(0, RECENT_ACTIVITY_LIMIT));
    }

    private CandidateDashboardResponse.ActivityItem toActivityItem(AuditLog a) {
        String text = switch (a.getAction()) {
            case "STATUS_CHANGE" -> a.getEntityType() + " status updated";
            case "SHORTLIST" -> "Application shortlisted";
            case "REJECT" -> "Application rejected";
            case "CREATE" -> "Offer extended";
            case "SEND" -> "Offer sent";
            case "ACCEPT" -> "Offer accepted";
            case "DECLINE" -> "Offer declined";
            case "REVOKE" -> "Offer revoked";
            case "EXPIRE" -> "Offer expired";
            default -> a.getEntityType() + " " + a.getAction().toLowerCase();
        };
        return CandidateDashboardResponse.ActivityItem.builder()
                .text(text)
                .at(a.getTimestamp())
                .build();
    }

    // ── Empty-state ─────────────────────────────────────────────────────────

    private CandidateDashboardResponse empty(User caller) {
        // No Candidate row yet — either brand-new account or registration lazy-
        // create hasn't fired. Build profileComplete from just the User fields
        // we do have so the % isn't always 0.
        int profileComplete = 0;
        String name = null;
        if (caller != null) {
            name = caller.getFullName();
            boolean hasName = name != null && !name.isBlank();
            boolean hasPhone = caller.getPhoneNumber() != null && !caller.getPhoneNumber().isBlank();
            profileComplete = percentFilled(hasName, hasPhone, false, false);
        }

        CandidateDashboardResponse.NextStep next = profileComplete < 100
                ? CandidateDashboardResponse.NextStep.builder()
                        .type("PROFILE")
                        .title("Complete your profile")
                        .subtitle(profileComplete + "% complete")
                        .ctaLabel("Edit profile")
                        .ctaHref("/careers/intern/profile")
                        .build()
                : CandidateDashboardResponse.NextStep.builder()
                        .type("BROWSE")
                        .title("Browse open internships")
                        .subtitle("Find a role that matches your skills")
                        .ctaLabel("View openings")
                        .ctaHref("/careers/openings")
                        .build();

        return CandidateDashboardResponse.builder()
                .candidateName(name)
                .profileComplete(profileComplete)
                .nextStep(next)
                .applications(Collections.emptyList())
                .upcoming(Collections.emptyList())
                .recentActivity(Collections.emptyList())
                .build();
    }

    // ── Phase-2 intern face ─────────────────────────────────────────────────

    /**
     * "This week's Monday" using the server clock. Stays in UTC since
     * Timesheet.weekStart + WeeklyReport.weekStart are LocalDate (no TZ).
     */
    private static LocalDate currentWeekStart() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int back = today.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
        if (back < 0) back += 7;
        return today.minusDays(back);
    }

    /**
     * Build the active-intern's weekly cockpit. Reads the most-recent visible
     * material + this week's report + this week's timesheet + the auth-expiry
     * snapshot. Each sub-row is independently nullable — the frontend renders
     * "log this" / "create that" prompts when fields are absent.
     */
    private CandidateDashboardResponse.WeeklyCockpit buildWeeklyCockpit(
            Candidate candidate, Engagement engagement) {
        if (candidate == null || engagement == null) return null;
        UUID candidateId = candidate.getId();
        LocalDate weekStart = currentWeekStart();

        // Material lookup removed in Trainer Phase 0 — the WeeklyMaterial
        // concept is not in the Trainer doc spec. The MaterialCard slot in
        // the dashboard response stays null; the Trainer-doc's
        // Files / Templates feature lands via a different DTO in a later
        // phase (no intern-facing read needed).
        CandidateDashboardResponse.MaterialCard materialCard = null;

        // Report — exact match on (intern_id, week_start). Null when the
        // intern hasn't started a report for this week.
        WeeklyReport report = weeklyReportRepository
                .findByInternIdAndWeekStart(candidateId, weekStart)
                .orElse(null);
        CandidateDashboardResponse.ReportCard reportCard =
                CandidateDashboardResponse.ReportCard.builder()
                        .id(report != null ? report.getId() : null)
                        .weekStart(weekStart)
                        .status(report != null && report.getStatus() != null
                                ? report.getStatus().name() : null)
                        .submittedAt(report != null ? report.getSubmittedAt() : null)
                        .reviewedAt(report != null ? report.getReviewedAt() : null)
                        .reviewNotes(report != null ? report.getReviewNotes() : null)
                        .href("/careers/intern/weekly-reports")
                        .build();

        // Timesheet — newest first list; pick the one whose weekStart matches
        // current Monday. Repo doesn't have an exact-week query and adding one
        // would be scope creep — the lists are small.
        Timesheet weekSheet = timesheetRepository.findForIntern(candidateId).stream()
                .filter(t -> weekStart.equals(t.getWeekStart()))
                .findFirst()
                .orElse(null);
        CandidateDashboardResponse.TimesheetCard timesheetCard =
                CandidateDashboardResponse.TimesheetCard.builder()
                        .id(weekSheet != null ? weekSheet.getId() : null)
                        .weekStart(weekStart)
                        .status(weekSheet != null && weekSheet.getStatus() != null
                                ? weekSheet.getStatus().name() : null)
                        .hours(weekSheet != null ? weekSheet.getHours() : null)
                        .href("/careers/intern/work")
                        .build();

        // Authorization snapshot — earliest of workAuthExpirationDate / optEndDate.
        CandidateDashboardResponse.AuthorizationInfo auth = buildAuthorizationInfo(candidateId);

        // Active project — picks the most-actionable row for the intern: RETURNED
        // first (their turn to fix), then IN_PROGRESS, then NOT_STARTED. SUBMITTED
        // surfaces too (so they know it's awaiting review). COMPLETED falls off.
        CandidateDashboardResponse.ProjectCard projectCard = buildProjectCard(candidateId);

        // Evaluation card — prefer a pending I-983 self-review (action!), else
        // fall back to the most-recent FINALIZED evaluation (celebrate / read).
        CandidateDashboardResponse.EvaluationCard evaluationCard =
                buildEvaluationCard(candidate);

        return CandidateDashboardResponse.WeeklyCockpit.builder()
                .weekStart(weekStart)
                .material(materialCard)
                .report(reportCard)
                .timesheet(timesheetCard)
                .authorization(auth)
                .project(projectCard)
                .latestEvaluation(evaluationCard)
                .build();
    }

    private CandidateDashboardResponse.EvaluationCard buildEvaluationCard(Candidate candidate) {
        if (candidate == null || candidate.getUser() == null) return null;
        UUID userId = candidate.getUser().getId();
        // 1. Action-first: pending I-983 self-review on a DRAFT.
        java.util.List<com.anvicorp.api.entity.Evaluation> drafts = evaluationRepository
                .findSelfReviewableDraftsByCandidateUserIdWithGraph(userId);
        if (!drafts.isEmpty()) {
            com.anvicorp.api.entity.Evaluation d = drafts.get(0);
            return CandidateDashboardResponse.EvaluationCard.builder()
                    .id(d.getId())
                    .type(d.getType() != null ? d.getType().name() : null)
                    .status(d.getStatus() != null ? d.getStatus().name() : null)
                    .selfReviewPending(true)
                    .href("/careers/intern/evaluations")
                    .build();
        }
        // 2. Read: most-recent FINALIZED (the repo orders by createdAt desc).
        java.util.List<com.anvicorp.api.entity.Evaluation> finalized = evaluationRepository
                .findFinalizedByCandidateUserIdWithGraph(userId,
                        com.anvicorp.api.enums.EvaluationStatus.FINALIZED);
        if (finalized.isEmpty()) return null;
        com.anvicorp.api.entity.Evaluation top = finalized.get(0);
        return CandidateDashboardResponse.EvaluationCard.builder()
                .id(top.getId())
                .type(top.getType() != null ? top.getType().name() : null)
                .status(top.getStatus() != null ? top.getStatus().name() : null)
                .overallRating(top.getOverallRating())
                .finalizedAt(top.getFinalizedAt())
                .selfReviewPending(false)
                .href("/careers/intern/evaluations")
                .build();
    }

    private static final java.util.List<ProjectStatus> PROJECT_PRIORITY = java.util.List.of(
            ProjectStatus.RETURNED,
            ProjectStatus.IN_PROGRESS,
            ProjectStatus.NOT_STARTED,
            ProjectStatus.SUBMITTED);

    private CandidateDashboardResponse.ProjectCard buildProjectCard(UUID candidateId) {
        java.util.List<Project> projects = projectRepository.findByInternIdWithGraph(candidateId);
        if (projects.isEmpty()) return null;
        // Pick the highest-priority active row; ties broken by newest createdAt.
        Project chosen = null;
        int chosenRank = Integer.MAX_VALUE;
        for (Project p : projects) {
            int rank = PROJECT_PRIORITY.indexOf(p.getStatus());
            if (rank < 0) continue; // COMPLETED — skip
            if (rank < chosenRank
                    || (rank == chosenRank && chosen != null
                            && p.getCreatedAt() != null
                            && (chosen.getCreatedAt() == null
                                    || p.getCreatedAt().isAfter(chosen.getCreatedAt())))) {
                chosen = p;
                chosenRank = rank;
            }
        }
        if (chosen == null) return null;
        return CandidateDashboardResponse.ProjectCard.builder()
                .id(chosen.getId())
                .title(chosen.getTitle())
                .status(chosen.getStatus() != null ? chosen.getStatus().name() : null)
                .dueDate(chosen.getDueDate())
                .progressPct(chosen.getProgressPct())
                .reviewNotes(chosen.getStatus() == ProjectStatus.RETURNED
                        ? chosen.getReviewNotes() : null)
                .href("/careers/intern/projects")
                .build();
    }

    /**
     * Pull the soonest work-auth expiry the intern has on file. Compares I9's
     * work_auth_expiration_date with I-983's opt_end_date and surfaces whichever
     * fires first. Returns null when neither row exists or both dates are null.
     */
    private CandidateDashboardResponse.AuthorizationInfo buildAuthorizationInfo(UUID candidateId) {
        if (candidateId == null) return null;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        I9Form i9 = i9FormRepository.findByCandidateId(candidateId).orElse(null);
        LocalDate i9Exp = i9 != null ? i9.getWorkAuthExpirationDate() : null;

        LocalDate optEnd = i983PlanRepository
                .findByCandidateIdOrderByCreatedAtDesc(candidateId)
                .stream()
                .findFirst()
                .map(I983Plan::getOptEndDate)
                .orElse(null);

        LocalDate winner;
        String authType;
        if (i9Exp == null && optEnd == null) return null;
        if (i9Exp == null) {
            winner = optEnd;
            authType = "STEM OPT";
        } else if (optEnd == null) {
            winner = i9Exp;
            authType = "Work authorization";
        } else if (optEnd.isBefore(i9Exp)) {
            winner = optEnd;
            authType = "STEM OPT";
        } else {
            winner = i9Exp;
            authType = "Work authorization";
        }

        return CandidateDashboardResponse.AuthorizationInfo.builder()
                .expirationDate(winner)
                .daysUntilExpiry((int) ChronoUnit.DAYS.between(today, winner))
                .authType(authType)
                .build();
    }

    /** Stable keys + labels for the Phase-2 intern journey. */
    private static final List<String> INTERN_STAGE_KEYS = List.of(
            "SETUP", "ACTIVE_WEEKS", "EVALUATION", "COMPLETED");
    private static final Map<String, String> INTERN_STAGE_LABELS = Map.of(
            "SETUP", "Setup",
            "ACTIVE_WEEKS", "Active weeks",
            "EVALUATION", "Evaluation",
            "COMPLETED", "Completed");

    /**
     * Phase-2 intern journey — 4 stages. When the engagement is ACTIVE we're
     * always on the "Active weeks" stage; Setup is done, Evaluation + Completed
     * are upcoming. The current stage's sub-steps reflect this week's cockpit
     * (material ack / report status / timesheet status).
     */
    private CandidateDashboardResponse.Journey buildInternJourney(
            CandidateDashboardResponse.WeeklyCockpit cockpit, Engagement engagement) {
        if (engagement == null) return null;
        EngagementStatus es = engagement.getStatus();

        // Resolve current stage from engagement status. ACTIVE is the only
        // state that triggers the intern face today; the other branches are
        // belt-and-braces for future expansion.
        String currentKey;
        if (es == EngagementStatus.COMPLETED) currentKey = "COMPLETED";
        else if (es == EngagementStatus.ACTIVE) currentKey = "ACTIVE_WEEKS";
        else currentKey = "SETUP";

        int currentIndex = INTERN_STAGE_KEYS.indexOf(currentKey);
        if (currentIndex < 0) currentIndex = 1;

        List<CandidateDashboardResponse.JourneyStage> stages = new ArrayList<>(INTERN_STAGE_KEYS.size());
        for (int i = 0; i < INTERN_STAGE_KEYS.size(); i++) {
            String key = INTERN_STAGE_KEYS.get(i);
            String state;
            if (i < currentIndex) state = "done";
            else if (i == currentIndex) state = "current";
            else state = "upcoming";

            List<CandidateDashboardResponse.SubStep> subSteps =
                    (i == currentIndex && key.equals("ACTIVE_WEEKS") && cockpit != null)
                            ? buildActiveWeekSubSteps(cockpit)
                            : Collections.emptyList();

            stages.add(CandidateDashboardResponse.JourneyStage.builder()
                    .key(key)
                    .label(INTERN_STAGE_LABELS.get(key))
                    .state(state)
                    .subSteps(subSteps)
                    .build());
        }

        return CandidateDashboardResponse.Journey.builder()
                .currentStageKey(currentKey)
                .isExited(false)
                .stages(stages)
                .build();
    }

    /**
     * Sub-step rows under "Active weeks" — material ack, weekly report,
     * timesheet. Each row maps a current-state field to a JourneyBar
     * state + owner + href + subtitle. Read by the same JourneyBar component
     * the applicant face uses.
     */
    private List<CandidateDashboardResponse.SubStep> buildActiveWeekSubSteps(
            CandidateDashboardResponse.WeeklyCockpit cockpit) {
        List<CandidateDashboardResponse.SubStep> out = new ArrayList<>(3);

        CandidateDashboardResponse.MaterialCard mat = cockpit.getMaterial();
        if (mat == null) {
            out.add(subStep("WEEKLY_MATERIAL", "Weekly material",
                    "upcoming", "supervisor", null,
                    "Supervisor hasn't released a material yet"));
        } else if (mat.isAcknowledged()) {
            out.add(subStep("WEEKLY_MATERIAL",
                    "Acknowledged: " + mat.getTitle(),
                    "done", "you", mat.getHref(),
                    mat.getAcknowledgedAt() != null
                            ? "Acknowledged on " + formatDate(mat.getAcknowledgedAt())
                            : "Acknowledged"));
        } else {
            out.add(subStep("WEEKLY_MATERIAL",
                    "Acknowledge: " + mat.getTitle(),
                    "current", "you", mat.getHref(),
                    "Read and mark as reviewed"));
        }

        CandidateDashboardResponse.ReportCard rep = cockpit.getReport();
        if (rep == null || rep.getStatus() == null) {
            out.add(subStep("WEEKLY_REPORT", "Submit this week's report",
                    "current", "you", "/careers/intern/weekly-reports",
                    "Completed work, blockers, learnings, next plan"));
        } else {
            switch (rep.getStatus()) {
                case "DRAFT" -> out.add(subStep("WEEKLY_REPORT",
                        "Finish & submit this week's report",
                        "current", "you", "/careers/intern/weekly-reports",
                        "Draft saved — submit when ready"));
                case "RETURNED" -> out.add(subStep("WEEKLY_REPORT",
                        "Reviewer asked for changes",
                        "current", "you", "/careers/intern/weekly-reports",
                        rep.getReviewNotes() != null && !rep.getReviewNotes().isBlank()
                                ? rep.getReviewNotes()
                                : "Update and resubmit"));
                case "SUBMITTED" -> out.add(subStep("WEEKLY_REPORT",
                        "Report submitted",
                        "waiting", "supervisor", "/careers/intern/weekly-reports",
                        "Awaiting supervisor review"));
                case "APPROVED" -> out.add(subStep("WEEKLY_REPORT",
                        "Report approved",
                        "done", "supervisor", "/careers/intern/weekly-reports",
                        rep.getReviewedAt() != null
                                ? "Approved on " + formatDate(rep.getReviewedAt())
                                : "Approved"));
                default -> out.add(subStep("WEEKLY_REPORT",
                        "Weekly report",
                        "current", "you", "/careers/intern/weekly-reports",
                        rep.getStatus()));
            }
        }

        CandidateDashboardResponse.TimesheetCard sheet = cockpit.getTimesheet();
        if (sheet == null || sheet.getStatus() == null) {
            out.add(subStep("WEEKLY_TIMESHEET", "Log this week's hours",
                    "current", "you", "/careers/intern/work",
                    "Track hours alongside your assignments"));
        } else {
            switch (sheet.getStatus()) {
                case "DRAFT" -> out.add(subStep("WEEKLY_TIMESHEET",
                        "Finish & submit timesheet",
                        "current", "you", "/careers/intern/work",
                        sheet.getHours() != null
                                ? sheet.getHours() + " hrs logged so far"
                                : "Draft saved"));
                case "REJECTED" -> out.add(subStep("WEEKLY_TIMESHEET",
                        "Timesheet returned",
                        "current", "you", "/careers/intern/work",
                        "Reviewer asked for changes — update and resubmit"));
                case "SUBMITTED" -> out.add(subStep("WEEKLY_TIMESHEET",
                        "Timesheet submitted",
                        "waiting", "supervisor", "/careers/intern/work",
                        sheet.getHours() != null
                                ? sheet.getHours() + " hrs — awaiting approval"
                                : "Awaiting approval"));
                case "APPROVED" -> out.add(subStep("WEEKLY_TIMESHEET",
                        "Timesheet approved",
                        "done", "supervisor", "/careers/intern/work",
                        sheet.getHours() != null
                                ? sheet.getHours() + " hrs approved"
                                : "Approved"));
                default -> out.add(subStep("WEEKLY_TIMESHEET",
                        "Timesheet",
                        "current", "you", "/careers/intern/work",
                        sheet.getStatus()));
            }
        }

        return out;
    }

    /**
     * Intern-priority next-step picker. Returns the single most-actionable
     * thing for the active intern, or a waiting state when supervisor's turn,
     * or "all set" when the week is complete. Mirrors the never-null contract
     * the applicant face uses.
     */
    private CandidateDashboardResponse.NextStep pickInternNextStep(
            CandidateDashboardResponse.WeeklyCockpit cockpit) {
        if (cockpit == null) return null;
        CandidateDashboardResponse.MaterialCard mat = cockpit.getMaterial();
        CandidateDashboardResponse.ReportCard rep = cockpit.getReport();
        CandidateDashboardResponse.TimesheetCard sheet = cockpit.getTimesheet();

        // 1. RETURNED report — reviewer is blocking on the intern.
        if (rep != null && "RETURNED".equals(rep.getStatus())) {
            return CandidateDashboardResponse.NextStep.builder()
                    .type("REPORT_RETURNED")
                    .title("Your weekly report needs changes")
                    .subtitle(rep.getReviewNotes() != null && !rep.getReviewNotes().isBlank()
                            ? rep.getReviewNotes()
                            : "Update and resubmit")
                    .ctaLabel("Open report")
                    .ctaHref("/careers/intern/weekly-reports")
                    .build();
        }

        // 2. REJECTED timesheet — same shape.
        if (sheet != null && "REJECTED".equals(sheet.getStatus())) {
            return CandidateDashboardResponse.NextStep.builder()
                    .type("TIMESHEET_REJECTED")
                    .title("Your timesheet was returned")
                    .subtitle("Update and resubmit your hours")
                    .ctaLabel("Open timesheet")
                    .ctaHref("/careers/intern/work")
                    .build();
        }

        // 3. Unacknowledged material released this week (or any unread).
        if (mat != null && !mat.isAcknowledged()) {
            return CandidateDashboardResponse.NextStep.builder()
                    .type("MATERIAL_PENDING_ACK")
                    .title("Read this week's material")
                    .subtitle(mat.getTitle())
                    .ctaLabel("Open material")
                    .ctaHref("/careers/intern/weekly-materials")
                    .build();
        }

        // 4. Report not yet created OR still DRAFT.
        if (rep == null || rep.getStatus() == null) {
            return CandidateDashboardResponse.NextStep.builder()
                    .type("REPORT_TODO")
                    .title("Submit this week's report")
                    .subtitle("Completed work, blockers, learnings, next plan")
                    .ctaLabel("Open report")
                    .ctaHref("/careers/intern/weekly-reports")
                    .build();
        }
        if ("DRAFT".equals(rep.getStatus())) {
            return CandidateDashboardResponse.NextStep.builder()
                    .type("REPORT_DRAFT")
                    .title("Finish your weekly report")
                    .subtitle("Draft saved — submit when ready")
                    .ctaLabel("Open report")
                    .ctaHref("/careers/intern/weekly-reports")
                    .build();
        }

        // 5. Timesheet not yet logged OR DRAFT.
        if (sheet == null || sheet.getStatus() == null) {
            return CandidateDashboardResponse.NextStep.builder()
                    .type("TIMESHEET_TODO")
                    .title("Log this week's hours")
                    .subtitle("Track your time alongside your assignments")
                    .ctaLabel("Open timesheet")
                    .ctaHref("/careers/intern/work")
                    .build();
        }
        if ("DRAFT".equals(sheet.getStatus())) {
            return CandidateDashboardResponse.NextStep.builder()
                    .type("TIMESHEET_DRAFT")
                    .title("Submit this week's timesheet")
                    .subtitle(sheet.getHours() != null
                            ? sheet.getHours() + " hrs in draft"
                            : "Draft saved")
                    .ctaLabel("Open timesheet")
                    .ctaHref("/careers/intern/work")
                    .build();
        }

        // 6. Both submitted and awaiting review → waiting state.
        boolean reportSubmitted = "SUBMITTED".equals(rep.getStatus());
        boolean timesheetSubmitted = "SUBMITTED".equals(sheet.getStatus());
        if (reportSubmitted || timesheetSubmitted) {
            String waiting;
            if (reportSubmitted && timesheetSubmitted) waiting = "Report + timesheet";
            else if (reportSubmitted) waiting = "Weekly report";
            else waiting = "Timesheet";
            return CandidateDashboardResponse.NextStep.builder()
                    .type("AWAITING_REVIEW")
                    .title("Submitted — awaiting supervisor review")
                    .subtitle(waiting + " with your supervisor")
                    .ctaLabel(null)
                    .ctaHref(null)
                    .isWaiting(true)
                    .waitingFor("Supervisor review of " + waiting.toLowerCase())
                    .build();
        }

        // 7. Everything approved (or ack'd + no submission needed) → all set.
        return CandidateDashboardResponse.NextStep.builder()
                .type("WEEK_COMPLETE")
                .title("All set for this week — great work")
                .subtitle("Your supervisor will check in for next week's plan")
                .ctaLabel(null)
                .ctaHref(null)
                .build();
    }

    // ── Change 2 — DashboardStatusDTO (rich timeline + recent updates) ──────

    /**
     * Build the richer "Your Journey" payload served by
     * {@code GET /api/v1/candidate/dashboard-status}.
     *
     * <p>Derived purely on read — no denormalised timeline table; the timeline
     * is computed fresh on each call from the candidate's application, offer,
     * I-9, I-983, E-Verify, onboarding-task, and engagement rows. Non-applicable
     * steps (e.g. I-983 for non-STEM-OPT tracks) are kept and tagged
     * {@code SKIPPED} so the intern sees what is not required, not just what is
     * done.</p>
     */
    @Transactional(readOnly = true)
    public DashboardStatusDTO getDashboardStatus(User caller) {
        if (caller == null || caller.getId() == null) {
            return DashboardStatusDTO.builder()
                    .overallStage("APPLIED")
                    .timeline(Collections.emptyList())
                    .recentUpdates(Collections.emptyList())
                    .build();
        }

        // Reuse the existing aggregate so we don't duplicate the priority
        // ladder for nextStep / journey resolution.
        CandidateDashboardResponse base = build(caller);
        Candidate candidate = candidateRepository.findByUserId(caller.getId()).orElse(null);

        List<DashboardStatusDTO.TimelineStepDTO> timeline = candidate != null
                ? buildTimeline(candidate)
                : Collections.emptyList();

        List<DashboardStatusDTO.RecentUpdateDTO> recent = buildRecentUpdates(caller);

        String overallStage = resolveOverallStage(candidate, base);

        return DashboardStatusDTO.builder()
                .overallStage(overallStage)
                .nextStep(base.getNextStep())
                .timeline(timeline)
                .recentUpdates(recent)
                .build();
    }

    private String resolveOverallStage(Candidate candidate, CandidateDashboardResponse base) {
        if (candidate == null) return "APPLIED";
        if (base != null && base.getEngagement() != null
                && "ACTIVE".equals(base.getEngagement().getStatus())) {
            return "ACTIVE";
        }
        if (base != null && base.getEngagement() != null
                && "COMPLETED".equals(base.getEngagement().getStatus())) {
            return "COMPLETED";
        }
        if (base != null && base.getJourney() != null) {
            String key = base.getJourney().getCurrentStageKey();
            if (key != null && !key.equals("EXITED")) return key;
            if (base.getJourney().isExited()) return "REJECTED";
        }
        return "APPLIED";
    }

    private List<DashboardStatusDTO.TimelineStepDTO> buildTimeline(Candidate candidate) {
        UUID candidateId = candidate.getId();

        List<Application> apps = applicationRepository
                .findByCandidateIdWithPosting(candidateId);
        Application anchor = apps.stream()
                .filter(a -> !ApplicationLifecycle.isExited(a.getStatus()))
                .max(Comparator.comparingInt(a -> ApplicationLifecycle.stageIndexOf(a.getStatus())))
                .orElse(apps.isEmpty() ? null : apps.get(0));
        ApplicationStatus appStatus = anchor != null ? anchor.getStatus() : null;

        List<Interview> interviews = anchor != null
                ? interviewRepository.findAllForCandidateUser(candidate.getUser().getId())
                : Collections.emptyList();
        Interview completedInterview = interviews.stream()
                .filter(i -> i.getStatus() == InterviewStatus.COMPLETED)
                .max(Comparator.comparing(Interview::getFeedbackSubmittedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        Interview scheduledInterview = interviews.stream()
                .filter(i -> i.getStatus() == InterviewStatus.SCHEDULED)
                .findFirst()
                .orElse(null);

        List<Offer> offers = offerRepository.findByCandidateUserIdWithGraph(candidate.getUser().getId());
        Offer accepted = offers.stream()
                .filter(o -> o.getStatus() == OfferStatus.ACCEPTED)
                .findFirst()
                .orElse(null);
        Offer sent = offers.stream()
                .filter(o -> o.getStatus() == OfferStatus.SENT)
                .findFirst()
                .orElse(null);

        I9Form i9 = i9FormRepository.findByCandidateId(candidateId).orElse(null);
        boolean isStemOpt = candidate.getExpectedTrack()
                == com.anvicorp.api.enums.WorkAuthTrack.STEM_OPT;
        I983Plan i983 = i983PlanRepository.findByCandidateIdOrderByCreatedAtDesc(candidateId).stream()
                .findFirst().orElse(null);
        EVerifyCase everifyCase = i9 != null
                ? everifyCaseRepository.findByI9FormId(i9.getId()).orElse(null)
                : null;
        Engagement engagement = engagementRepository.findByCandidateId(candidateId).stream()
                .filter(e -> e.getStatus() != EngagementStatus.TERMINATED)
                .findFirst()
                .orElse(null);
        List<OnboardingTask> tasks = onboardingTaskRepository
                .findByCandidateIdOrderBySortOrderAsc(candidateId);

        List<DashboardStatusDTO.TimelineStepDTO> out = new ArrayList<>();

        // APPLIED
        out.add(step("APPLIED", "Applied",
                anchor != null ? "DONE" : "NOT_STARTED",
                anchor != null ? anchor.getAppliedAt() : null,
                null,
                "Your application has been received.",
                false, null));

        // SCREENING
        boolean screeningRequired = appStatus == ApplicationStatus.SCREENING_SENT
                || appStatus == ApplicationStatus.SCREENING_COMPLETED
                || (anchor != null && screeningRepository
                        .findByApplicationIdWithGraph(anchor.getId()).isPresent());
        if (!screeningRequired) {
            out.add(step("SCREENING", "Screening",
                    "SKIPPED", null, null,
                    "Screening not required for this role.",
                    false, null));
        } else if (appStatus == ApplicationStatus.SCREENING_COMPLETED
                || isAtOrPast(appStatus, ApplicationStatus.SHORTLISTED)) {
            out.add(step("SCREENING", "Screening",
                    "DONE", null, null,
                    "Recruiter screening completed.",
                    false, null));
        } else if (appStatus == ApplicationStatus.SCREENING_SENT) {
            out.add(step("SCREENING", "Screening",
                    "IN_PROGRESS", null, null,
                    "Complete the screening questionnaire.",
                    true, "/careers/intern/applications"));
        } else {
            out.add(step("SCREENING", "Screening",
                    "NOT_STARTED", null, null,
                    "Screening not yet sent.",
                    false, null));
        }

        // INTERVIEW
        if (completedInterview != null) {
            out.add(step("INTERVIEW", "Interview",
                    "DONE",
                    completedInterview.getFeedbackSubmittedAt(),
                    null,
                    interviewDoneDescription(completedInterview),
                    true,
                    "/careers/intern/interviews/" + completedInterview.getId()));
        } else if (scheduledInterview != null) {
            out.add(step("INTERVIEW", "Interview",
                    "IN_PROGRESS", null, null,
                    "Interview scheduled — check your calendar.",
                    false, "/careers/intern/interviews"));
        } else if (appStatus == ApplicationStatus.INTERVIEWED) {
            out.add(step("INTERVIEW", "Interview",
                    "DONE", null, null,
                    "Interview completed.", false, null));
        } else {
            out.add(step("INTERVIEW", "Interview",
                    "NOT_STARTED", null, null,
                    "Awaiting interview scheduling.", false, null));
        }

        // OFFER_ACCEPTED
        if (accepted != null) {
            out.add(step("OFFER_ACCEPTED", "Offer accepted",
                    "DONE",
                    accepted.getRespondedAt() != null
                            ? accepted.getRespondedAt()
                            : accepted.getUpdatedAt(),
                    null,
                    "You accepted the offer.",
                    false, null));
        } else if (sent != null) {
            out.add(step("OFFER_ACCEPTED", "Offer accepted",
                    "IN_PROGRESS", null, null,
                    "Respond to your offer.",
                    true, "/careers/intern/offers"));
        } else {
            out.add(step("OFFER_ACCEPTED", "Offer accepted",
                    "NOT_STARTED", null, null,
                    "An offer hasn't been issued yet.", false, null));
        }

        // I9_SECTION1
        String i9s1Status; Instant i9s1At = null; boolean s1Action = false;
        String i9s1Desc = "I-9 Section 1 — completed by the intern.";
        if (i9 != null && i9.getSection1SignedAt() != null) {
            i9s1Status = "DONE"; i9s1At = i9.getSection1SignedAt();
        } else if (engagement != null
                && (engagement.getStatus() == EngagementStatus.PENDING_COMPLIANCE
                        || engagement.getStatus() == EngagementStatus.READY_TO_START)) {
            i9s1Status = "IN_PROGRESS"; s1Action = true;
            i9s1Desc = "Complete Section 1 of your I-9.";
        } else {
            i9s1Status = "NOT_STARTED";
        }
        out.add(step("I9_SECTION1", "I-9 Section 1",
                i9s1Status, i9s1At, null,
                i9s1Desc, s1Action, s1Action ? "/careers/intern/i9" : null));

        // I9_SECTION2
        String i9s2Status; Instant i9s2At = null; String i9s2Wait = null;
        if (i9 != null && i9.getStatus() == I9Status.COMPLETED && i9.getSection2SignedAt() != null) {
            i9s2Status = "DONE"; i9s2At = i9.getSection2SignedAt();
        } else if (i9 != null && i9.getSection1SignedAt() != null) {
            i9s2Status = "WAITING"; i9s2Wait = "HR";
        } else {
            i9s2Status = "NOT_STARTED";
        }
        out.add(step("I9_SECTION2", "I-9 Section 2 (HR verification)",
                i9s2Status, i9s2At, i9s2Wait,
                "I-9 Section 2 — verified by HR.",
                false, null));

        // I983 (only when STEM OPT)
        if (!isStemOpt) {
            out.add(step("I983", "I-983 Training Plan",
                    "SKIPPED", null, null,
                    "Not applicable for your work-authorization track.",
                    false, null));
        } else if (i983 == null) {
            out.add(step("I983", "I-983 Training Plan",
                    "NOT_STARTED", null, null,
                    "STEM OPT training plan to be drafted.",
                    false, null));
        } else {
            switch (i983.getStatus()) {
                case DSO_APPROVED -> out.add(step("I983", "I-983 Training Plan",
                        "DONE", i983.getDsoRespondedAt(), null,
                        "DSO approved your training plan.",
                        false, "/careers/intern/training-plans/" + i983.getId()));
                case SUBMITTED_TO_DSO -> out.add(step("I983", "I-983 Training Plan",
                        "WAITING", null, "DSO",
                        "Submitted — DSO review pending.",
                        false, "/careers/intern/training-plans/" + i983.getId()));
                case DSO_REJECTED, AMENDMENT_REQUESTED -> out.add(step("I983", "I-983 Training Plan",
                        "BLOCKED", null, null,
                        "DSO requested changes.",
                        true, "/careers/intern/training-plans/" + i983.getId()));
                default -> out.add(step("I983", "I-983 Training Plan",
                        "IN_PROGRESS", null, null,
                        "Drafting your training plan.",
                        true, "/careers/intern/training-plans/" + i983.getId()));
            }
        }

        // E_VERIFY
        if (everifyCase == null) {
            if (i9 != null && i9.getStatus() == I9Status.COMPLETED) {
                out.add(step("E_VERIFY", "E-Verify",
                        "WAITING", null, "HR",
                        "HR will open your E-Verify case.",
                        false, null));
            } else {
                out.add(step("E_VERIFY", "E-Verify",
                        "NOT_STARTED", null, null,
                        "Case opened by HR after I-9 completion.",
                        false, null));
            }
        } else {
            switch (everifyCase.getStatus()) {
                case EMPLOYMENT_AUTHORIZED -> out.add(step("E_VERIFY", "E-Verify",
                        "DONE", everifyCase.getClosedAt(), null,
                        "Employment authorized.", false, null));
                case CLOSED -> out.add(step("E_VERIFY", "E-Verify",
                        "DONE", everifyCase.getClosedAt(), null,
                        "Case closed.", false, null));
                case TENTATIVE_NONCONFIRMATION, FINAL_NONCONFIRMATION -> out.add(step(
                        "E_VERIFY", "E-Verify",
                        "BLOCKED", null, null,
                        "Action required to resolve E-Verify.",
                        false, null));
                default -> out.add(step("E_VERIFY", "E-Verify",
                        "IN_PROGRESS", null, null,
                        "E-Verify case in progress.", false, null));
            }
        }

        // ONBOARDING_TASKS
        long total = tasks.size();
        long done = tasks.stream()
                .filter(t -> t.getStatus() == OnboardingTaskStatus.COMPLETED)
                .count();
        String tasksStatus;
        String tasksDesc;
        boolean tasksAction = false;
        if (total == 0) {
            tasksStatus = engagement != null ? "NOT_STARTED" : "SKIPPED";
            tasksDesc = engagement != null
                    ? "No onboarding tasks yet."
                    : "Onboarding tasks unlock after offer acceptance.";
        } else if (done == total) {
            tasksStatus = "DONE";
            tasksDesc = total + " of " + total + " complete.";
        } else {
            tasksStatus = "IN_PROGRESS"; tasksAction = true;
            tasksDesc = done + " of " + total + " complete.";
        }
        out.add(step("ONBOARDING_TASKS", "Onboarding tasks",
                tasksStatus, null, null, tasksDesc,
                tasksAction, tasksAction ? "/careers/intern/documents" : null));

        // HR_ACTIVATION
        if (engagement == null) {
            out.add(step("HR_ACTIVATION", "HR activation",
                    "NOT_STARTED", null, null,
                    "HR will activate your engagement once compliance is complete.",
                    false, null));
        } else if (engagement.getStatus() == EngagementStatus.PENDING_COMPLIANCE) {
            out.add(step("HR_ACTIVATION", "HR activation",
                    "WAITING", null, "HR",
                    "Awaiting HR to activate your engagement.",
                    false, null));
        } else if (engagement.getStatus() == EngagementStatus.READY_TO_START
                || engagement.getStatus() == EngagementStatus.ACTIVE
                || engagement.getStatus() == EngagementStatus.COMPLETED) {
            out.add(step("HR_ACTIVATION", "HR activation",
                    "DONE",
                    engagement.getUpdatedAt(),
                    null,
                    "HR activated your engagement.",
                    false, null));
        } else {
            out.add(step("HR_ACTIVATION", "HR activation",
                    "BLOCKED", null, null,
                    "Engagement blocked — see compliance.",
                    false, null));
        }

        // FIRST_DAY / ACTIVE
        if (engagement != null && engagement.getStatus() == EngagementStatus.ACTIVE) {
            out.add(step("FIRST_DAY", "First day",
                    "DONE",
                    engagement.getUpdatedAt(),
                    null,
                    "You're active — welcome aboard.", false, null));
        } else if (engagement != null
                && engagement.getStatus() == EngagementStatus.READY_TO_START) {
            out.add(step("FIRST_DAY", "First day",
                    "IN_PROGRESS", null, null,
                    "Ready to start — first day is coming up.",
                    false, null));
        } else if (engagement != null
                && engagement.getStatus() == EngagementStatus.COMPLETED) {
            out.add(step("FIRST_DAY", "First day",
                    "DONE",
                    engagement.getUpdatedAt(),
                    null,
                    "Engagement completed.", false, null));
        } else {
            out.add(step("FIRST_DAY", "First day",
                    "NOT_STARTED", null, null,
                    "Unlocks after HR activation.", false, null));
        }

        return out;
    }

    private DashboardStatusDTO.TimelineStepDTO step(String key, String label,
                                                    String status, Instant completedAt,
                                                    String waitingFor, String description,
                                                    boolean actionRequired, String actionHref) {
        return DashboardStatusDTO.TimelineStepDTO.builder()
                .key(key)
                .label(label)
                .status(status)
                .completedAt(completedAt)
                .waitingFor(waitingFor)
                .description(description)
                .actionRequired(actionRequired)
                .actionHref(actionHref)
                .build();
    }

    private String interviewDoneDescription(Interview interview) {
        if (interview.getFeedbackRecommendation() != null) {
            String rec = interview.getFeedbackRecommendation().name();
            if ("HIRE".equals(rec) || "STRONG_HIRE".equals(rec)) {
                return "Interview completed. Outcome: positive.";
            }
        }
        return "Interview completed.";
    }

    private boolean isAtOrPast(ApplicationStatus s, ApplicationStatus min) {
        if (s == null || min == null) return false;
        return ApplicationLifecycle.stageIndexOf(s) >= ApplicationLifecycle.stageIndexOf(min);
    }

    private List<DashboardStatusDTO.RecentUpdateDTO> buildRecentUpdates(User caller) {
        if (caller == null || caller.getEmail() == null) return Collections.emptyList();
        List<com.anvicorp.api.entity.SentNotification> rows;
        try {
            rows = sentNotificationRepository.findByRecipientOrderBySentAtDesc(
                    caller.getEmail(), PageRequest.of(0, 10));
        } catch (Exception e) {
            return Collections.emptyList();
        }
        List<DashboardStatusDTO.RecentUpdateDTO> out = new ArrayList<>(rows.size());
        for (com.anvicorp.api.entity.SentNotification row : rows) {
            out.add(DashboardStatusDTO.RecentUpdateDTO.builder()
                    .timestamp(row.getSentAt())
                    .kind(updateKindFor(row.getEventType()))
                    .message(updateMessageFor(row.getEventType()))
                    .source(updateSourceFor(row.getEventType()))
                    .build());
        }
        return out;
    }

    private String updateKindFor(NotificationEventType type) {
        if (type == null) return "SYSTEM";
        return switch (type) {
            case I9_SECTION1_REMINDER, I9_SECTION2_PENDING,
                 I983_PLAN_NEEDED, I983_PLAN_READY,
                 EVERIFY_CASE_OPENED, EVERIFY_TNC_ALERT, EVERIFY_CLEARED,
                 WORKAUTH_EXPIRY_90, WORKAUTH_EXPIRY_60, WORKAUTH_EXPIRY_30,
                 WORKAUTH_EXPIRY_14, WORKAUTH_EXPIRY_7,
                 COMPLIANCE_TASK_REMINDER -> "COMPLIANCE";
            case INTERVIEW_SCHEDULED, INTERVIEW_REMINDER, INTERVIEW_COMPLETED -> "INTERVIEW";
            case APPLICATION_RECEIVED, APPLICATION_SHORTLISTED, APPLICATION_REJECTED,
                 OFFER_EXTENDED, OFFER_ACCEPTED, ONBOARDING_WELCOME,
                 CONDITIONAL_SELECT -> "STATUS_CHANGE";
            default -> "SYSTEM";
        };
    }

    private String updateMessageFor(NotificationEventType type) {
        if (type == null) return "Status update";
        return switch (type) {
            case APPLICATION_RECEIVED -> "Your application has been received.";
            case APPLICATION_SHORTLISTED -> "You've been shortlisted.";
            case APPLICATION_REJECTED -> "Application update available.";
            case INTERVIEW_SCHEDULED -> "Your interview has been scheduled.";
            case INTERVIEW_REMINDER -> "Interview reminder — see your calendar.";
            case INTERVIEW_COMPLETED -> "Your interview has been completed.";
            case OFFER_EXTENDED -> "An offer has been extended.";
            case OFFER_ACCEPTED -> "Offer acceptance confirmed.";
            case ONBOARDING_WELCOME -> "HR activated your engagement — you're now Hired.";
            case CONDITIONAL_SELECT -> "Conditional selection confirmed.";
            case I9_SECTION1_REMINDER -> "Reminder: complete I-9 Section 1.";
            case I9_SECTION2_PENDING -> "HR is preparing your I-9 Section 2.";
            case I983_PLAN_NEEDED -> "Your I-983 training plan is required.";
            case I983_PLAN_READY -> "Your I-983 training plan is ready for DSO review.";
            case EVERIFY_CASE_OPENED -> "E-Verify case opened.";
            case EVERIFY_TNC_ALERT -> "E-Verify needs your attention.";
            case EVERIFY_CLEARED -> "E-Verify case closed: Employment Authorized.";
            case WORKAUTH_EXPIRY_90 -> "Work authorization expires in 90 days.";
            case WORKAUTH_EXPIRY_60 -> "Work authorization expires in 60 days.";
            case WORKAUTH_EXPIRY_30 -> "Work authorization expires in 30 days.";
            case WORKAUTH_EXPIRY_14 -> "Work authorization expires in 14 days.";
            case WORKAUTH_EXPIRY_7 -> "Work authorization expires in 7 days.";
            case COMPLIANCE_TASK_REMINDER -> "Onboarding task reminder.";
            case PROJECT_ASSIGNED -> "You've been assigned a project.";
            case PROJECT_RETURNED -> "Your project was returned with comments.";
            case PROJECT_COMPLETED -> "Your project has been completed.";
            case PROJECT_TECH_APPROVED -> "Your project was tech-approved.";
            case PROJECT_RETURNED_FOR_REVISIONS -> "Project sent back for revisions.";
            case PROJECT_PENDING_VIVA -> "Your project is pending viva.";
            case EVALUATION_FINALIZED -> "Your evaluation is finalized.";
            default -> "Status update";
        };
    }

    private String updateSourceFor(NotificationEventType type) {
        if (type == null) return "SYSTEM";
        return switch (type) {
            case I9_SECTION2_PENDING, EVERIFY_CASE_OPENED, EVERIFY_CLEARED,
                 ONBOARDING_WELCOME, OFFER_EXTENDED -> "HR";
            case INTERVIEW_SCHEDULED, INTERVIEW_REMINDER, INTERVIEW_COMPLETED,
                 APPLICATION_RECEIVED, APPLICATION_SHORTLISTED, APPLICATION_REJECTED,
                 CONDITIONAL_SELECT -> "OPERATIONS";
            case PROJECT_TECH_APPROVED, PROJECT_RETURNED_FOR_REVISIONS,
                 PROJECT_ASSIGNED, PROJECT_RETURNED -> "TE";
            case PROJECT_PENDING_VIVA, PROJECT_COMPLETED -> "RM";
            default -> "SYSTEM";
        };
    }
}
