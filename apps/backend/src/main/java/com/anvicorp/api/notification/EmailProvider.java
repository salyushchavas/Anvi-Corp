package com.anvicorp.api.notification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Single seam for outbound transactional email. Two implementations:
 *
 * <ul>
 *   <li>{@link SmtpEmailProvider} — real send via Spring's JavaMailSender.
 *       Active when {@code spring.mail.host} + {@code spring.mail.username}
 *       are configured. Throws {@link EmailDeliveryException} on failure.</li>
 *   <li>{@link LogEmailProvider} — fallback. Logs at INFO and returns. Used
 *       in dev / CI / local where no SMTP is configured.</li>
 * </ul>
 *
 * Every method renders into the shared branded template (see
 * {@code SmtpEmailProvider#wrapHtml}) so all outgoing mail looks identical
 * to the verification email.
 */
public interface EmailProvider {

    /**
     * ERM Phase 2 — generic send seam for pre-rendered template bodies.
     * Subject + body come from {@link com.anvicorp.api.erm.CommunicationTemplateService}
     * after variable substitution. SMTP implementation wraps the body in the
     * shared branded template; log implementation just records.
     *
     * <p>The body is treated as plain text and HTML-escaped before injection
     * into the branded wrapper — pass HTML markup only via
     * {@link #sendBrandedHtml(String, String, String, String)}.</p>
     */
    void sendRendered(String email, String subject, String body);

    /**
     * Generic send seam for pre-built HTML bodies (e.g. the admin staff
     * activation invite, which has interactive markup the
     * {@link #sendRendered} escape path mangles). The caller is
     * responsible for HTML-escaping any user-supplied text it splices
     * into the body. {@code plainBody} is the text/plain alternative
     * for clients that can't render HTML — pass a sensible fallback.
     */
    void sendBrandedHtml(String email, String subject, String plainBody, String htmlBody);

    // ── Auth flow (existing) ────────────────────────────────────────────────

    void sendVerificationCode(String email, String code, Instant expiresAt);

    void sendApplicantIdIssued(String email, String applicantId);

    void sendPasswordReset(String email, String resetUrl, Instant expiresAt);

    void sendConditionalSelectionConfirmation(String email,
                                              String jobPostingTitle,
                                              String entityName);

    // ── Batch 1 — applicant lifecycle ───────────────────────────────────────

    /** Confirmation to applicant immediately after submitting an application. */
    void sendApplicationReceived(String email,
                                 String candidateName,
                                 String jobTitle,
                                 String entityName);

    /** Status flipped to SHORTLISTED. */
    void sendApplicationShortlisted(String email,
                                    String candidateName,
                                    String jobTitle,
                                    String entityName);

    /** Status flipped to REJECTED. Polite copy. */
    void sendApplicationRejected(String email,
                                 String candidateName,
                                 String jobTitle,
                                 String entityName);

    /** A new Interview row exists — applicant gets details. */
    void sendInterviewScheduled(String email,
                                String candidateName,
                                String jobTitle,
                                String entityName,
                                Instant scheduledAt,
                                Integer durationMinutes,
                                String interviewType,
                                String interviewerName,
                                String meetingUrl,
                                String candidateNotes);

    /** 24h before an interview. Same fields as scheduled, different framing. */
    void sendInterviewReminder(String email,
                               String candidateName,
                               String jobTitle,
                               String entityName,
                               Instant scheduledAt,
                               Integer durationMinutes,
                               String interviewType,
                               String interviewerName,
                               String meetingUrl);

    /** Offer extended — applicant. */
    void sendOfferExtended(String email,
                           String candidateName,
                           String jobTitle,
                           String entityName,
                           BigDecimal compensationAmount,
                           String compensationCurrency,
                           String compensationFrequency,
                           LocalDate startDate,
                           Instant expiresAt,
                           String viewOfferUrl);

    /** Offer accepted — applicant confirmation. */
    void sendOfferAccepted(String email,
                           String candidateName,
                           String jobTitle,
                           String entityName,
                           LocalDate startDate);

    /** Offer accepted — Operations notification (different recipient + framing). */
    void sendOfferAcceptedToOps(String opsEmail,
                                String candidateName,
                                String candidateEmail,
                                String jobTitle,
                                String entityName,
                                LocalDate startDate);

    /** Engagement flipped to ACTIVE — welcome the new intern. */
    void sendOnboardingWelcome(String email,
                               String internName,
                               String jobTitle,
                               String entityName,
                               LocalDate startDate,
                               String dashboardUrl);

    // ── Batch 2 — compliance / onboarding ───────────────────────────────────
    // PII RULE: every method here takes ONLY status + names + URLs. No SSN,
    // no document numbers, no DOB, no addresses, no decrypted PII of any kind.
    // The email body always points the user back to the dashboard to view the
    // actual data.

    /** Intern reminder to complete I-9 Section 1. */
    void sendI9Section1Reminder(String email,
                                String internName,
                                LocalDate section1DueDate,
                                String dashboardUrl);

    /** HR notification that an intern just completed §1 and §2 is due. */
    void sendI9Section2Pending(String hrEmail,
                               String internName,
                               LocalDate section2DueDate,
                               String hrDashboardUrl);

    /** Intern (STEM OPT only) — fill in the I-983 training plan. */
    void sendI983PlanNeeded(String email,
                            String internName,
                            String dashboardUrl);

    /** HR — intern has signed the I-983; ready for employer signature. */
    void sendI983PlanReady(String hrEmail,
                           String internName,
                           String hrDashboardUrl);

    /** Intern — E-Verify case has been opened (status: OPEN). */
    void sendEVerifyCaseOpened(String email,
                               String internName,
                               String dashboardUrl);

    /** Intern — URGENT: Tentative Nonconfirmation requires action. */
    void sendEVerifyTncAlert(String email,
                             String internName,
                             String dashboardUrl);

    /** Intern + HR — favorable close (Employment Authorized or Closed favorably). */
    void sendEVerifyCleared(String email,
                            String internName,
                            String dashboardUrl);

    /**
     * Work-authorization expiry reminder. {@code daysUntilExpiry} is the
     * threshold (90 / 60 / 30 / 14 / 7), used to phrase urgency. The actual
     * expiry date is included as a date — NOT the document number or any PII.
     */
    void sendWorkAuthExpiryReminder(String email,
                                    String internName,
                                    int daysUntilExpiry,
                                    LocalDate expirationDate,
                                    String authType,
                                    String dashboardUrl);

    /** Generic compliance-task reminder. {@code taskTitle} is a non-PII label. */
    void sendComplianceTaskReminder(String email,
                                    String internName,
                                    String taskTitle,
                                    LocalDate dueDate,
                                    Integer daysOverdue,
                                    String dashboardUrl);

    // ── Batch 3 — intern weekly cycle ───────────────────────────────────────
    // WeeklyMaterial release + unread-reminder removed in Trainer Phase 0
    // (concept not in Trainer doc spec).

    /** Intern — end-of-week reminder to submit this week's report. */
    void sendWeeklyReportDue(String email,
                             String internName,
                             java.time.LocalDate weekStart,
                             String dashboardUrl);

    /** Intern — supervisor returned a report for corrections. */
    void sendWeeklyReportReturned(String email,
                                  String internName,
                                  java.time.LocalDate weekStart,
                                  String reviewNotes,
                                  String dashboardUrl);

    /** Intern — supervisor approved a report. */
    void sendWeeklyReportApproved(String email,
                                  String internName,
                                  java.time.LocalDate weekStart,
                                  String dashboardUrl);

    /** Intern — end-of-week reminder to log hours. */
    void sendTimesheetDue(String email,
                          String internName,
                          java.time.LocalDate weekStart,
                          String dashboardUrl);

    /** Intern — supervisor allocated a new project. */
    void sendProjectAssigned(String email,
                             String internName,
                             String projectTitle,
                             java.time.LocalDate dueDate,
                             String supervisorName,
                             String dashboardUrl);

    /** Supervisor — intern submitted a project for review. */
    void sendProjectSubmitted(String supervisorEmail,
                              String supervisorName,
                              String internName,
                              String projectTitle,
                              String supervisorDashboardUrl);

    /** Intern — supervisor returned a project for changes. */
    void sendProjectReturned(String email,
                             String internName,
                             String projectTitle,
                             String reviewNotes,
                             String dashboardUrl);

    /** Intern — supervisor marked project complete. */
    void sendProjectCompleted(String email,
                              String internName,
                              String projectTitle,
                              String dashboardUrl);

    /** Supervisor — DRAFT evaluation is still pending finalization. */
    void sendEvaluationDue(String supervisorEmail,
                           String supervisorName,
                           String internName,
                           String evaluationType,
                           Integer daysInDraft,
                           String supervisorDashboardUrl);

    /** Intern — supervisor finalized an evaluation; visible now. */
    void sendEvaluationFinalized(String email,
                                 String internName,
                                 String evaluationType,
                                 String supervisorName,
                                 Integer overallRating,
                                 String dashboardUrl);

    /** Intern — DRAFT I-983 self-review is awaiting their reflection. */
    void sendI983SelfEvalDue(String email,
                             String internName,
                             String evaluationType,
                             String dashboardUrl);

    // ── Two-role workflow (P1b) ─────────────────────────────────────────────

    /** Intern — technical supervisor approved their submission. */
    void sendProjectTechApproved(String email,
                                 String internName,
                                 String projectTitle,
                                 String dashboardUrl);

    /** Intern — a reviewer returned the project for revisions, with their note. */
    void sendProjectReturnedForRevisions(String email,
                                         String internName,
                                         String projectTitle,
                                         String reason,
                                         String dashboardUrl);

    /** Intern — Reporting Manager scheduled the viva. */
    void sendProjectPendingViva(String email,
                                String internName,
                                String projectTitle,
                                String dashboardUrl);
}
