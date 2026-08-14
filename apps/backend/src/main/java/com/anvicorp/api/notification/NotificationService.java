package com.anvicorp.api.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.entity.Application;
import com.anvicorp.api.entity.AuditLog;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.EVerifyCase;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.Evaluation;
import com.anvicorp.api.entity.I9Form;
import com.anvicorp.api.entity.I983Plan;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.entity.InternLifecycle;
import com.anvicorp.api.entity.JobPosting;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.OnboardingTask;
import com.anvicorp.api.entity.Project;
import com.anvicorp.api.entity.SentNotification;
import com.anvicorp.api.entity.StaffingEntity;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.entity.WeeklyReport;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.repository.AuditLogRepository;
import com.anvicorp.api.repository.InternLifecycleRepository;
import com.anvicorp.api.repository.SentNotificationRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrator for batch-1 applicant-lifecycle notifications.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li><b>Idempotency</b> — checks {@link SentNotificationRepository} before
 *       every send; a duplicate {@code (event, target)} silently no-ops. This
 *       absorbs status flip-flops (e.g. Shortlisted → Applied → Shortlisted
 *       only emails once).</li>
 *   <li><b>Audit</b> — writes one {@code NOTIFICATION_SENT} {@link AuditLog}
 *       row per real send with the event type, target id, and recipient
 *       captured in {@code afterJson}.</li>
 *   <li><b>Failure containment</b> — every send is wrapped in try/catch.
 *       Email failures are logged and swallowed; the caller's domain action
 *       (offer sent, application created, etc.) always succeeds.</li>
 * </ol>
 *
 * <h2>Transactional posture</h2>
 * Each {@code sendX(...)} call runs in {@link Propagation#REQUIRES_NEW} so the
 * notification ledger commit (idempotency row + audit row) survives even when
 * the caller's outer transaction rolls back. Conversely, a notification-side
 * write failure CANNOT roll back the caller's domain change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final EmailProvider emailProvider;
    private final SentNotificationRepository sentNotificationRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final UserNotificationDispatcher userNotificationDispatcher;
    private final InternLifecycleRepository internLifecycleRepository;
    private final InternNotificationService internNotifications;
    private final com.anvicorp.api.config.BrandConfig brand;
    /** Wave-1 email-refinement — try seeded EMAIL templates BEFORE the
     *  historical typed EmailProvider methods so a template edited via
     *  the ERM Settings page overrides the hard-coded body. Currently
     *  wired for APPLICATION_RECEIVED and OFFER_ACCEPTED; more will
     *  migrate as Wave-2 lands. */
    private final com.anvicorp.api.erm.CommunicationTemplateService templateService;

    /**
     * Operations recipient for "offer accepted" notifications. Configure as a
     * shared distribution list / shared inbox. Empty/blank disables the ops
     * notification (a warning is logged once per send-attempt so misconfig is
     * visible).
     */
    @Value("${app.ops.notification-email:}")
    private String opsNotificationEmail;

    /** Public URL of the candidate dashboard, used in the onboarding welcome. */
    @Value("${app.onboarding.dashboard-url:https://www.anvicorp.com/careers/intern}")
    private String dashboardUrl;

    /** HR dashboard URL surfaced in batch-2 HR-targeted emails (I-9 §2, I-983 ready). */
    @Value("${app.hr.dashboard-url:https://www.anvicorp.com/careers/hr}")
    private String hrDashboardUrl;

    /** Supervisor dashboard URL surfaced in batch-3 supervisor-targeted emails. */
    @Value("${app.supervisor.dashboard-url:https://www.anvicorp.com/careers/trainer}")
    private String supervisorDashboardUrl;

    /**
     * Template for the offer "view" URL embedded in the offer-extended email.
     * {@code {id}} is replaced with the offer's UUID.
     */
    @Value("${app.offer.view-url-template:https://www.anvicorp.com/careers/intern/offers/{id}}")
    private String offerViewUrlTemplate;

    // ── Public API (per-event-type) ─────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendApplicationReceived(Application application) {
        if (application == null) return;
        UUID targetId = application.getId();
        String email = candidateEmail(application);
        String name = candidateName(application);
        if (email == null) {
            log.warn("APPLICATION_RECEIVED skipped — no candidate email on application {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.APPLICATION_RECEIVED, targetId)) return;

        String jobTitle = jobTitle(application);
        String entityName = entityName(application);
        // Wave-1 email-refinement — prefer the seeded APPLICATION_RECEIVED
        // template so the ERM Settings > Communication Templates page can
        // customise the body. Falls back to the hard-coded typed method if
        // the template is absent (fresh DB pre-seed, or an operator has
        // manually deleted the row).
        deliver(NotificationEventType.APPLICATION_RECEIVED, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    vars.put("jobTitle", nz(jobTitle));
                    var rendered = templateService.render(
                            "APPLICATION_RECEIVED", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendApplicationReceived(
                                email, name, jobTitle, entityName);
                    }
                });

        dispatchInApp(applicantUserId(application),
                NotificationEventType.APPLICATION_RECEIVED,
                "Application received",
                "We received your application for " + nz(jobTitle) + ". We'll be in touch.",
                INTERN_DASH + "/applications",
                EnumSet.of(StaffRole.ERM, StaffRole.MANAGER),
                "New application: " + nz(jobTitle),
                "Applicant " + nz(name) + " applied to " + nz(jobTitle) + ".");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendApplicationShortlisted(Application application) {
        if (application == null) return;
        UUID targetId = application.getId();
        String email = candidateEmail(application);
        String name = candidateName(application);
        if (email == null) {
            log.warn("APPLICATION_SHORTLISTED skipped — no candidate email on application {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.APPLICATION_SHORTLISTED, targetId)) return;

        String jobTitle = jobTitle(application);
        String entityName = entityName(application);
        deliver(NotificationEventType.APPLICATION_SHORTLISTED, targetId, email,
                () -> emailProvider.sendApplicationShortlisted(email, name, jobTitle, entityName));

        dispatchInApp(applicantUserId(application),
                NotificationEventType.APPLICATION_SHORTLISTED,
                "Your application was shortlisted",
                "Your application for " + nz(jobTitle) + " moved to Shortlisted. "
                        + "Watch for interview scheduling next.",
                INTERN_DASH + "/applications",
                EnumSet.of(StaffRole.MANAGER),
                "Applicant shortlisted: " + nz(name),
                nz(name) + " was shortlisted for " + nz(jobTitle) + ".");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendApplicationRejected(Application application) {
        if (application == null) return;
        UUID targetId = application.getId();
        String email = candidateEmail(application);
        String name = candidateName(application);
        if (email == null) {
            log.warn("APPLICATION_REJECTED skipped — no candidate email on application {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.APPLICATION_REJECTED, targetId)) return;

        String jobTitle = jobTitle(application);
        String entityName = entityName(application);
        deliver(NotificationEventType.APPLICATION_REJECTED, targetId, email,
                () -> emailProvider.sendApplicationRejected(email, name, jobTitle, entityName));

        dispatchInApp(applicantUserId(application),
                NotificationEventType.APPLICATION_REJECTED,
                "Application update",
                "Your application for " + nz(jobTitle) + " was not advanced. "
                        + "Thank you for applying.",
                INTERN_DASH + "/applications",
                null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendInterviewScheduled(Interview interview) {
        if (interview == null) return;
        UUID targetId = interview.getId();
        Application application = interview.getApplication();
        String email = candidateEmail(application);
        String name = candidateName(application);
        if (email == null) {
            log.warn("INTERVIEW_SCHEDULED skipped — no candidate email on interview {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.INTERVIEW_SCHEDULED, targetId)) return;

        String jobTitle = jobTitle(application);
        String entityName = entityName(application);
        String interviewType = interview.getType() != null ? interview.getType().name() : null;
        String interviewerName = interview.getInterviewer() != null
                ? interview.getInterviewer().getFullName() : null;
        // Slice-6d fold-in — template-first via existing INTERVIEW_SCHEDULED
        // (also used by InterviewEmailListener's event-driven path).
        // Distinct entry-point from ErmInterviewService.schedule which
        // publishes InterviewScheduledEvent; each fires ONE leg, no
        // double-send.
        deliver(NotificationEventType.INTERVIEW_SCHEDULED, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    vars.put("jobTitle", nz(jobTitle));
                    vars.put("scheduledForLocal", interview.getScheduledAt() != null
                            ? interview.getScheduledAt().toString() : "your scheduled time");
                    vars.put("timezone", "UTC");
                    vars.put("zoomJoinUrl", interview.getMeetingUrl() != null
                            ? interview.getMeetingUrl() : "(link will be shared)");
                    vars.put("interviewerName", interviewerName != null
                            ? interviewerName : "your interviewer");
                    vars.put("prepInstructions", interview.getCandidateNotes() != null
                            && !interview.getCandidateNotes().isBlank()
                            ? interview.getCandidateNotes()
                            : "Come prepared to discuss your background and the role.");
                    var rendered = templateService.render(
                            "INTERVIEW_SCHEDULED", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendInterviewScheduled(
                                email, name, jobTitle, entityName,
                                interview.getScheduledAt(), interview.getDurationMinutes(),
                                interviewType, interviewerName,
                                interview.getMeetingUrl(), interview.getCandidateNotes());
                    }
                });

        dispatchInApp(applicantUserId(application),
                NotificationEventType.INTERVIEW_SCHEDULED,
                "Interview scheduled",
                "Your interview for " + nz(jobTitle) + " is scheduled. "
                        + "Open the Interviews page for details.",
                INTERN_DASH + "/interviews/" + targetId,
                EnumSet.of(StaffRole.ERM, StaffRole.MANAGER),
                "Interview scheduled: " + nz(name),
                "Interview for " + nz(name) + " (" + nz(jobTitle) + ") is on the calendar.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendInterviewReminder(Interview interview) {
        if (interview == null) return;
        UUID targetId = interview.getId();
        Application application = interview.getApplication();
        String email = candidateEmail(application);
        String name = candidateName(application);
        if (email == null) {
            log.warn("INTERVIEW_REMINDER skipped — no candidate email on interview {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.INTERVIEW_REMINDER, targetId)) return;

        String jobTitle = jobTitle(application);
        String entityName = entityName(application);
        String interviewType = interview.getType() != null ? interview.getType().name() : null;
        String interviewerName = interview.getInterviewer() != null
                ? interview.getInterviewer().getFullName() : null;
        // Slice-6b Category A fold-in — template-first with typed fallback.
        deliver(NotificationEventType.INTERVIEW_REMINDER, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    vars.put("jobTitle", nz(jobTitle));
                    vars.put("entityName", nz(entityName));
                    vars.put("scheduledAtLocal", interview.getScheduledAt() != null
                            ? interview.getScheduledAt().toString() : "your scheduled time");
                    vars.put("durationMinutes", interview.getDurationMinutes() != null
                            ? interview.getDurationMinutes() : 30);
                    vars.put("interviewType", interviewType != null ? interviewType : "interview");
                    vars.put("interviewerName", interviewerName != null
                            ? interviewerName : "your interviewer");
                    vars.put("meetingUrl", interview.getMeetingUrl() != null
                            ? interview.getMeetingUrl() : "(link will be shared)");
                    var rendered = templateService.render(
                            "INTERVIEW_REMINDER", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendInterviewReminder(
                                email, name, jobTitle, entityName,
                                interview.getScheduledAt(), interview.getDurationMinutes(),
                                interviewType, interviewerName,
                                interview.getMeetingUrl());
                    }
                });

        dispatchInApp(applicantUserId(application),
                NotificationEventType.INTERVIEW_REMINDER,
                "Interview reminder",
                "Your interview for " + nz(jobTitle) + " starts within 24 hours.",
                INTERN_DASH + "/interviews/" + targetId,
                null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendOfferExtended(Offer offer) {
        if (offer == null) return;
        UUID targetId = offer.getId();
        Application application = offer.getApplication();
        String email = candidateEmail(application);
        String name = candidateName(application);
        if (email == null) {
            log.warn("OFFER_EXTENDED skipped — no candidate email on offer {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.OFFER_EXTENDED, targetId)) return;

        String jobTitle = jobTitle(application);
        String entityName = entityName(application);
        String currency = offer.getCompensationCurrency();
        String frequency = offer.getCompensationFrequency() != null
                ? offer.getCompensationFrequency().name() : null;
        String viewUrl = offerViewUrlTemplate.replace("{id}", offer.getId().toString());
        // Slice-6d fold-in — template-first via new OFFER_EXTENDED template.
        deliver(NotificationEventType.OFFER_EXTENDED, targetId, email,
                () -> {
                    String compensationLine;
                    if (offer.getCompensationAmount() != null) {
                        compensationLine = offer.getCompensationAmount()
                                + (currency != null ? " " + currency : "")
                                + (frequency != null ? " / "
                                        + frequency.toLowerCase().replace('_', ' ') : "");
                    } else {
                        compensationLine = "See offer letter";
                    }
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    vars.put("jobTitle", nz(jobTitle));
                    vars.put("entityName", nz(entityName));
                    vars.put("compensationLine", compensationLine);
                    vars.put("startDate", offer.getStartDate() != null
                            ? offer.getStartDate().toString() : "TBD");
                    vars.put("expiresAt", offer.getExpiresAt() != null
                            ? offer.getExpiresAt().toString() : "See offer letter");
                    vars.put("deepLink", viewUrl != null ? viewUrl : "");
                    var rendered = templateService.render(
                            "OFFER_EXTENDED", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendOfferExtended(
                                email, name, jobTitle, entityName,
                                offer.getCompensationAmount(), currency, frequency,
                                offer.getStartDate(), offer.getExpiresAt(), viewUrl);
                    }
                });

        dispatchInApp(applicantUserId(application),
                NotificationEventType.OFFER_EXTENDED,
                "Your offer is ready to view",
                "We've extended an offer for " + nz(jobTitle) + ". "
                        + "Open the Offer page to review and sign.",
                INTERN_DASH + "/offer",
                EnumSet.of(StaffRole.ERM, StaffRole.MANAGER),
                "Offer sent: " + nz(name),
                "Offer extended to " + nz(name) + " for " + nz(jobTitle) + ".");
    }

    /** Fires BOTH sends — applicant confirmation + ops heads-up. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendOfferAccepted(Offer offer) {
        if (offer == null) return;
        UUID targetId = offer.getId();
        Application application = offer.getApplication();
        String email = candidateEmail(application);
        String name = candidateName(application);
        String jobTitle = jobTitle(application);
        String entityName = entityName(application);

        // 1) Applicant confirmation.
        if (email != null) {
            if (!alreadySent(NotificationEventType.OFFER_ACCEPTED, targetId)) {
                // Wave-1 email-refinement — prefer the seeded
                // OFFER_ACCEPTED template so the ERM Settings page can
                // customise the body. Falls back to the historical typed
                // method if the template is absent.
                deliver(NotificationEventType.OFFER_ACCEPTED, targetId, email,
                        () -> {
                            Map<String, Object> vars = new LinkedHashMap<>();
                            vars.put("firstName", nz(name).split(" ")[0]);
                            vars.put("jobTitle", nz(jobTitle));
                            vars.put("tentativeStartDate", offer.getStartDate() != null
                                    ? offer.getStartDate().toString() : "TBD");
                            var rendered = templateService.render(
                                    "OFFER_ACCEPTED", "EMAIL", vars);
                            if (rendered.isPresent()) {
                                emailProvider.sendRendered(
                                        email, rendered.get().subject(),
                                        rendered.get().body());
                            } else {
                                emailProvider.sendOfferAccepted(
                                        email, name, jobTitle, entityName,
                                        offer.getStartDate());
                            }
                        });
            }
        } else {
            log.warn("OFFER_ACCEPTED (applicant) skipped — no candidate email on offer {}", targetId);
        }

        // 2) Operations heads-up.
        if (opsNotificationEmail == null || opsNotificationEmail.isBlank()) {
            log.warn("OFFER_ACCEPTED_OPS skipped — app.ops.notification-email is not configured "
                    + "(offer {}). Set OPS_NOTIFICATION_EMAIL to enable.", targetId);
            return;
        }
        // In-app mirror — applicant gets the welcome, ERM (when lifecycle exists)
        // gets the "offer signed" heads-up. Lifecycle exists by the time
        // OfferSignedListener fires this for the signed path; for the legacy
        // pre-sign acceptance path the lifecycle isn't yet created and the ERM
        // dispatch is silently skipped per the standard rule.
        dispatchInApp(applicantUserId(application),
                NotificationEventType.OFFER_ACCEPTED,
                "Welcome to " + nz(entityName) + "!",
                "Your offer is signed. Onboarding instructions will follow shortly.",
                INTERN_DASH,
                EnumSet.of(StaffRole.ERM),
                "Offer signed: " + nz(name),
                nz(name) + " signed the offer for " + nz(jobTitle) + ".");

        if (alreadySent(NotificationEventType.OFFER_ACCEPTED_OPS, targetId)) return;
        deliver(NotificationEventType.OFFER_ACCEPTED_OPS, targetId, opsNotificationEmail,
                () -> emailProvider.sendOfferAcceptedToOps(
                        opsNotificationEmail, name, email, jobTitle, entityName,
                        offer.getStartDate()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendOnboardingWelcome(Engagement engagement) {
        if (engagement == null) return;
        UUID targetId = engagement.getId();
        Application application = engagement.getApplication();
        String email = candidateEmail(application);
        String name = candidateName(application);
        if (email == null) {
            log.warn("ONBOARDING_WELCOME skipped — no candidate email on engagement {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.ONBOARDING_WELCOME, targetId)) return;

        String jobTitle = jobTitle(application);
        StaffingEntity ent = engagement.getEntity();
        String entityName = ent != null ? ent.getName() : entityName(application);
        // Slice-6b Category A fold-in — template-first with the typed
        // emailProvider.sendOnboardingWelcome as the rebrand-safe fallback
        // when the template row is absent (fresh DB, operator-deleted row).
        LocalDate startDate = engagement.getActualStartDate();
        deliver(NotificationEventType.ONBOARDING_WELCOME, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    vars.put("jobTitle", nz(jobTitle));
                    vars.put("entityName", nz(entityName));
                    vars.put("startDate", startDate != null
                            ? startDate.toString() : "your scheduled start date");
                    vars.put("deepLink", dashboardUrl != null ? dashboardUrl : "");
                    var rendered = templateService.render(
                            "ONBOARDING_WELCOME", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendOnboardingWelcome(
                                email, name, jobTitle, entityName, startDate, dashboardUrl);
                    }
                });

        dispatchInApp(applicantUserId(application),
                NotificationEventType.ONBOARDING_WELCOME,
                "Onboarding started",
                "Your engagement with " + nz(entityName) + " is now active. "
                        + "Complete onboarding items to begin.",
                INTERN_DASH + "/documents",
                EnumSet.of(StaffRole.ERM),
                "Engagement active: " + nz(name),
                nz(name) + " moved to ACTIVE for " + nz(jobTitle) + ".");
    }

    // ── Batch 2 — compliance / onboarding ───────────────────────────────────
    // PII RULE: callers pass only the entity reference. The send-fns here pull
    // ONLY name + status + dates + URLs from the entity — never SSN, document
    // numbers, addresses, or any decrypted PII. See SmtpEmailProvider templates.

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendI9Section1Reminder(I9Form form) {
        if (form == null) return;
        UUID targetId = form.getId();
        String email = candidateEmailFromForm(form);
        String name = candidateNameFromForm(form);
        if (email == null) {
            log.warn("I9_SECTION1_REMINDER skipped — no candidate email on i9-form {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.I9_SECTION1_REMINDER, targetId)) return;

        // Slice-6d fold-in — template-first via new I9_SECTION1_REMINDER.
        deliver(NotificationEventType.I9_SECTION1_REMINDER, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    vars.put("dueDate", form.getSection1DueDate() != null
                            ? form.getSection1DueDate().toString() : "soon");
                    vars.put("deepLink", dashboardUrl != null ? dashboardUrl : "");
                    var rendered = templateService.render(
                            "I9_SECTION1_REMINDER", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendI9Section1Reminder(
                                email, name, form.getSection1DueDate(), dashboardUrl);
                    }
                });

        dispatchInApp(applicantUserIdFromForm(form),
                NotificationEventType.I9_SECTION1_REMINDER,
                "Complete your I-9 Section 1",
                "Your I-9 Section 1 is due. Open the I-9 page to complete it.",
                INTERN_DASH + "/documents",
                null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendI9Section2Pending(I9Form form) {
        if (form == null) return;
        UUID targetId = form.getId();
        if (alreadySent(NotificationEventType.I9_SECTION2_PENDING, targetId)) return;

        String internName = candidateNameFromForm(form);
        List<String> hrRecipients = hrComplianceEmails();
        if (hrRecipients.isEmpty()) {
            log.warn("I9_SECTION2_PENDING skipped — no HR users to notify "
                    + "(form {}). Add a user with HR role.", targetId);
            return;
        }
        // Single ledger row keyed on the form; the email goes to all HR users
        // via the To: list so we don't fan-out per recipient.
        String to = String.join(", ", hrRecipients);
        deliver(NotificationEventType.I9_SECTION2_PENDING, targetId, to,
                () -> emailProvider.sendI9Section2Pending(
                        to, internName, form.getSection2DueDate(), hrDashboardUrl));

        // Staff-only event (no applicant in-app row). ERM slot on the intern's
        // lifecycle drives the in-app fan-out; mirrors the HR To: list above.
        dispatchInApp(applicantUserIdFromForm(form),
                NotificationEventType.I9_SECTION2_PENDING,
                null, null, null,
                EnumSet.of(StaffRole.ERM),
                "I-9 Section 2 pending: " + nz(internName),
                nz(internName) + " completed Section 1; Section 2 awaits HR.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendI983PlanNeeded(Engagement engagement) {
        if (engagement == null) return;
        UUID targetId = engagement.getId();
        Application application = engagement.getApplication();
        String email = candidateEmail(application);
        String name = candidateName(application);
        if (email == null) {
            log.warn("I983_PLAN_NEEDED skipped — no candidate email on engagement {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.I983_PLAN_NEEDED, targetId)) return;

        deliver(NotificationEventType.I983_PLAN_NEEDED, targetId, email,
                () -> emailProvider.sendI983PlanNeeded(email, name, dashboardUrl));

        dispatchInApp(applicantUserId(application),
                NotificationEventType.I983_PLAN_NEEDED,
                "Draft your I-983 Training Plan",
                "Start your I-983 training plan to keep your STEM OPT on track.",
                INTERN_DASH + "/documents",
                null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendI983PlanReady(I983Plan plan) {
        if (plan == null) return;
        UUID targetId = plan.getId();
        if (alreadySent(NotificationEventType.I983_PLAN_READY, targetId)) return;

        Candidate c = plan.getCandidate();
        User u = c != null ? c.getUser() : null;
        String internName = u != null ? u.getFullName() : null;

        List<String> hrRecipients = hrComplianceEmails();
        if (hrRecipients.isEmpty()) {
            log.warn("I983_PLAN_READY skipped — no HR users (plan {})", targetId);
            return;
        }
        String to = String.join(", ", hrRecipients);
        deliver(NotificationEventType.I983_PLAN_READY, targetId, to,
                () -> emailProvider.sendI983PlanReady(to, internName, hrDashboardUrl));

        dispatchInApp(applicantUserIdFromCandidate(c),
                NotificationEventType.I983_PLAN_READY,
                null, null, null,
                EnumSet.of(StaffRole.ERM),
                "I-983 plan ready: " + nz(internName),
                nz(internName) + " signed the I-983; ready for DSO approval.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendEVerifyCaseOpened(EVerifyCase ev) {
        if (ev == null) return;
        UUID targetId = ev.getId();
        I9Form form = ev.getI9Form();
        String email = candidateEmailFromForm(form);
        String name = candidateNameFromForm(form);
        if (email == null) {
            log.warn("EVERIFY_CASE_OPENED skipped — no candidate email on case {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.EVERIFY_CASE_OPENED, targetId)) return;

        // Slice-6d fold-in — template-first via existing EVERIFY_CASE_OPENED
        // (also used by ComplianceLifecycleListener's event-driven path).
        deliver(NotificationEventType.EVERIFY_CASE_OPENED, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    var rendered = templateService.render(
                            "EVERIFY_CASE_OPENED", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendEVerifyCaseOpened(email, name, dashboardUrl);
                    }
                });

        dispatchInApp(applicantUserIdFromForm(form),
                NotificationEventType.EVERIFY_CASE_OPENED,
                "E-Verify case opened",
                "Your E-Verify case is open. We'll let you know if any action is needed.",
                INTERN_DASH + "/documents",
                null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendEVerifyTncAlert(EVerifyCase ev) {
        if (ev == null) return;
        UUID targetId = ev.getId();
        I9Form form = ev.getI9Form();
        String email = candidateEmailFromForm(form);
        String name = candidateNameFromForm(form);
        if (email == null) {
            log.warn("EVERIFY_TNC_ALERT skipped — no candidate email on case {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.EVERIFY_TNC_ALERT, targetId)) return;

        // Slice-6d fold-in — template-first via existing
        // EVERIFY_TENTATIVE_NONCONFIRMATION. Legacy path doesn't know
        // the ERM; ermName defaults to "your ERM".
        deliver(NotificationEventType.EVERIFY_TNC_ALERT, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    vars.put("ermName", "your ERM");
                    var rendered = templateService.render(
                            "EVERIFY_TENTATIVE_NONCONFIRMATION", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendEVerifyTncAlert(email, name, dashboardUrl);
                    }
                });

        dispatchInApp(applicantUserIdFromForm(form),
                NotificationEventType.EVERIFY_TNC_ALERT,
                "E-Verify: action required",
                "Your E-Verify case received a Tentative Nonconfirmation. "
                        + "Open onboarding for next steps.",
                INTERN_DASH + "/documents",
                null, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendEVerifyCleared(EVerifyCase ev) {
        if (ev == null) return;
        UUID targetId = ev.getId();
        I9Form form = ev.getI9Form();
        String email = candidateEmailFromForm(form);
        String name = candidateNameFromForm(form);
        if (email == null) {
            log.warn("EVERIFY_CLEARED skipped — no candidate email on case {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.EVERIFY_CLEARED, targetId)) return;

        // Slice-6d fold-in — template-first via existing EVERIFY_AUTHORIZED.
        deliver(NotificationEventType.EVERIFY_CLEARED, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    var rendered = templateService.render(
                            "EVERIFY_AUTHORIZED", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendEVerifyCleared(email, name, dashboardUrl);
                    }
                });

        dispatchInApp(applicantUserIdFromForm(form),
                NotificationEventType.EVERIFY_CLEARED,
                "E-Verify: employment authorized",
                "Your E-Verify case cleared with Employment Authorized.",
                INTERN_DASH + "/documents",
                null, null, null);
    }

    /**
     * Work-auth expiry reminder. One event type per threshold, target_id =
     * engagement_id, so each engagement gets at most one email per threshold
     * (90/60/30/14/7). Scheduler decides which threshold to fire.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendWorkAuthExpiryReminder(Engagement engagement, int daysUntilExpiry,
                                           LocalDate expirationDate, String authType) {
        if (engagement == null) return;
        NotificationEventType eventType = workAuthEventForThreshold(daysUntilExpiry);
        if (eventType == null) return; // not a configured threshold
        UUID targetId = engagement.getId();
        Application application = engagement.getApplication();
        String email = candidateEmail(application);
        String name = candidateName(application);
        if (email == null) {
            log.warn("{} skipped — no candidate email on engagement {}", eventType, targetId);
            return;
        }
        if (alreadySent(eventType, targetId)) return;

        // Slice-6b Category A fold-in — template-first (reusing the
        // existing WORK_AUTH_EXPIRING template that ComplianceLifecycleListener
        // uses for its event-driven path) with the typed fallback. The
        // ermName var isn't known at the scheduler layer — default to
        // "your ERM" so the copy stays coherent.
        deliver(eventType, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    vars.put("workAuthType", nz(authType).isEmpty()
                            ? "work authorization" : authType);
                    vars.put("expirationDate", expirationDate != null
                            ? expirationDate.toString() : "soon");
                    vars.put("daysUntilExpiration", daysUntilExpiry);
                    vars.put("ermName", "your ERM");
                    var rendered = templateService.render(
                            "WORK_AUTH_EXPIRING", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendWorkAuthExpiryReminder(
                                email, name, daysUntilExpiry, expirationDate,
                                authType, dashboardUrl);
                    }
                });

        dispatchInApp(applicantUserId(application),
                eventType,
                "Work authorization expires in " + daysUntilExpiry + " days",
                "Your " + nz(authType) + " expires " + expirationDate
                        + ". Coordinate with your ERM on next steps.",
                INTERN_DASH + "/documents",
                null, null, null);
    }

    /**
     * Generic compliance-task reminder. target_id = task_id so each overdue
     * task gets exactly one reminder ever (re-running the scheduler on the
     * same overdue task is a no-op).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendComplianceTaskReminder(OnboardingTask task) {
        if (task == null) return;
        UUID targetId = task.getId();
        Candidate c = task.getCandidate();
        User u = c != null ? c.getUser() : null;
        String email = u != null ? u.getEmail() : null;
        String name = u != null ? u.getFullName() : null;
        if (email == null) {
            log.warn("COMPLIANCE_TASK_REMINDER skipped — no candidate email on task {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.COMPLIANCE_TASK_REMINDER, targetId)) return;

        Integer overdue = null;
        if (task.getDueDate() != null) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(
                    task.getDueDate(), LocalDate.now());
            overdue = days > 0 ? (int) days : 0;
        }
        final Integer overdueFinal = overdue;
        // Slice-6b Category A fold-in — template-first with typed fallback.
        // overdueLine is empty when the task isn't yet overdue (no orphan
        // "Overdue by …" line in that case).
        deliver(NotificationEventType.COMPLIANCE_TASK_REMINDER, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    vars.put("taskTitle", nz(task.getTitle()));
                    vars.put("dueDate", task.getDueDate() != null
                            ? task.getDueDate().toString() : "TBD");
                    vars.put("overdueLine", overdueFinal != null && overdueFinal > 0
                            ? "  · Overdue by " + overdueFinal + " day"
                                    + (overdueFinal == 1 ? "" : "s")
                            : "");
                    vars.put("deepLink", dashboardUrl != null ? dashboardUrl : "");
                    var rendered = templateService.render(
                            "COMPLIANCE_TASK_REMINDER", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendComplianceTaskReminder(
                                email, name, task.getTitle(), task.getDueDate(),
                                overdueFinal, dashboardUrl);
                    }
                });

        dispatchInApp(applicantUserIdFromCandidate(c),
                NotificationEventType.COMPLIANCE_TASK_REMINDER,
                "Action needed: " + nz(task.getTitle()),
                (overdueFinal != null && overdueFinal > 0
                        ? "Overdue by " + overdueFinal + " day(s). "
                        : "Due " + task.getDueDate() + ". ")
                        + "Open onboarding to complete it.",
                INTERN_DASH + "/documents",
                null, null, null);
    }

    // ── Batch 3 — intern weekly cycle ───────────────────────────────────────

    // WeeklyMaterial release + unread-reminder helpers removed in Trainer
    // Phase 0 — the entire weekly-materials concept was retired (not in the
    // Trainer doc spec). The Trainer-doc's "Files / Templates" module uses
    // the new ProjectTemplate entity instead.

    /** Weekly report due — fired by the daily scheduler late in the week. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendWeeklyReportDue(Engagement engagement, LocalDate weekStart) {
        if (engagement == null || weekStart == null) return;
        Candidate c = engagement.getCandidate();
        User u = c != null ? c.getUser() : null;
        String email = u != null ? u.getEmail() : null;
        String name = u != null ? u.getFullName() : null;
        if (email == null) return;

        UUID targetId = weeklyTargetId("REPORT_DUE",
                engagement.getId().toString(), weekStart.toString());
        if (alreadySent(NotificationEventType.WEEKLY_REPORT_DUE, targetId)) return;

        // Slice-6b Category A fold-in — template-first with typed fallback.
        deliver(NotificationEventType.WEEKLY_REPORT_DUE, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    vars.put("weekStart", weekStart.toString());
                    vars.put("deepLink", dashboardUrl != null ? dashboardUrl : "");
                    var rendered = templateService.render(
                            "WEEKLY_REPORT_DUE", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendWeeklyReportDue(email, name, weekStart, dashboardUrl);
                    }
                });

        dispatchInApp(applicantUserIdFromCandidate(c),
                NotificationEventType.WEEKLY_REPORT_DUE,
                "Weekly report due",
                "Submit your weekly report for the week of " + weekStart + ".",
                INTERN_DASH + "/weekly-meetings",
                null, null, null);
    }

    /** Weekly report returned for changes — event-triggered. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendWeeklyReportReturned(WeeklyReport report) {
        if (report == null) return;
        UUID targetId = report.getId();
        Candidate c = report.getIntern();
        User u = c != null ? c.getUser() : null;
        String email = u != null ? u.getEmail() : null;
        String name = u != null ? u.getFullName() : null;
        if (email == null) {
            log.warn("WEEKLY_REPORT_RETURNED skipped — no candidate email on report {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.WEEKLY_REPORT_RETURNED, targetId)) return;

        // Slice-6d fold-in — template-first via new WEEKLY_REPORT_RETURNED.
        // reviewNotesLine is empty when the reviewer left no notes (no
        // orphan text).
        deliver(NotificationEventType.WEEKLY_REPORT_RETURNED, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    vars.put("weekStart", report.getWeekStart() != null
                            ? report.getWeekStart().toString() : "TBD");
                    vars.put("reviewNotesLine",
                            report.getReviewNotes() != null
                                    && !report.getReviewNotes().isBlank()
                                    ? "\n\nReviewer notes: " + report.getReviewNotes()
                                    : "");
                    vars.put("deepLink", dashboardUrl != null ? dashboardUrl : "");
                    var rendered = templateService.render(
                            "WEEKLY_REPORT_RETURNED", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendWeeklyReportReturned(
                                email, name, report.getWeekStart(),
                                report.getReviewNotes(), dashboardUrl);
                    }
                });

        dispatchInApp(applicantUserIdFromCandidate(c),
                NotificationEventType.WEEKLY_REPORT_RETURNED,
                "Weekly report returned for changes",
                "Your report for week of " + report.getWeekStart()
                        + " needs revisions. Open it for review notes.",
                INTERN_DASH + "/weekly-meetings",
                null, null, null);
    }

    /** Weekly report approved — event-triggered. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendWeeklyReportApproved(WeeklyReport report) {
        if (report == null) return;
        UUID targetId = report.getId();
        Candidate c = report.getIntern();
        User u = c != null ? c.getUser() : null;
        String email = u != null ? u.getEmail() : null;
        String name = u != null ? u.getFullName() : null;
        if (email == null) return;
        if (alreadySent(NotificationEventType.WEEKLY_REPORT_APPROVED, targetId)) return;

        // Slice-6d fold-in — template-first via new WEEKLY_REPORT_APPROVED.
        deliver(NotificationEventType.WEEKLY_REPORT_APPROVED, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    vars.put("weekStart", report.getWeekStart() != null
                            ? report.getWeekStart().toString() : "TBD");
                    vars.put("deepLink", dashboardUrl != null ? dashboardUrl : "");
                    var rendered = templateService.render(
                            "WEEKLY_REPORT_APPROVED", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendWeeklyReportApproved(
                                email, name, report.getWeekStart(), dashboardUrl);
                    }
                });

        dispatchInApp(applicantUserIdFromCandidate(c),
                NotificationEventType.WEEKLY_REPORT_APPROVED,
                "Weekly report approved",
                "Your weekly report for " + report.getWeekStart() + " was approved.",
                INTERN_DASH + "/weekly-meetings",
                null, null, null);
    }

    /** Timesheet due — fired by the daily scheduler. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendTimesheetDue(Engagement engagement, LocalDate weekStart) {
        if (engagement == null || weekStart == null) return;
        Candidate c = engagement.getCandidate();
        User u = c != null ? c.getUser() : null;
        String email = u != null ? u.getEmail() : null;
        String name = u != null ? u.getFullName() : null;
        if (email == null) return;

        UUID targetId = weeklyTargetId("TIMESHEET_DUE",
                engagement.getId().toString(), weekStart.toString());
        if (alreadySent(NotificationEventType.TIMESHEET_DUE, targetId)) return;

        // Slice-6b Category A fold-in — template-first with typed fallback.
        deliver(NotificationEventType.TIMESHEET_DUE, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    vars.put("weekStart", weekStart.toString());
                    vars.put("deepLink", dashboardUrl != null ? dashboardUrl : "");
                    var rendered = templateService.render(
                            "TIMESHEET_DUE", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendTimesheetDue(email, name, weekStart, dashboardUrl);
                    }
                });

        dispatchInApp(applicantUserIdFromCandidate(c),
                NotificationEventType.TIMESHEET_DUE,
                "Timesheet due",
                "Submit your timesheet for the week of " + weekStart + ".",
                INTERN_DASH + "/timesheets",
                null, null, null);

        // Tier A — sendTimesheetDue above is a typed EmailProvider method
        // that bypasses BridgingEmailProvider and reaches the intern's
        // personal Gmail. For ACTIVE interns we ALSO land the reminder in
        // their company mailbox via notifyIntern (bridge-routed). Pre-active
        // interns skip silently inside the helper. Idempotency for the
        // weekly send is already guaranteed by the alreadySent check above.
        try {
            UUID internUserId = applicantUserIdFromCandidate(c);
            if (internUserId != null) {
                String subject = "Reminder: your timesheet for the week of "
                        + weekStart + " is due";
                String plain = "Hi,\n\nThis is a reminder to submit your timesheet "
                        + "for the week of " + weekStart + ". Approval can take a "
                        + "couple of business days, so submitting today keeps the "
                        + "week's hours on track."
                        + "\n\nOpen your timesheets: " + INTERN_DASH + "/timesheets"
                        + "\n\n" + brand.signoff();
                internNotifications.notifyIntern(internUserId, subject, plain, null);
            }
        } catch (Exception e) {
            log.warn("[NotificationService] TIMESHEET_DUE internal-mail failed (non-fatal) "
                    + "engagement={}: {}", engagement.getId(), e.getMessage());
        }
    }

    /** Project assigned — event-triggered, intern recipient. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendProjectAssigned(Project project) {
        if (project == null) return;
        UUID targetId = project.getId();
        Candidate intern = project.getIntern();
        User u = intern != null ? intern.getUser() : null;
        String email = u != null ? u.getEmail() : null;
        String name = u != null ? u.getFullName() : null;
        if (email == null) {
            log.warn("PROJECT_ASSIGNED skipped — no candidate email on project {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.PROJECT_ASSIGNED, targetId)) return;

        User assignedBy = project.getAssignedBy();
        String by = assignedBy != null ? assignedBy.getFullName() : null;
        deliver(NotificationEventType.PROJECT_ASSIGNED, targetId, email,
                () -> emailProvider.sendProjectAssigned(
                        email, name, project.getTitle(), project.getDueDate(),
                        by, dashboardUrl));

        dispatchInApp(applicantUserIdFromCandidate(intern),
                NotificationEventType.PROJECT_ASSIGNED,
                "New project assigned: " + nz(project.getTitle()),
                "Due " + project.getDueDate()
                        + (by != null ? " · assigned by " + by : "") + ".",
                INTERN_DASH + "/projects/" + targetId,
                EnumSet.of(StaffRole.TRAINER, StaffRole.EVALUATOR,
                        StaffRole.ERM, StaffRole.MANAGER),
                "Project assigned to " + nz(name),
                nz(name) + " was assigned \"" + nz(project.getTitle())
                        + "\" (due " + project.getDueDate() + ").");
    }

    /** Project submitted — event-triggered, supervisor recipient. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendProjectSubmitted(Project project) {
        if (project == null) return;
        UUID targetId = project.getId();
        Engagement eng = project.getEngagement();
        User supervisor = eng != null ? eng.getSupervisor() : null;
        // Fallback to whoever assigned it if engagement.supervisor is null.
        if (supervisor == null) supervisor = project.getAssignedBy();
        String email = supervisor != null ? supervisor.getEmail() : null;
        String name = supervisor != null ? supervisor.getFullName() : null;
        if (email == null) {
            log.warn("PROJECT_SUBMITTED skipped — no supervisor email on project {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.PROJECT_SUBMITTED, targetId)) return;

        Candidate intern = project.getIntern();
        User iu = intern != null ? intern.getUser() : null;
        String internName = iu != null ? iu.getFullName() : null;
        final String supervisorEmail = email;
        final String supervisorName = name;
        deliver(NotificationEventType.PROJECT_SUBMITTED, targetId, supervisorEmail,
                () -> emailProvider.sendProjectSubmitted(
                        supervisorEmail, supervisorName, internName,
                        project.getTitle(), supervisorDashboardUrl));

        dispatchInApp(applicantUserIdFromCandidate(intern),
                NotificationEventType.PROJECT_SUBMITTED,
                "Project submitted: " + nz(project.getTitle()),
                "Your submission was received. The reviewer will be in touch.",
                INTERN_DASH + "/projects/" + targetId,
                EnumSet.of(StaffRole.TRAINER),
                "Project ready to review: " + nz(internName),
                nz(internName) + " submitted \"" + nz(project.getTitle()) + "\".");
    }

    /** Project returned for changes — event-triggered, intern recipient. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendProjectReturned(Project project) {
        if (project == null) return;
        UUID targetId = project.getId();
        Candidate intern = project.getIntern();
        User u = intern != null ? intern.getUser() : null;
        String email = u != null ? u.getEmail() : null;
        String name = u != null ? u.getFullName() : null;
        if (email == null) return;
        if (alreadySent(NotificationEventType.PROJECT_RETURNED, targetId)) return;

        deliver(NotificationEventType.PROJECT_RETURNED, targetId, email,
                () -> emailProvider.sendProjectReturned(
                        email, name, project.getTitle(),
                        project.getReviewNotes(), dashboardUrl));

        dispatchInApp(applicantUserIdFromCandidate(intern),
                NotificationEventType.PROJECT_RETURNED,
                "Project returned: " + nz(project.getTitle()),
                "Open the project for review notes and resubmit when ready.",
                INTERN_DASH + "/projects/" + targetId,
                EnumSet.of(StaffRole.TRAINER),
                "Project returned to intern",
                "\"" + nz(project.getTitle()) + "\" was returned for changes.");
    }

    /** Project completed — event-triggered, intern recipient. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendProjectCompleted(Project project) {
        if (project == null) return;
        UUID targetId = project.getId();
        Candidate intern = project.getIntern();
        User u = intern != null ? intern.getUser() : null;
        String email = u != null ? u.getEmail() : null;
        String name = u != null ? u.getFullName() : null;
        if (email == null) return;
        if (alreadySent(NotificationEventType.PROJECT_COMPLETED, targetId)) return;

        deliver(NotificationEventType.PROJECT_COMPLETED, targetId, email,
                () -> emailProvider.sendProjectCompleted(
                        email, name, project.getTitle(), dashboardUrl));

        dispatchInApp(applicantUserIdFromCandidate(intern),
                NotificationEventType.PROJECT_COMPLETED,
                "Project completed: " + nz(project.getTitle()),
                "Congratulations — your project was marked complete.",
                INTERN_DASH + "/projects/" + targetId,
                EnumSet.of(StaffRole.TRAINER, StaffRole.EVALUATOR,
                        StaffRole.ERM, StaffRole.MANAGER),
                "Project completed: " + nz(name),
                nz(name) + " completed \"" + nz(project.getTitle()) + "\".");
    }

    /** Evaluation due — fired by the daily scheduler against DRAFT older than N days. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendEvaluationDue(Evaluation evaluation, int daysInDraft) {
        if (evaluation == null) return;
        UUID targetId = evaluation.getId();
        User supervisor = evaluation.getEvaluator();
        String email = supervisor != null ? supervisor.getEmail() : null;
        String name = supervisor != null ? supervisor.getFullName() : null;
        if (email == null) {
            log.warn("EVALUATION_DUE skipped — no evaluator email on evaluation {}", targetId);
            return;
        }
        if (alreadySent(NotificationEventType.EVALUATION_DUE, targetId)) return;

        Candidate intern = evaluation.getIntern();
        User iu = intern != null ? intern.getUser() : null;
        String internName = iu != null ? iu.getFullName() : null;
        String type = evaluation.getType() != null ? evaluation.getType().name() : null;
        final String supervisorEmail = email;
        final String supervisorName = name;
        deliver(NotificationEventType.EVALUATION_DUE, targetId, supervisorEmail,
                () -> emailProvider.sendEvaluationDue(
                        supervisorEmail, supervisorName, internName, type,
                        daysInDraft, supervisorDashboardUrl));

        // Staff-only nudge to the assigned evaluator.
        if (supervisor != null) {
            try {
                UUID internUserId = applicantUserIdFromCandidate(intern);
                userNotificationDispatcher.dispatch(supervisor.getId(),
                        NotificationEventType.EVALUATION_DUE.name(), internUserId,
                        cap("Evaluation draft pending: " + nz(internName), 200),
                        cap("Draft is " + daysInDraft + " day(s) old. Finalize when ready.", 400),
                        cap(EVALUATOR_DASH, 500), true);
            } catch (Exception e) {
                log.debug("[UserNotif] EVALUATION_DUE supervisor dispatch failed: {}",
                        e.getMessage());
            }
        }
    }

    /** Evaluation finalized — event-triggered, intern recipient. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendEvaluationFinalized(Evaluation evaluation) {
        if (evaluation == null) return;
        UUID targetId = evaluation.getId();
        Candidate intern = evaluation.getIntern();
        User iu = intern != null ? intern.getUser() : null;
        String email = iu != null ? iu.getEmail() : null;
        String name = iu != null ? iu.getFullName() : null;
        if (email == null) return;
        if (alreadySent(NotificationEventType.EVALUATION_FINALIZED, targetId)) return;

        User supervisor = evaluation.getEvaluator();
        String supervisorName = supervisor != null ? supervisor.getFullName() : null;
        String type = evaluation.getType() != null ? evaluation.getType().name() : null;
        deliver(NotificationEventType.EVALUATION_FINALIZED, targetId, email,
                () -> emailProvider.sendEvaluationFinalized(
                        email, name, type, supervisorName,
                        evaluation.getOverallRating(), dashboardUrl));

        dispatchInApp(applicantUserIdFromCandidate(intern),
                NotificationEventType.EVALUATION_FINALIZED,
                "Evaluation feedback available",
                "Your " + nz(type) + " evaluation was published"
                        + (supervisorName != null ? " by " + supervisorName : "") + ".",
                INTERN_DASH + "/evaluations/" + targetId,
                EnumSet.of(StaffRole.TRAINER, StaffRole.ERM, StaffRole.MANAGER),
                "Evaluation published: " + nz(name),
                nz(type) + " evaluation for " + nz(name) + " was published.");
    }

    /** I-983 self-review due — fired by the daily scheduler. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendI983SelfEvalDue(Evaluation evaluation) {
        if (evaluation == null) return;
        UUID targetId = evaluation.getId();
        Candidate intern = evaluation.getIntern();
        User iu = intern != null ? intern.getUser() : null;
        String email = iu != null ? iu.getEmail() : null;
        String name = iu != null ? iu.getFullName() : null;
        if (email == null) return;
        if (alreadySent(NotificationEventType.I983_SELF_EVAL_DUE, targetId)) return;

        String type = evaluation.getType() != null ? evaluation.getType().name() : null;
        // Slice-5 fold-in — template-first via I983_SELF_EVAL_DUE. Same
        // shape as sendApplicationReceived above: render the template if
        // present, fall back to the pre-migration typed hardcoded method
        // if the row is absent (fresh DB pre-seed or a manually-deleted
        // row).
        String friendlyType = type != null ? type.replace('_', ' ') : "I-983";
        deliver(NotificationEventType.I983_SELF_EVAL_DUE, targetId, email,
                () -> {
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("firstName", nz(name).split(" ")[0]);
                    vars.put("evaluationType", friendlyType);
                    vars.put("deepLink", dashboardUrl != null ? dashboardUrl : "");
                    var rendered = templateService.render(
                            "I983_SELF_EVAL_DUE", "EMAIL", vars);
                    if (rendered.isPresent()) {
                        emailProvider.sendRendered(
                                email, rendered.get().subject(), rendered.get().body());
                    } else {
                        emailProvider.sendI983SelfEvalDue(email, name, type, dashboardUrl);
                    }
                });

        dispatchInApp(applicantUserIdFromCandidate(intern),
                NotificationEventType.I983_SELF_EVAL_DUE,
                "I-983 self-evaluation due",
                "Complete your " + nz(type) + " self-evaluation in the Evaluations page.",
                INTERN_DASH + "/evaluations/" + targetId,
                null, null, null);
    }

    // ── Two-role workflow (P1b) ─────────────────────────────────────────────

    /** Intern email — technical supervisor approved their submission. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendProjectTechApproved(Project project) {
        if (project == null) return;
        UUID targetId = project.getId();
        Candidate intern = project.getIntern();
        User u = intern != null ? intern.getUser() : null;
        String email = u != null ? u.getEmail() : null;
        String name = u != null ? u.getFullName() : null;
        if (email == null) return;
        if (alreadySent(NotificationEventType.PROJECT_TECH_APPROVED, targetId)) return;

        deliver(NotificationEventType.PROJECT_TECH_APPROVED, targetId, email,
                () -> emailProvider.sendProjectTechApproved(
                        email, name, project.getTitle(), dashboardUrl));

        dispatchInApp(applicantUserIdFromCandidate(intern),
                NotificationEventType.PROJECT_TECH_APPROVED,
                "Project: technical approval granted",
                "\"" + nz(project.getTitle()) + "\" passed technical review.",
                INTERN_DASH + "/projects/" + targetId,
                EnumSet.of(StaffRole.TRAINER),
                "Project tech-approved",
                "\"" + nz(project.getTitle()) + "\" cleared technical review.");
    }

    /** Intern email — reviewer returned the project for revisions. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendProjectReturnedForRevisions(Project project, String reason) {
        if (project == null) return;
        UUID targetId = project.getId();
        Candidate intern = project.getIntern();
        User u = intern != null ? intern.getUser() : null;
        String email = u != null ? u.getEmail() : null;
        String name = u != null ? u.getFullName() : null;
        if (email == null) return;
        if (alreadySent(NotificationEventType.PROJECT_RETURNED_FOR_REVISIONS, targetId)) return;

        deliver(NotificationEventType.PROJECT_RETURNED_FOR_REVISIONS, targetId, email,
                () -> emailProvider.sendProjectReturnedForRevisions(
                        email, name, project.getTitle(), reason, dashboardUrl));

        dispatchInApp(applicantUserIdFromCandidate(intern),
                NotificationEventType.PROJECT_RETURNED_FOR_REVISIONS,
                "Project returned for revisions",
                "\"" + nz(project.getTitle()) + "\" needs changes"
                        + (reason != null && !reason.isBlank() ? ": " + reason : "") + ".",
                INTERN_DASH + "/projects/" + targetId,
                EnumSet.of(StaffRole.TRAINER),
                "Project returned for revisions",
                "\"" + nz(project.getTitle()) + "\" returned for changes.");
    }

    /** Intern email — Reporting Manager scheduled the viva. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendProjectPendingViva(Project project) {
        if (project == null) return;
        UUID targetId = project.getId();
        Candidate intern = project.getIntern();
        User u = intern != null ? intern.getUser() : null;
        String email = u != null ? u.getEmail() : null;
        String name = u != null ? u.getFullName() : null;
        if (email == null) return;
        if (alreadySent(NotificationEventType.PROJECT_PENDING_VIVA, targetId)) return;

        deliver(NotificationEventType.PROJECT_PENDING_VIVA, targetId, email,
                () -> emailProvider.sendProjectPendingViva(
                        email, name, project.getTitle(), dashboardUrl));

        dispatchInApp(applicantUserIdFromCandidate(intern),
                NotificationEventType.PROJECT_PENDING_VIVA,
                "Viva scheduled for " + nz(project.getTitle()),
                "Your Reporting Manager scheduled the viva. Watch for calendar details.",
                INTERN_DASH + "/projects/" + targetId,
                null, null, null);
    }

    /**
     * Build a deterministic UUID for "weekly" idempotency keys. The
     * sent_notifications table is keyed on (event_type, target_id) — for
     * events that fire once per (resource × recipient × period) we need a
     * synthetic target_id that encodes all three. UUIDv3 over a salted name
     * gives us a stable hash that survives restarts and runs deterministic
     * across the cluster.
     */
    public static UUID weeklyTargetId(String namespace, String... parts) {
        StringBuilder sb = new StringBuilder(namespace);
        for (String p : parts) {
            sb.append(':').append(p == null ? "" : p);
        }
        return UUID.nameUUIDFromBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Public so the WorkAuthExpiryScheduler can pick the right threshold. */
    public static NotificationEventType workAuthEventForThreshold(int days) {
        return switch (days) {
            case 90 -> NotificationEventType.WORKAUTH_EXPIRY_90;
            case 60 -> NotificationEventType.WORKAUTH_EXPIRY_60;
            case 30 -> NotificationEventType.WORKAUTH_EXPIRY_30;
            case 14 -> NotificationEventType.WORKAUTH_EXPIRY_14;
            case 7  -> NotificationEventType.WORKAUTH_EXPIRY_7;
            default -> null;
        };
    }

    // ── Internal plumbing ──────────────────────────────────────────────────

    private boolean alreadySent(NotificationEventType eventType, UUID targetId) {
        try {
            return sentNotificationRepository.existsByEventTypeAndTargetId(eventType, targetId);
        } catch (Exception e) {
            log.warn("Idempotency check failed for {}/{} — proceeding with send: {}",
                    eventType, targetId, e.getMessage());
            return false;
        }
    }

    /**
     * Execute the send, persist the idempotency row, write the audit row. All
     * failures are caught + logged; the caller's domain action is never
     * affected.
     */
    private void deliver(NotificationEventType eventType, UUID targetId, String recipient,
                         Runnable sendFn) {
        // Opt-out gate. Transactional events are always sent. For
        // reminders + engagement-updates, look up the recipient user and skip
        // if their preference flag is off. We can't resolve a user for blasts
        // sent to a comma-joined HR/Ops list — those proceed unconditionally,
        // which is correct: those are staff workflows, not user-personalised
        // streams.
        NotificationCategory category = NotificationEventCategories.categoryOf(eventType);
        if (category != NotificationCategory.TRANSACTIONAL && recipient != null
                && !recipient.contains(",")) {
            User recipientUser = userRepository.findByEmail(recipient.trim()).orElse(null);
            if (recipientUser != null && isOptedOut(recipientUser, category)) {
                log.info("Skipping {} for {} — user opted out of {}",
                        eventType, recipient, category);
                writeOptOutAudit(eventType, targetId, recipient, category);
                return;
            }
        }

        // Mail bridge Phase 2 — publish the sender role into a thread-scoped
        // context immediately before the send executes so BridgingEmailProvider
        // can stamp the right "from" address (erm@ / trainer@ / ...) on the
        // internal-mail copy when the recipient is ACTIVATED. ALWAYS cleared
        // in finally — a Tomcat-pooled thread must never carry a stale value
        // into the next request. No behaviour change here: the wrapper falls
        // back to the raw SMTP provider for every recipient who isn't
        // ACTIVATED (which is everyone today).
        NotificationSenderContext.set(NotificationSenderRoles.forEvent(eventType));
        try {
            sendFn.run();
        } catch (Exception e) {
            log.warn("Notification {} for {} ({}) failed (non-fatal): {}",
                    eventType, targetId, recipient, e.getMessage());
            return;
        } finally {
            NotificationSenderContext.clear();
        }

        // Best-effort ledger + audit. A race that double-sends still only
        // commits one ledger row (the unique constraint protects); the second
        // commit attempt is logged and swallowed.
        try {
            sentNotificationRepository.save(SentNotification.builder()
                    .eventType(eventType)
                    .targetId(targetId)
                    .recipient(recipient)
                    .build());
        } catch (DataIntegrityViolationException dive) {
            log.info("Duplicate notification ledger row for {}/{} — already recorded.",
                    eventType, targetId);
        } catch (Exception e) {
            log.warn("Failed to persist sent_notifications row for {}/{} (non-fatal): {}",
                    eventType, targetId, e.getMessage());
        }

        try {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("eventType", eventType.name());
            after.put("targetId", targetId.toString());
            after.put("recipient", recipient);
            AuditLog entry = AuditLog.builder()
                    .entityType("Notification")
                    .entityId(targetId)
                    .action("NOTIFICATION_SENT")
                    .afterJson(objectMapper.writeValueAsString(after))
                    .build();
            auditLogRepository.save(entry);
        } catch (JsonProcessingException jpe) {
            log.warn("Failed to serialize NOTIFICATION_SENT audit for {}/{}: {}",
                    eventType, targetId, jpe.getMessage());
        } catch (Exception e) {
            log.warn("Failed to write NOTIFICATION_SENT audit row for {}/{} (non-fatal): {}",
                    eventType, targetId, e.getMessage());
        }
    }

    // ── Resolvers (null-tolerant) ──────────────────────────────────────────

    private static String candidateEmail(Application application) {
        if (application == null) return null;
        Candidate c = application.getCandidate();
        if (c == null) return null;
        User u = c.getUser();
        return u != null ? u.getEmail() : null;
    }

    private static String candidateName(Application application) {
        if (application == null) return null;
        Candidate c = application.getCandidate();
        if (c == null) return null;
        User u = c.getUser();
        return u != null ? u.getFullName() : null;
    }

    private static String jobTitle(Application application) {
        if (application == null) return null;
        JobPosting jp = application.getJobPosting();
        return jp != null ? jp.getTitle() : null;
    }

    private static String entityName(Application application) {
        if (application == null) return null;
        JobPosting jp = application.getJobPosting();
        if (jp == null) return null;
        StaffingEntity ent = jp.getEntity();
        return ent != null ? ent.getName() : null;
    }

    private static String candidateEmailFromForm(I9Form form) {
        if (form == null) return null;
        Candidate c = form.getCandidate();
        if (c == null) return null;
        User u = c.getUser();
        return u != null ? u.getEmail() : null;
    }

    private static String candidateNameFromForm(I9Form form) {
        if (form == null) return null;
        Candidate c = form.getCandidate();
        if (c == null) return null;
        User u = c.getUser();
        return u != null ? u.getFullName() : null;
    }

    /**
     * Has the recipient turned off this category? Defaults to FALSE (opt-in
     * is the default) — null flag on a legacy User row means "not opted out".
     */
    private static boolean isOptedOut(User user, NotificationCategory category) {
        if (user == null || category == null) return false;
        return switch (category) {
            case REMINDERS -> Boolean.FALSE.equals(user.getPrefsReminders());
            case ENGAGEMENT_UPDATES -> Boolean.FALSE.equals(user.getPrefsEngagementUpdates());
            case TRANSACTIONAL -> false; // never opt-outable
        };
    }

    /**
     * Audit a suppression so the operator can see the user's intent reflected
     * in the same audit stream as real sends. {@code action=NOTIFICATION_SKIPPED}
     * lives alongside {@code NOTIFICATION_SENT} for a clean ledger.
     */
    private void writeOptOutAudit(NotificationEventType eventType, UUID targetId,
                                  String recipient, NotificationCategory category) {
        try {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("eventType", eventType.name());
            after.put("targetId", targetId != null ? targetId.toString() : null);
            after.put("recipient", recipient);
            after.put("reason", "opted_out");
            after.put("category", category.name());
            AuditLog entry = AuditLog.builder()
                    .entityType("Notification")
                    .entityId(targetId)
                    .action("NOTIFICATION_SKIPPED")
                    .afterJson(objectMapper.writeValueAsString(after))
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to write NOTIFICATION_SKIPPED audit (non-fatal): {}",
                    e.getMessage());
        }
    }

    // ── Phase 7 sweep: parallel in-app dispatch ─────────────────────────────
    //
    // Every email/SentNotification send above is mirrored as a UserNotification
    // row so the bell + Messages page surface the real lifecycle. Recipients
    // resolved per the doc §9 matrix; staff slots come from intern_lifecycles
    // (null slot → skip silently). Best-effort: failure here never affects the
    // email path that already ran above.

    enum StaffRole { ERM, TRAINER, EVALUATOR, MANAGER }

    private static final String INTERN_DASH = "/careers/intern";
    private static final String ERM_DASH = "/careers/erm";
    private static final String TRAINER_DASH = "/careers/trainer";
    private static final String EVALUATOR_DASH = "/careers/reporting-manager";
    private static final String MANAGER_DASH = "/careers/manager";

    /**
     * Mirror an email send as an in-app row for the applicant + each
     * resolved staff recipient. {@code staffTitle} / {@code staffBody}
     * fall back to the intern strings when null.
     */
    private void dispatchInApp(UUID applicantUserId,
                                NotificationEventType eventType,
                                String internTitle, String internBody, String internActionUrl,
                                Set<StaffRole> staffRoles,
                                String staffTitle, String staffBody) {
        if (applicantUserId != null && internTitle != null) {
            try {
                userNotificationDispatcher.dispatch(applicantUserId, eventType.name(),
                        applicantUserId,
                        cap(internTitle, 200), cap(internBody, 400),
                        cap(internActionUrl, 500), true);
            } catch (Exception e) {
                log.debug("[UserNotif] applicant dispatch failed for {}: {}",
                        eventType, e.getMessage());
            }
        }
        if (applicantUserId == null || staffRoles == null || staffRoles.isEmpty()) return;
        InternLifecycle lc;
        try {
            lc = internLifecycleRepository.findByUserId(applicantUserId).orElse(null);
        } catch (Exception e) {
            log.debug("[UserNotif] lifecycle lookup failed for {}: {}",
                    applicantUserId, e.getMessage());
            return;
        }
        if (lc == null) {
            log.debug("[UserNotif] no InternLifecycle for {} — staff dispatches skipped "
                    + "(pre-OFFER_SIGNED)", applicantUserId);
            return;
        }
        String sTitle = staffTitle != null ? staffTitle : internTitle;
        String sBody = staffBody != null ? staffBody : internBody;
        for (StaffRole r : staffRoles) {
            UUID staffId = switch (r) {
                case ERM -> lc.getErmId();
                case TRAINER -> lc.getTrainerId();
                case EVALUATOR -> lc.getEvaluatorId();
                case MANAGER -> lc.getManagerId();
            };
            if (staffId == null) {
                log.debug("[UserNotif] {} slot null on lifecycle {} for event {} — skip",
                        r, lc.getId(), eventType);
                continue;
            }
            try {
                userNotificationDispatcher.dispatch(staffId, eventType.name(), applicantUserId,
                        cap(sTitle, 200), cap(sBody, 400),
                        cap(staffActionUrl(r), 500), true);
            } catch (Exception e) {
                log.debug("[UserNotif] {} dispatch failed for {}: {}",
                        r, eventType, e.getMessage());
            }
        }
    }

    private static String staffActionUrl(StaffRole r) {
        return switch (r) {
            case ERM -> ERM_DASH;
            case TRAINER -> TRAINER_DASH;
            case EVALUATOR -> EVALUATOR_DASH;
            case MANAGER -> MANAGER_DASH;
        };
    }

    private static String cap(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    private static UUID applicantUserId(Application application) {
        if (application == null) return null;
        Candidate c = application.getCandidate();
        if (c == null) return null;
        User u = c.getUser();
        return u != null ? u.getId() : null;
    }

    private static UUID applicantUserIdFromForm(I9Form form) {
        if (form == null) return null;
        Candidate c = form.getCandidate();
        if (c == null) return null;
        User u = c.getUser();
        return u != null ? u.getId() : null;
    }

    private static UUID applicantUserIdFromCandidate(Candidate c) {
        if (c == null) return null;
        User u = c.getUser();
        return u != null ? u.getId() : null;
    }

    /**
     * Active recipients with the HR role. Used for the HR-targeted
     * batch-2 sends (I-9 §2 pending, I-983 ready). Filters out blanks/null
     * emails so a misconfigured user doesn't break the send.
     */
    private List<String> hrComplianceEmails() {
        try {
            return userRepository.findByRole(UserRole.ERM).stream()
                    .filter(u -> u != null && Boolean.TRUE.equals(u.getActive()))
                    .map(User::getEmail)
                    .filter(e -> e != null && !e.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.warn("HR recipient lookup failed (non-fatal): {}", e.getMessage());
            return List.of();
        }
    }
}
