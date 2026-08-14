package com.anvicorp.api.erm;

import com.anvicorp.api.config.BrandConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Idempotent boot-time seeder. Inserts a starter set of email templates
 * the ERM phases reference. Existing rows (matched by
 * (key, channel)) are left untouched so ERM edits made via the Phase 7
 * settings UI are never overwritten.
 */
@Component
@Order(10)
@RequiredArgsConstructor
@Slf4j
public class CommunicationTemplateSeeder implements CommandLineRunner {

    private final CommunicationTemplateRepository repository;
    private final BrandConfig brand;

    public record Seed(
            String key, String channel, String subject, String body, String vars
    ) {}

    /** ERM Phase 7 — exposed so the Settings "Restore default" flow can
     *  fetch the originally-seeded values for a given (key, channel).
     *  Seed strings are brand-interpolated before returning so a per-brand
     *  deploy sees its own brand name in the restore-default payload. */
    public java.util.Optional<Seed> findSeed(String key, String channel) {
        if (key == null || channel == null) return java.util.Optional.empty();
        return SEEDS.stream()
                .filter(s -> key.equals(s.key()) && channel.equals(s.channel()))
                .findFirst()
                .map(this::brandify);
    }

    /**
     * DEFENSE-IN-DEPTH only. Phase-0 Batch-7 converted the source
     * {@link #SEEDS} list from hardcoded brand tokens (literal
     * {@code "Anvi Corp"}, {@code "— Anvi Corp ERM"}, etc.) directly to
     * the placeholder shape ({@code {{brandName}}} / {@code {{signoffBlock}}}
     * / {@code {{brandName}} ERM}), so {@link #brandify} is a no-op on
     * the current seed content — there are no literal tokens left to
     * rewrite. The method and its literal find-patterns are preserved
     * because {@link #rebrandifyExistingRowsIfLiteralBrandLeaks} still
     * calls {@link #rewrite} to migrate pre-Wave-1 DB rows on any old
     * cluster that never ran the migration once. Dropping the literal
     * patterns would strand those rows in their legacy shape.
     *
     * <p>Order matters — the longest / most-specific tokens go first so
     * the shorter tokens don't accidentally match the longer ones'
     * prefixes. Signoff patterns run before the bare brand-name
     * substitution so the three signoff shapes collapse to a single
     * {@code {{signoffBlock}}} placeholder.</p>
     *
     * <p>{@code "Skyzen ERM"} is legacy pre-rebrand text that a fresh
     * DB will never see; kept for defence-in-depth. {@code — {{ermName}}}
     * collapses to {@code {{signoffBlock}}} too (the render-time
     * resolver detects {@code vars.ermName} and prefers the personalised
     * line — behaviour is preserved).</p>
     */
    private Seed brandify(Seed s) {
        return new Seed(
                s.key(), s.channel(),
                rewrite(s.subject()),
                rewrite(s.body()),
                s.vars());
    }

    private String rewrite(String v) {
        if (v == null) return null;
        return v
                // ── SIGNOFF PATTERNS (longest first) ──────────────────
                // Three co-existing shapes collapse to a single
                // {{signoffBlock}} placeholder. The render-time resolver
                // computes the actual line using ermName-if-present or
                // brand.getName() otherwise (see BrandConfig.signoffBlock).
                .replace("— Anvi Corp ERM", "{{signoffBlock}}")
                .replace("— Skyzen ERM", "{{signoffBlock}}")
                .replace("— {{ermName}}", "{{signoffBlock}}")
                .replace("— Anvi Corp", "{{signoffBlock}}")
                // ── INLINE BRAND REFERENCES ───────────────────────────
                // "Anvi Corp ERM" inside a sentence (not a signoff)
                // rewrites to the ERM composite form so a per-brand
                // deploy renders correctly. Longer-first ordering.
                .replace("Anvi Corp ERM", "{{brandName}} ERM")
                .replace("Skyzen ERM", "{{brandName}} ERM")
                // Bare "Anvi Corp" (subject lines + body copy).
                .replace("Anvi Corp", "{{brandName}}");
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed(
                    "APPLICATION_REJECT", "EMAIL",
                    "Update on your {{brandName}} application",
                    "Hello {{firstName}},\n\n"
                            + "Thank you for applying to {{jobTitle}} at {{brandName}}. After careful "
                            + "review, we have decided not to proceed with your application at "
                            + "this time. We appreciate your interest and wish you the best.\n\n"
                            + "If you'd like feedback or have questions, reach out to "
                            + "{{supportEmail}}. We keep candidate records for 6 months and "
                            + "welcome a fresh application after that window — new roles are "
                            + "posted regularly, so watch our openings page.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,jobTitle"),
            new Seed(
                    "APPLICATION_HOLD", "EMAIL",
                    "Your {{brandName}} application — under review",
                    "Hello {{firstName}},\n\n"
                            + "Thank you for applying to {{jobTitle}}. Your application is "
                            + "currently under extended review. We will reach out when we have "
                            + "an update.\n\n{{signoffBlock}}",
                    "firstName,jobTitle"),
            new Seed(
                    "APPLICATION_REQUEST_INFO", "EMAIL",
                    "{{brandName}} application — additional information needed",
                    "Hello {{firstName}},\n\n"
                            + "We are reviewing your application for {{jobTitle}} and need the "
                            + "following information: {{infoRequested}}.\n\n"
                            + "Please update your application in your {{brandName}} dashboard.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,jobTitle,infoRequested"),
            // Intern Email Wave 1 — #11. ERM shortlists an application →
            // the applicant gets both an in-app dispatch AND this email so
            // they know their application progressed. Previously ONLY
            // managers were notified (in-app); the applicant heard nothing.
            new Seed(
                    "APPLICATION_SHORTLIST", "EMAIL",
                    "Your {{brandName}} application has been shortlisted",
                    "Hello {{firstName}},\n\n"
                            + "Great news — your application for {{jobTitle}} has been "
                            + "shortlisted for the next stage. A recruiter will reach out to "
                            + "schedule your interview shortly.\n\n"
                            + "You can track the status of your application at any time in "
                            + "your {{brandName}} dashboard: {{dashboardUrl}}\n\n"
                            + "Questions in the meantime? Reach out to {{supportEmail}}.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,jobTitle,dashboardUrl"),
            // Intern Email Wave 1 — #13 is already covered by the
            // existing INTERVIEW_SELECTED template below, which
            // InterviewEmailListener.onManagerHireDecision → sendDecision
            // fires the moment ManagerHireApprovalService.decide flips
            // iv.decision = "SELECTED" (APPROVED path). The current
            // copy already prompts the intern to "click Receive my offer
            // letter", satisfying the ack-to-receive-letter intent. No
            // new template needed — see the report for the exact path.
            // Intern Email Wave 1 — #19. Once an offer is finalized
            // (ERM executes the countersigned PDF), the intern gets
            // this "your executed offer letter is ready" email with a
            // secure dashboard link to download the PDF. The download
            // route goes through the same authenticated + decrypting
            // endpoint the in-dashboard button uses; a bare S3 link
            // would leak the encrypted envelope (see
            // DocumentInstanceService.readFinalPdfBytes).
            new Seed(
                    "OFFER_LETTER_EXECUTED", "EMAIL",
                    "Your {{brandName}} offer letter has been executed",
                    "Hello {{firstName}},\n\n"
                            + "Your {{brandName}} offer letter for {{jobTitle}} has been "
                            + "countersigned and executed. Welcome aboard!\n\n"
                            + "Download the fully-signed PDF from your dashboard:\n"
                            + "{{pdfDownloadUrl}}\n\n"
                            + "Keep a copy for your records — the same PDF is stored in "
                            + "your {{brandName}} dashboard and can be re-downloaded any time "
                            + "under Documents.\n\n"
                            + "Your onboarding lead will reach out with next steps.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,jobTitle,pdfDownloadUrl"),
            // Intern Email Wave 1 — #8 is already fully implemented by
            // ProfileNotificationService.maybeFireSubmissionAck, called
            // from UserProfileService.updateProfile after every save.
            // That path fires exactly once per intern (guarded by
            // Candidate.profileSubmittedAt), emails every active ERM
            // with the full intern details (Name, Email, Contact,
            // Work Auth, Skillset, Submission date, Full Address) via
            // ProfileNotificationService.buildSubmissionBody, AND drops
            // an in-app row per ERM via UserNotificationDispatcher.
            // Brand-neutral copy — no rebrand risk. No new template
            // needed; documented here so the audit trail is obvious.
            new Seed(
                    "INTERVIEW_SELECTED", "EMAIL",
                    "Great news from your {{brandName}} interview",
                    "Hello {{firstName}},\n\n"
                            + "We enjoyed speaking with you and would like to move forward. "
                            + "Your offer letter will land in your {{brandName}} dashboard within "
                            + "the next 2 business days — you'll get a separate email the "
                            + "moment it's ready with a direct link to review and sign.\n\n"
                            + "In the meantime, sign in to see your candidate timeline: "
                            + "{{dashboardUrl}}\n\n"
                            + "Questions? Reach out to {{supportEmail}}.\n\n{{signoffBlock}}",
                    "firstName,dashboardUrl"),
            new Seed(
                    "INTERVIEW_HOLD", "EMAIL",
                    "{{brandName}} interview — under consideration",
                    "Hello {{firstName}},\n\n"
                            + "Thank you for interviewing with us. We are still in the decision "
                            + "phase — expect to hear back within the next 5 business days. If "
                            + "you don't hear from us by then, reach out to {{supportEmail}} "
                            + "and we'll follow up.\n\n{{signoffBlock}}",
                    "firstName"),
            new Seed(
                    "INTERVIEW_REJECTED", "EMAIL",
                    "{{brandName}} interview decision",
                    "Hello {{firstName}},\n\n"
                            + "Thank you for interviewing for {{jobTitle}}. After careful "
                            + "consideration, we have decided not to proceed at this time. We "
                            + "appreciate your time and interest.\n\n"
                            + "If you'd like specific feedback on the interview or have any "
                            + "questions, please email {{supportEmail}} — we're happy to share "
                            + "what we can.\n\n{{signoffBlock}}",
                    "firstName,jobTitle"),
            new Seed(
                    "OFFER_DOC_REJECTED", "EMAIL",
                    "Onboarding document needs correction — {{documentName}}",
                    "Hello {{firstName}},\n\n"
                            + "The {{documentName}} you submitted needs correction: "
                            + "{{ermComments}}. Please update and resubmit from your "
                            + "dashboard.\n\n{{signoffBlock}}",
                    "firstName,documentName,ermComments"),
            new Seed(
                    "EXIT_COMPLETED", "EMAIL",
                    "Your {{brandName}} internship has concluded",
                    "Hello {{firstName}},\n\n"
                            + "Your internship at {{brandName}} concluded on {{exitDate}}. Thank you "
                            + "for your contributions. Your records remain accessible in your "
                            + "dashboard. Please share your exit feedback when you have a "
                            + "moment.\n\n{{signoffBlock}}",
                    "firstName,exitDate"),
            new Seed(
                    "EXIT_TERMINATED", "EMAIL",
                    "Your {{brandName}} employment status",
                    "Hello {{firstName}},\n\n"
                            + "This message confirms that your engagement with {{brandName}} ended "
                            + "on {{exitDate}}. You will receive separate communications about "
                            + "next steps from your ERM. Records remain accessible in your "
                            + "dashboard.\n\n{{signoffBlock}}",
                    "firstName,exitDate"),
            new Seed(
                    "EXIT_RESIGNED", "EMAIL",
                    "Confirmation of your resignation",
                    "Hello {{firstName}},\n\n"
                            + "This confirms your resignation from {{brandName}} effective "
                            + "{{exitDate}}. Thank you for your contributions. Records remain "
                            + "accessible in your dashboard; please share your exit feedback "
                            + "when you have a moment.\n\n{{signoffBlock}}",
                    "firstName,exitDate"),
            // ── ERM Phase 3 — interview lifecycle templates ─────────────────
            new Seed(
                    "INTERVIEW_SCHEDULED", "EMAIL",
                    "Your {{brandName}} interview is scheduled for {{scheduledForLocal}}",
                    "Hello {{firstName}},\n\n"
                            + "Your interview for {{jobTitle}} is scheduled for "
                            + "{{scheduledForLocal}} {{timezone}}.\n\n"
                            + "Join via this link: {{zoomJoinUrl}}\n"
                            + "Interviewer: {{interviewerName}}\n\n"
                            + "Prep: {{prepInstructions}}\n\n"
                            + "Reply to this email if you need to reschedule.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,jobTitle,scheduledForLocal,timezone,zoomJoinUrl,"
                            + "interviewerName,prepInstructions"),
            new Seed(
                    "INTERVIEW_RESCHEDULED", "EMAIL",
                    "Your {{brandName}} interview has been rescheduled",
                    "Hello {{firstName}},\n\n"
                            + "Your interview for {{jobTitle}} has been rescheduled to "
                            + "{{newScheduledForLocal}} {{timezone}}.\n\n"
                            + "Reason: {{rescheduleReasonHuman}}\n"
                            + "Updated link: {{zoomJoinUrl}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,jobTitle,newScheduledForLocal,timezone,"
                            + "rescheduleReasonHuman,zoomJoinUrl"),
            new Seed(
                    "INTERVIEW_CANCELLED", "EMAIL",
                    "Your {{brandName}} interview has been cancelled",
                    "Hello {{firstName}},\n\n"
                            + "Your interview for {{jobTitle}} has been cancelled.\n\n"
                            + "{{cancellationMessage}}\n\n"
                            + "We will follow up shortly with next steps.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,jobTitle,cancellationMessage"),
            // ── ERM Phase 4 — offer + new-hire templates ────────────────────
            new Seed(
                    "OFFER_LETTER", "EMAIL",
                    "Your offer from {{brandName}} — {{roleTitle}}",
                    "Hello {{firstName}},\n\n"
                            + "We're delighted to extend an offer for the {{roleTitle}} role at "
                            + "{{brandName}}.\n\n"
                            + "Tentative start: {{tentativeStartDate}}\n"
                            + "Compensation: {{compensationSummary}}\n"
                            + "Worksite: {{worksite}}\n"
                            + "Expected hours: {{expectedHoursPerWeek}}/week\n\n"
                            + "{{contingencies}}\n\n"
                            + "Please review and sign your offer within {{expiryDays}} days. "
                            + "The link below opens the signing page directly on your {{brandName}} "
                            + "dashboard — there's no separate signing email or third-party "
                            + "tool to install.\n\n"
                            + "{{signingLink}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,roleTitle,tentativeStartDate,compensationSummary,"
                            + "worksite,expectedHoursPerWeek,contingencies,expiryDays,"
                            + "ermName,signingLink"),
            new Seed(
                    "OFFER_REMINDER", "EMAIL",
                    "Reminder: your {{brandName}} offer is awaiting signature",
                    "Hello {{firstName}},\n\n"
                            + "This is a reminder that your offer for {{roleTitle}} is awaiting "
                            + "your signature. The offer expires on {{expiryDate}}.\n\n"
                            + "Open the link below to review and sign directly on your {{brandName}} "
                            + "dashboard (no separate signing email to look for).\n\n"
                            + "{{signingLink}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,roleTitle,expiryDate,ermName,signingLink"),
            new Seed(
                    "OFFER_VOIDED", "EMAIL",
                    "Your {{brandName}} offer has been withdrawn",
                    "Hello {{firstName}},\n\n"
                            + "We regret to inform you that the offer extended to you for "
                            + "{{roleTitle}} has been withdrawn.\n\n"
                            + "{{voidReasonHuman}}\n\n"
                            + "If you have questions, please reach out.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,roleTitle,voidReasonHuman,ermName"),
            new Seed(
                    "REPORTING_STRUCTURE_ASSIGNED", "EMAIL",
                    "You've been assigned to a new intern at {{brandName}}",
                    "Hello {{recipientFirstName}},\n\n"
                            + "You've been assigned as the {{role}} for {{internName}} "
                            + "({{employeeId}}). Internship starts {{tentativeStartDate}}.\n\n"
                            + "Open your dashboard to view their profile and prepare for "
                            + "onboarding.\n\n{{signoffBlock}}",
                    "recipientFirstName,role,internName,employeeId,tentativeStartDate"),
            new Seed(
                    "START_DATE_UPDATED", "EMAIL",
                    "Your {{brandName}} start date has been updated",
                    "Hello {{firstName}},\n\n"
                            + "Your tentative start date is now {{newDate}}. Please update "
                            + "your calendar.\n\n{{signoffBlock}}",
                    "firstName,newDate,ermName"),
            // ── ERM Phase 5 — onboarding review + compliance templates ───────
            new Seed(
                    "ONBOARDING_ITEM_ACCEPTED", "EMAIL",
                    "Your {{documentName}} has been accepted",
                    "Hello {{firstName}},\n\n"
                            + "Your {{documentName}} submission has been reviewed and "
                            + "accepted. No further action is needed for this item.\n\n"
                            + "Open your dashboard to track the remaining onboarding "
                            + "documents.\n\n{{signoffBlock}}",
                    "firstName,documentName"),
            new Seed(
                    "ONBOARDING_ITEM_REJECTED", "EMAIL",
                    "Action needed: {{documentName}} requires correction",
                    "Hello {{firstName}},\n\n"
                            + "Your {{documentName}} submission needs correction before it "
                            + "can be accepted.\n\n"
                            + "Reviewer comments: {{ermComments}}\n\n"
                            + "Please update and resubmit from your dashboard.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,documentName,ermComments,ermName"),
            new Seed(
                    "ONBOARDING_ITEM_RESEND", "EMAIL",
                    "Please resend: {{documentName}}",
                    "Hello {{firstName}},\n\n"
                            + "We need a fresh copy of your {{documentName}}.\n\n"
                            + "Reason: {{ermComments}}\n\n"
                            + "Please re-upload from your dashboard at your earliest "
                            + "convenience.\n\n{{signoffBlock}}",
                    "firstName,documentName,ermComments,ermName"),
            new Seed(
                    "ONBOARDING_PACKET_ACCEPTED", "EMAIL",
                    "Your {{brandName}} onboarding packet is complete",
                    "Hello {{firstName}},\n\n"
                            + "All required onboarding documents have been accepted. "
                            + "Welcome aboard — your first day is {{firstDayOfEmployment}}.\n\n"
                            + "Your reporting team will reach out with project details "
                            + "shortly.\n\n{{signoffBlock}}",
                    "firstName,firstDayOfEmployment"),
            new Seed(
                    "EVERIFY_CASE_OPENED", "EMAIL",
                    "Your E-Verify case has been opened",
                    "Hello {{firstName}},\n\n"
                            + "A federal E-Verify case has been opened to confirm your "
                            + "employment eligibility. No action is required from you at "
                            + "this time — we will contact you only if additional "
                            + "verification is needed.\n\n{{signoffBlock}}",
                    "firstName"),
            new Seed(
                    "EVERIFY_TENTATIVE_NONCONFIRMATION", "EMAIL",
                    "Action needed: your E-Verify case requires follow-up",
                    "Hello {{firstName}},\n\n"
                            + "Your E-Verify case returned a Tentative Nonconfirmation "
                            + "(TNC). This often resolves quickly once we exchange "
                            + "additional information.\n\n"
                            + "Please reach out to {{ermName}} within 10 federal "
                            + "business days to decide whether to contest. We will guide "
                            + "you through the next steps.\n\n{{signoffBlock}}",
                    "firstName,ermName"),
            new Seed(
                    "EVERIFY_AUTHORIZED", "EMAIL",
                    "Your E-Verify case is closed — employment authorized",
                    "Hello {{firstName}},\n\n"
                            + "Your E-Verify case has been closed with Employment "
                            + "Authorized. No further action is needed.\n\n{{signoffBlock}}",
                    "firstName"),
            new Seed(
                    "WORK_AUTH_EXPIRING", "EMAIL",
                    "Your work authorization expires on {{expirationDate}}",
                    "Hello {{firstName}},\n\n"
                            + "Our records show that your {{workAuthType}} expires on "
                            + "{{expirationDate}} ({{daysUntilExpiration}} days away).\n\n"
                            + "Please connect with {{ermName}} as soon as possible to "
                            + "share updated documentation or discuss extension "
                            + "options.\n\n{{signoffBlock}}",
                    "firstName,workAuthType,expirationDate,daysUntilExpiration,ermName"),
            // Parity-gap fill — ERM-facing counterpart for the work-auth
            // expiring alert. The intern already gets WORK_AUTH_EXPIRING
            // via ComplianceLifecycleListener; the ERM CC leg only wrote an
            // in-app row (WORK_AUTH_EXPIRING is not in the dispatcher's
            // auto-email allowlist AND the auto-hook couldn't carry the
            // real context anyway — it composes plaintext from the row's
            // title/body only). This seed lets us render a proper, context-
            // rich alert directly to the ERM: which intern, which
            // work-auth type (CPT/OPT/H1B/…), the expiry date, days
            // remaining, and the review action.
            new Seed(
                    "WORK_AUTH_EXPIRING_ERM", "EMAIL",
                    "Action needed: {{internName}}'s {{workAuthType}} expires "
                            + "in {{daysUntilExpiration}} days",
                    "Hello {{ermName}},\n\n"
                            + "{{internName}}'s work authorization is approaching "
                            + "expiry:\n\n"
                            + " · Intern: {{internName}}\n"
                            + " · Work authorization: {{workAuthType}}\n"
                            + " · Expires: {{expirationDate}}\n"
                            + " · Days remaining: {{daysUntilExpiration}}\n\n"
                            + "Please review the compliance record and follow up with "
                            + "the intern to collect updated documentation or discuss "
                            + "extension options before the deadline:\n"
                            + "{{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "ermName,internName,workAuthType,expirationDate,"
                            + "daysUntilExpiration,deepLink"),
            // Parity-gap fill — intern-facing "your IDMS document was
            // returned for corrections" alert. Distinct from the packet-
            // level DOCUMENT_TASK_REJECTED (which covers document-packet
            // task reviews and already double-emails via
            // DocumentEmailListener); this covers the IDMS-native flow
            // where an ERM returns a signed / submitted document instance
            // via DocumentInstanceService.returnForCorrections. Was
            // dispatched with emailSent=false + not in the allowlist, so
            // the intern only saw an in-app row and could miss a time-
            // sensitive corrections deadline.
            new Seed(
                    "IDMS_DOC_RETURNED", "EMAIL",
                    "Please make corrections to \"{{templateTitle}}\"",
                    "Hello {{firstName}},\n\n"
                            + "{{ermName}}, your ERM, has returned "
                            + "\"{{templateTitle}}\" and asked for some corrections "
                            + "before it can be accepted."
                            + "{{reasonBlock}}"
                            + "{{commentsBlock}}"
                            + "\n\nOpen the document to review the feedback, apply "
                            + "the corrections, and re-submit:\n{{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,ermName,templateTitle,reasonBlock,"
                            + "commentsBlock,deepLink"),
            // ── Trainer Phase 0 — doc §10 + §8 notification matrix (7 templates).
            new Seed(
                    "PROJECT_ASSIGNED", "EMAIL",
                    "New project assigned: {{projectTitle}}",
                    "Hello {{firstName}},\n\n"
                            + "Your trainer {{trainerName}} has assigned a new project: "
                            + "{{projectTitle}} ({{technologyArea}}).\n\n"
                            + "Due: {{dueDateLocal}}\n\n"
                            + "Open your dashboard to view instructions, attached files, "
                            + "and GitHub setup:\n{{deepLink}}\n\n{{signoffBlock}}",
                    "firstName,trainerName,projectTitle,technologyArea,dueDateLocal,deepLink"),
            // Trainer Phase 2 — staff-side variant used when the trainer
            // flips on "notify stakeholders" so Evaluator / Manager / ERM
            // receive a different framing than the intern.
            new Seed(
                    "PROJECT_ASSIGNED_STAKEHOLDER", "EMAIL",
                    "Project assigned: {{internName}} — {{projectTitle}}",
                    "Hello {{firstName}},\n\n"
                            + "Trainer {{trainerName}} has assigned a project to "
                            + "{{internName}}:\n\n"
                            + "  · {{projectTitle}} ({{technologyArea}})\n"
                            + "  · Due {{dueDateLocal}}\n"
                            + "  · Slot: Project {{projectNumber}} for {{monthYear}}\n\n"
                            + "Open the project to review instructions and attached "
                            + "files:\n{{deepLink}}\n\n{{signoffBlock}}",
                    "firstName,trainerName,internName,projectTitle,technologyArea,"
                            + "dueDateLocal,projectNumber,monthYear,deepLink"),
            new Seed(
                    "WEEKLY_MEETING_SCHEDULED", "EMAIL",
                    "Weekly meeting scheduled: {{meetingDateLocal}}",
                    "Hello {{firstName}},\n\n"
                            + "Your trainer {{trainerName}} has scheduled a weekly support "
                            + "meeting on {{meetingDateLocal}} {{timezone}}.\n\n"
                            + "Topic: {{topic}}\nAgenda: {{agenda}}\n\n"
                            + "Join: {{zoomJoinUrl}}\n\n{{signoffBlock}}",
                    "firstName,trainerName,meetingDateLocal,timezone,topic,zoomJoinUrl,agenda"),
            new Seed(
                    "WEEKLY_MEETING_COMPLETED", "EMAIL",
                    "Weekly meeting notes: {{meetingDateLocal}}",
                    "Hello {{firstName}},\n\n"
                            + "The weekly meeting on {{meetingDateLocal}} with "
                            + "{{trainerName}} has been recorded.\n\n"
                            + "Attendance: {{attendance}}\n"
                            + "Notes: {{notes}}\n"
                            + "Action items: {{actionItems}}\n\n{{signoffBlock}}",
                    "firstName,trainerName,meetingDateLocal,attendance,notes,actionItems"),
            new Seed(
                    "WEEKLY_MEETING_MISSED", "EMAIL",
                    "Weekly meeting marked missed: {{meetingDateLocal}}",
                    "Hello {{firstName}},\n\n"
                            + "Your scheduled meeting on {{meetingDateLocal}} with "
                            + "{{trainerName}} was marked missed.\n\n"
                            + "Reason: {{missedReason}}\n\n"
                            + "Please contact {{trainerName}} to reschedule.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,trainerName,meetingDateLocal,missedReason"),
            // Email-slice-2 — scheduler/host "you scheduled this meeting"
            // email. Dual-use: TrainerMeetingNotificationDispatcher sends
            // this to the trainer for a weekly meeting, and
            // InterviewEmailListener sends it to the interviewer for an
            // interview. Subject prefix is caller-supplied via
            // {{subjectPrefix}} ("Weekly meeting scheduled" or "Interview
            // scheduled") so the ONE template covers both flows and
            // future scheduler use-cases without another seed. Body
            // carries the meeting title, participant context, when/zone,
            // and — when available — the one-click Zoom {{startUrl}} for
            // the host (fetched fresh by SchedulerMeetingEmailSender on
            // send because Zoom start URLs expire ~2h after create).
            // The dashboard fallback line handles the missing-start-url
            // case without breaking the render.
            new Seed(
                    "MEETING_INVITE_HOST", "EMAIL",
                    "{{subjectPrefix}} — {{meetingTitle}}",
                    "Hi {{recipientName}},\n\n"
                            + "You scheduled \"{{meetingTitle}}\"{{participantLine}} "
                            + "for {{scheduledForLocal}} ({{timezone}}).\n\n"
                            + "{{hostAccessBlock}}\n\n"
                            + "{{signoffBlock}}",
                    "subjectPrefix,meetingTitle,recipientName,participantLine,"
                            + "scheduledForLocal,timezone,hostAccessBlock"),
            new Seed(
                    "SUBMISSION_UPLOADED", "EMAIL",
                    "Submission ready for review: {{projectTitle}}",
                    "Hello {{trainerFirstName}},\n\n"
                            + "{{internName}} has submitted work on {{projectTitle}}.\n\n"
                            + "Open the Pending Reviews queue:\n{{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "trainerFirstName,internName,projectTitle,deepLink"),
            new Seed(
                    "FEEDBACK_PUBLISHED", "EMAIL",
                    "Feedback published: {{projectTitle}} — {{decisionLabel}}",
                    "Hello {{firstName}},\n\n"
                            + "Your trainer {{trainerName}} has published feedback on "
                            + "{{projectTitle}}.\n\n"
                            + "Decision: {{decisionLabel}}\n"
                            + "Technical: {{technicalScore}}/5\n"
                            + "Communication: {{communicationScore}}/5\n\n"
                            + "Notes: {{reviewNotes}}\n\n"
                            + "{{nextActionBlurb}}\n\n"
                            + "View full feedback:\n{{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,trainerName,projectTitle,decisionLabel,technicalScore,"
                            + "communicationScore,reviewNotes,nextActionBlurb,deepLink"),
            new Seed(
                    "PROJECT_OVERDUE", "EMAIL",
                    "Project overdue: {{projectTitle}}",
                    "Hello {{firstName}},\n\n"
                            + "The project {{projectTitle}} was due {{dueDateLocal}} and "
                            + "has not been submitted.\n\n"
                            + "Please submit, or contact your trainer {{trainerName}}. "
                            + "Escalation may follow if not resolved.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,trainerName,projectTitle,dueDateLocal"),
            // ── ERM Phase 8 — Document packet workflow ──────────────────────
            new Seed(
                    "DOCUMENT_PACKET_ASSIGNED", "EMAIL",
                    "Your document packet is ready: {{templateCount}} forms to complete",
                    "Hello {{firstName}},\n\n"
                            + "Your ERM {{ermName}} has assigned you {{templateCount}} "
                            + "documents to complete:\n"
                            + "{{templateTitlesList}}\n\n"
                            + "For each document:\n"
                            + "  1. Click Download to open the PDF.\n"
                            + "  2. Print the PDF and fill it out by hand (blue or black pen).\n"
                            + "  3. Use your phone's scanner app (Adobe Scan, Microsoft "
                            + "Lens, or your built-in Notes scanner) to scan all filled "
                            + "pages into a single PDF.\n"
                            + "  4. Upload the scanned PDF from your dashboard.\n\n"
                            + "Open your dashboard to get started:\n"
                            + "{{deepLink}}\n\n{{signoffBlock}}",
                    "firstName,ermName,templateCount,templateTitlesList,deepLink"),
            new Seed(
                    "DOCUMENT_TASK_ACCEPTED", "EMAIL",
                    "Document accepted: {{templateTitle}}",
                    "Hello {{firstName}},\n\n"
                            + "Your submission for {{templateTitle}} has been reviewed "
                            + "and accepted by {{ermName}}.\n\n"
                            + "{{remainingTasksBlurb}}\n\n{{signoffBlock}}",
                    "firstName,templateTitle,ermName,remainingTasksBlurb"),
            new Seed(
                    "DOCUMENT_TASK_REJECTED", "EMAIL",
                    "Action needed: {{templateTitle}}",
                    "Hello {{firstName}},\n\n"
                            + "Your submission for {{templateTitle}} has been rejected.\n\n"
                            + "Reason: {{reasonHuman}}\n"
                            + "ERM comments: {{ermComments}}\n\n"
                            + "Please correct the issue and re-scan all pages into a "
                            + "single PDF, then upload again from your dashboard:\n"
                            + "{{deepLink}}\n\n{{signoffBlock}}",
                    "firstName,templateTitle,reasonHuman,ermComments,deepLink"),
            new Seed(
                    "DOCUMENT_TASK_RESEND", "EMAIL",
                    "Please update: {{templateTitle}}",
                    "Hello {{firstName}},\n\n"
                            + "Please update your submission for {{templateTitle}}.\n\n"
                            + "Reason: {{reasonHuman}}\n"
                            + "ERM comments: {{ermComments}}\n\n"
                            + "Re-scan all pages into a single PDF and resubmit from your "
                            + "dashboard:\n"
                            + "{{deepLink}}\n\n{{signoffBlock}}",
                    "firstName,templateTitle,reasonHuman,ermComments,deepLink"),
            new Seed(
                    "DOCUMENT_PACKET_COMPLETED", "EMAIL",
                    "Onboarding complete — welcome to {{brandName}}!",
                    "Hello {{firstName}},\n\n"
                            + "All your onboarding documents have been accepted. "
                            + "Your tentative start date is {{tentativeStartDate}}.\n\n"
                            + "Your team:\n"
                            + " · Trainer: {{trainerName}}\n"
                            + " · Evaluator: {{evaluatorName}}\n"
                            + " · Manager: {{managerName}}\n\n"
                            + "See you soon!\n\n{{signoffBlock}}",
                    "firstName,tentativeStartDate,trainerName,evaluatorName,managerName"),
            // ── Evaluator Phase 0 — scaffolded templates; workflows ship in
            // Phases 2-4. Seeded here so production templates exist before
            // the first send. Idempotent: re-running the seeder is a no-op
            // for matched (key, channel) pairs.
            new Seed(
                    "EVALUATION_SCHEDULED", "EMAIL",
                    "Evaluation scheduled — {{evaluationType}} on {{scheduledDateLocal}}",
                    "Hello {{firstName}},\n\n"
                            + "Your {{evaluationType}} evaluation has been scheduled by "
                            + "{{evaluatorName}}.\n\n"
                            + "When: {{scheduledDateLocal}} ({{timezone}})\n"
                            + "Join: {{zoomLink}}\n\n"
                            + "Come prepared to discuss your recent projects, your goals, "
                            + "and any blockers. We'll capture the outcome in your dashboard "
                            + "right after.\n\n{{signoffBlock}}",
                    "firstName,evaluationType,evaluatorName,scheduledDateLocal,"
                            + "timezone,zoomLink"),
            new Seed(
                    "EVALUATION_PUBLISHED", "EMAIL",
                    "Your evaluation is ready to view",
                    "Hello {{firstName}},\n\n"
                            + "{{evaluatorName}} has published your {{evaluationType}} "
                            + "evaluation. Please review it in your dashboard and acknowledge "
                            + "within {{ackDays}} days.\n\n"
                            + "Summary: {{summaryLine}}\n\n"
                            + "Open evaluation: {{deepLink}}\n\n{{signoffBlock}}",
                    "firstName,evaluatorName,evaluationType,ackDays,summaryLine,deepLink"),
            new Seed(
                    "EVALUATION_AMENDED", "EMAIL",
                    "Your evaluation has been updated",
                    "Hello {{firstName}},\n\n"
                            + "{{evaluatorName}} has amended the evaluation you previously "
                            + "acknowledged on {{previousAckDate}}. Please review the updated "
                            + "version and re-acknowledge.\n\n"
                            + "What changed: {{changeSummary}}\n\n"
                            + "Open evaluation: {{deepLink}}\n\n{{signoffBlock}}",
                    "firstName,evaluatorName,previousAckDate,changeSummary,deepLink"),
            new Seed(
                    "EVALUATION_REMINDER_TO_INTERN", "EMAIL",
                    "Reminder: please acknowledge your evaluation",
                    "Hello {{firstName}},\n\n"
                            + "Your {{evaluationType}} evaluation has been waiting on your "
                            + "acknowledgment for {{daysWaiting}} days.\n\n"
                            + "It takes less than a minute — open your dashboard and click "
                            + "Acknowledge so we can mark this cycle complete:\n"
                            + "{{deepLink}}\n\n{{signoffBlock}}",
                    "firstName,evaluationType,daysWaiting,deepLink"),
            new Seed(
                    "EVALUATION_OVERDUE_ALERT", "EMAIL",
                    "Heads up: {{internName}} has no evaluation this month",
                    "Hello {{ermName}},\n\n"
                            + "{{internName}} ({{employeeId}}) does not have a PUBLISHED "
                            + "evaluation for {{monthYear}}. The Evaluator hasn't scheduled "
                            + "or completed one yet.\n\n"
                            + "Open the dashboard to follow up:\n{{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "ermName,internName,employeeId,monthYear,deepLink"),
            new Seed(
                    "I983_EVALUATION_DUE", "EMAIL",
                    "I-983 evaluation due — {{internName}}",
                    "Hello {{evaluatorName}},\n\n"
                            + "{{internName}} ({{employeeId}}) is on F-1 STEM OPT and the "
                            + "next I-983 evaluation window opens {{windowStartDate}} and "
                            + "must be submitted by {{dueDate}}.\n\n"
                            + "Open the I-983 workspace: {{deepLink}}\n\n"
                            + "ERM is CC'd. Please coordinate scheduling with the intern.\n\n"
                            + "{{signoffBlock}}",
                    "evaluatorName,internName,employeeId,windowStartDate,dueDate,deepLink"),
            new Seed(
                    "I983_EVALUATION_PUBLISHED", "EMAIL",
                    "Your I-983 evaluation is ready",
                    "Hello {{firstName}},\n\n"
                            + "{{evaluatorName}} has published your I-983 {{evaluationType}} "
                            + "evaluation. Please review the form and confirm the student "
                            + "signature section in your dashboard so we can complete the DSO "
                            + "submission.\n\n"
                            + "Open evaluation: {{deepLink}}\n\n{{signoffBlock}}",
                    "firstName,evaluatorName,evaluationType,deepLink"),
            // ── Wave-1 email-refinement — two new high-value receipts ─────
            // APPLICATION_RECEIVED — applicant-facing confirmation on apply.
            // Historically the applicant only got the hardcoded shape from
            // SmtpEmailProvider.sendApplicationReceived and no ERM-editable
            // template. Seeding a proper template lets the settings page
            // customise the copy and adds the "what happens next" +
            // timeline every ATS provides. NotificationService.
            // sendApplicationReceived now renders this template with a
            // fallback to the hard-coded method when the template is
            // absent (Wave-2 will remove the fallback once every deploy
            // has re-seeded).
            new Seed(
                    "APPLICATION_RECEIVED", "EMAIL",
                    "We received your {{brandName}} application — {{jobTitle}}",
                    "Hello {{firstName}},\n\n"
                            + "Thanks for applying to {{jobTitle}} at {{brandName}} — we've "
                            + "received your application and it's now with our recruiting "
                            + "team.\n\n"
                            + "What happens next:\n"
                            + "  · A recruiter reviews your application within 3-5 business "
                            + "days.\n"
                            + "  · If your background looks like a fit, we'll invite you to "
                            + "an initial interview.\n"
                            + "  · If it's not a match, we'll let you know via email — we "
                            + "close the loop either way.\n\n"
                            + "You can track the status any time in your {{brandName}} "
                            + "dashboard. Reach out to {{supportEmail}} with any "
                            + "questions.\n\n{{signoffBlock}}",
                    "firstName,jobTitle"),
            // OFFER_ACCEPTED — applicant-facing confirmation when they
            // sign the offer through IDMS. Previously only ERM got an
            // in-app notice and the applicant got the hardcoded body
            // from SmtpEmailProvider.sendOfferAccepted with no editable
            // template. Seeding gives the ERM settings page control over
            // the copy and provides the applicant with a durable record
            // of what they signed + the onboarding next steps.
            new Seed(
                    "OFFER_ACCEPTED", "EMAIL",
                    "Welcome to {{brandName}} — offer for {{jobTitle}} accepted",
                    "Hello {{firstName}},\n\n"
                            + "Congratulations — we've received your signed offer for "
                            + "{{jobTitle}} at {{brandName}}. This email is your record; a copy "
                            + "of the executed PDF is available any time in your {{brandName}} "
                            + "dashboard.\n\n"
                            + "What happens next:\n"
                            + "  · Your ERM will reach out within 2 business days to kick "
                            + "off onboarding.\n"
                            + "  · You'll receive a document packet to complete before your "
                            + "start date on {{tentativeStartDate}}.\n"
                            + "  · Your reporting team (trainer, evaluator, manager) will be "
                            + "assigned and you'll get an intro email from each.\n\n"
                            + "Save the entity name for your records: {{brandLegalEntity}}. "
                            + "Reach out to {{supportEmail}} with any questions.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,jobTitle,tentativeStartDate"),
            // ── Slice-1 evaluation migration — templates the fanout was
            // constructing inline. Rebrand-safe: literal "{{brandName}}" and
            // the "{{signoffBlock}}" signoff are rewritten by brandify() to
            // {{brandName}} / {{signoffBlock}} at seed time.
            new Seed(
                    "EVALUATION_ACK_REQUESTED", "EMAIL",
                    "Action needed: acknowledge your evaluation",
                    "Hello {{firstName}},\n\n"
                            + "{{evaluatorName}}, your Evaluator, is waiting on your "
                            + "acknowledgment of the monthly evaluation just published. "
                            + "Two-click flow: open the evaluation, add an optional note, "
                            + "and click Acknowledge — that confirms you've reviewed the "
                            + "ratings and lets your Manager and ERM know you're aware.\n\n"
                            + "Open it to acknowledge: {{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,evaluatorName,deepLink"),
            new Seed(
                    "I983_EVALUATION_SCHEDULED", "EMAIL",
                    "I-983 {{evaluationType}} evaluation scheduled by your Evaluator",
                    "Hello {{firstName}},\n\n"
                            + "{{evaluatorName}}, your Evaluator, has scheduled your "
                            + "{{evaluationType}} I-983 evaluation.\n\n"
                            + "Window: {{windowStartDate}}  →  Due: {{dueDate}}\n\n"
                            + "Open your I-983 evaluations: {{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,evaluatorName,evaluationType,windowStartDate,dueDate,deepLink"),
            new Seed(
                    "I983_DSO_SUBMITTED", "EMAIL",
                    "Your I-983 was submitted to your DSO",
                    "Hello {{firstName}},\n\n"
                            + "{{actorName}}, your ERM, has submitted your I-983 evaluation "
                            + "to your DSO.\n"
                            + "Submission method: {{submissionMethod}}.\n\n"
                            + "Keep this confirmation for your STEM-OPT records.\n"
                            + "Open your I-983: {{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,actorName,submissionMethod,deepLink"),
            new Seed(
                    "I983_EVALUATION_AMENDED", "EMAIL",
                    "Your I-983 was updated — please re-sign",
                    "Hello {{firstName}},\n\n"
                            + "{{evaluatorName}}, your Evaluator, has updated your I-983 "
                            + "evaluation. Your previous signature has been reset, so please "
                            + "review the changes and re-sign before the DSO submission "
                            + "window.\n\n"
                            + "What changed: {{changeSummary}}\n\n"
                            + "Open your I-983: {{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,evaluatorName,changeSummary,deepLink"),
            // ── Slice-3 manager-hire-decision fold-ins — ERM-facing.
            // Recipient for all three: the ERM who owns the applicant.
            // Previously the inline title/body was built in
            // InterviewEmailListener.notifyErmOfHireDecision and shipped
            // in two places (the dispatcher's auto-email hook AND an
            // explicit emailProvider.sendRendered call), which double-
            // emailed the ERM. Templates + emailSent=true collapse the
            // send to one place while unlocking rebrand/admin-edit.
            new Seed(
                    "MANAGER_HIRE_APPROVED", "EMAIL",
                    "Hire approved: {{internName}}",
                    "Hello {{ermName}},\n\n"
                            + "A Manager approved the hire for {{internName}}. The "
                            + "candidate is now SELECTED; once they acknowledge the "
                            + "selection, you can send the offer.\n\n"
                            + "Open the decision center:\n{{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "ermName,internName,deepLink"),
            new Seed(
                    "MANAGER_HIRE_HOLD", "EMAIL",
                    "Hire on hold: {{internName}}",
                    "Hello {{ermName}},\n\n"
                            + "A Manager placed the hire for {{internName}} ON HOLD — "
                            + "no final decision yet.{{noteBlock}}\n\n"
                            + "Check the interview and follow up as needed:\n{{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "ermName,internName,noteBlock,deepLink"),
            new Seed(
                    "MANAGER_HIRE_DECLINED", "EMAIL",
                    "Hire not approved: {{internName}}",
                    "Hello {{ermName}},\n\n"
                            + "A Manager declined the hire for {{internName}}. The "
                            + "application has been moved to REJECTED.\n\n"
                            + "Open the interview:\n{{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "ermName,internName,deepLink"),
            // ── Slice-5 profile + I-983 self-eval fold-ins.
            // PROFILE_SUBMITTED — ERM-facing one-shot alert when an intern
            // first meets the stricter completeness bar (base + address +
            // education + expected work-auth track). One row per active ERM.
            // The blank {{aptLine}} / {{workAuthBlock}} placeholders are
            // supplied by the caller as empty strings when the intern
            // didn't provide those optional pieces — the template renders
            // cleanly (no orphan labels) either way.
            new Seed(
                    "PROFILE_SUBMITTED", "EMAIL",
                    "Profile submitted: {{internName}}",
                    "Hello {{ermName}},\n\n"
                            + "A new intern profile has been submitted.\n\n"
                            + "  · Name: {{internName}}\n"
                            + "  · Email: {{internEmail}}\n"
                            + "  · Contact: {{internPhone}}\n"
                            + "  · Work authorization: {{workAuth}}\n"
                            + "  · Skillset: {{skillset}}\n"
                            + "  · Full address: {{fullAddress}}\n"
                            + "  · Submitted: {{submittedAtLocal}}\n\n"
                            + "Open the ERM applications dashboard to review:\n"
                            + "{{deepLink}}\n\n{{signoffBlock}}",
                    "ermName,internName,internEmail,internPhone,workAuth,"
                            + "skillset,fullAddress,submittedAtLocal,deepLink"),
            // PROFILE_EDITED — ERM-facing throttled alert (max 1/15min) when
            // an intern edits a monitored field after submission. Same
            // recipient shape as PROFILE_SUBMITTED. {{changedField}} names
            // the area that triggered the notify.
            new Seed(
                    "PROFILE_EDITED", "EMAIL",
                    "Profile updated: {{internName}}",
                    "Hello {{ermName}},\n\n"
                            + "An intern updated their profile after submission.\n\n"
                            + "  · Name: {{internName}}\n"
                            + "  · Email: {{internEmail}}\n"
                            + "  · Contact: {{internPhone}}\n"
                            + "  · Changed area: {{changedField}}\n"
                            + "  · Skillset: {{skillset}}\n"
                            + "  · Full address: {{fullAddress}}\n"
                            + "  · Work authorization: {{workAuth}}\n\n"
                            + "Open the ERM applications dashboard to review:\n"
                            + "{{deepLink}}\n\n"
                            + "(Note: further edits by this intern in the next 15 "
                            + "minutes will not trigger additional notifications.)\n\n"
                            + "{{signoffBlock}}",
                    "ermName,internName,internEmail,internPhone,changedField,"
                            + "skillset,fullAddress,workAuth,deepLink"),
            // I983_SELF_EVAL_DUE — intern-facing STEM-OPT compliance nudge
            // when the intern's self-review section on an I-983 evaluation
            // is awaiting completion. Distinct from I983_EVALUATION_DUE
            // (evaluator-facing) and I983_EVALUATION_SCHEDULED (intern-
            // facing scheduling notice). {{evaluationType}} inlines the
            // 12-month / 24-month label; deep link goes to the intern's
            // evaluations dashboard.
            new Seed(
                    "I983_SELF_EVAL_DUE", "EMAIL",
                    "Your {{evaluationType}} self-evaluation is awaiting your reflection",
                    "Hello {{firstName}},\n\n"
                            + "Your {{evaluationType}} I-983 self-evaluation is awaiting "
                            + "your reflection. Add your ratings and reflection notes "
                            + "before your supervisor finalizes their side — the two "
                            + "halves sign the same federal STEM-OPT record.\n\n"
                            + "Open your evaluations dashboard:\n{{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,evaluationType,deepLink"),
            // ── Slice-6b Category A migration — template-first-with-typed-
            // fallback for high-priority NotificationService legacy methods.
            // Each maps 1:1 to an existing emailProvider.sendXxx() typed
            // hardcoded body in SmtpEmailProvider that stays as fallback.
            //
            // ONBOARDING_WELCOME — Engagement flipped ACTIVE, first email
            // to the new hire. Vars mirror sendOnboardingWelcome's args.
            new Seed(
                    "ONBOARDING_WELCOME", "EMAIL",
                    "Welcome to {{brandName}} — {{jobTitle}} onboarding starts now",
                    "Hello {{firstName}},\n\n"
                            + "Congratulations — your engagement with "
                            + "{{entityName}} for the {{jobTitle}} role is now active. "
                            + "Your onboarding checklist is waiting for you in your "
                            + "dashboard.\n\n"
                            + "  · Start date: {{startDate}}\n\n"
                            + "Head to your dashboard to complete the required "
                            + "onboarding items so we can lock in your start:\n"
                            + "{{deepLink}}\n\n"
                            + "Reach out to {{supportEmail}} with any questions.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,jobTitle,entityName,startDate,deepLink"),
            // INTERVIEW_REMINDER — 24h-before scheduler reminder.
            new Seed(
                    "INTERVIEW_REMINDER", "EMAIL",
                    "Reminder: your {{jobTitle}} interview is tomorrow",
                    "Hello {{firstName}},\n\n"
                            + "This is a friendly reminder that your interview for "
                            + "{{jobTitle}} at {{entityName}} is scheduled within the "
                            + "next 24 hours.\n\n"
                            + "  · When: {{scheduledAtLocal}}\n"
                            + "  · Duration: {{durationMinutes}} minutes\n"
                            + "  · Type: {{interviewType}}\n"
                            + "  · Interviewer: {{interviewerName}}\n"
                            + "  · Join link: {{meetingUrl}}\n\n"
                            + "Please be online 2-3 minutes early so any connection "
                            + "issues don't eat into your interview time.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,jobTitle,entityName,scheduledAtLocal,"
                            + "durationMinutes,interviewType,interviewerName,meetingUrl"),
            // COMPLIANCE_TASK_REMINDER — one row per overdue task, fired by
            // the daily scheduler. {{overdueLine}} is empty when the task is
            // not yet overdue (just due today), else "Overdue by N day(s)".
            new Seed(
                    "COMPLIANCE_TASK_REMINDER", "EMAIL",
                    "Action needed: {{taskTitle}}",
                    "Hello {{firstName}},\n\n"
                            + "An onboarding task is waiting for you:\n\n"
                            + "  · Task: {{taskTitle}}\n"
                            + "  · Due: {{dueDate}}\n"
                            + "{{overdueLine}}\n\n"
                            + "Open your onboarding dashboard to complete it:\n"
                            + "{{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,taskTitle,dueDate,overdueLine,deepLink"),
            // TIMESHEET_DUE — weekly-scheduler reminder to intern to submit
            // their timesheet for {{weekStart}}.
            new Seed(
                    "TIMESHEET_DUE", "EMAIL",
                    "Weekly timesheet due — week of {{weekStart}}",
                    "Hello {{firstName}},\n\n"
                            + "Your weekly timesheet for the week of {{weekStart}} is "
                            + "due. Head to your dashboard to log your hours before "
                            + "your Manager's review cycle closes.\n\n"
                            + "Open your dashboard:\n{{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,weekStart,deepLink"),
            // WEEKLY_REPORT_DUE — weekly-scheduler reminder to intern to
            // submit their weekly report for {{weekStart}}.
            new Seed(
                    "WEEKLY_REPORT_DUE", "EMAIL",
                    "Weekly report due — week of {{weekStart}}",
                    "Hello {{firstName}},\n\n"
                            + "Your weekly report for the week of {{weekStart}} is "
                            + "due. Share the wins, blockers, and progress from the "
                            + "past week so your Manager and ERM stay aligned.\n\n"
                            + "Open your dashboard:\n{{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,weekStart,deepLink"),
            // ── Slice-6c OnboardingTrackerService fold-ins (Category C).
            // INTERN_ONBOARDING_ANNOUNCED — staff-facing (trainer /
            // evaluator / manager) alert when an ERM notifies the team
            // that a new intern joined. Fired 3× (once per role) from
            // OnboardingTrackerService.notifyTeam. Var {{firstName}} is
            // the STAFF recipient's first name (the intern is
            // {{internName}}).
            new Seed(
                    "INTERN_ONBOARDING_ANNOUNCED", "EMAIL",
                    "New intern joined — {{internName}}",
                    "Hi {{firstName}},\n\n"
                            + "{{internName}} has accepted their offer with {{brandName}} "
                            + "and their onboarding is in progress. They'll appear in "
                            + "your dashboard once activated.\n\n"
                            + "View your active-interns list:\n{{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,internName,deepLink"),
            // OFFER_SIGN_REMINDER — intern-facing lightweight nudge that
            // their offer letter is awaiting e-signature. Distinct from
            // OFFER_REMINDER (the offer-service's dedicated periodic
            // reminder) — this one fires ad-hoc from the tracker's
            // Signature Reminder action button. {{ermName}} names the
            // ERM sending it; empty when the caller is unresolved.
            new Seed(
                    "OFFER_SIGN_REMINDER", "EMAIL",
                    "Reminder: please sign your {{brandName}} offer",
                    "Hi {{firstName}},\n\n"
                            + "Your offer letter is waiting for your e-signature. "
                            + "Sign in to view and sign your offer:\n{{deepLink}}\n\n"
                            + "This is a friendly reminder from {{ermName}}.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,ermName,deepLink"),
            // ── Slice-6d Category A next-subset seeds ────────────────────
            // OFFER_EXTENDED — applicant-facing offer-ready alert fired
            // by OfferService.extendOffer. Compensation vars are inline
            // placeholders (empty when missing) so the render stays
            // coherent whether or not the ERM populated them.
            new Seed(
                    "OFFER_EXTENDED", "EMAIL",
                    "Your {{jobTitle}} offer from {{entityName}} is ready to view",
                    "Hello {{firstName}},\n\n"
                            + "We're delighted to extend an offer for the {{jobTitle}} "
                            + "role at {{entityName}}.\n\n"
                            + "  · Compensation: {{compensationLine}}\n"
                            + "  · Start date: {{startDate}}\n"
                            + "  · Offer expires: {{expiresAt}}\n\n"
                            + "Open the Offer page to review the full letter and sign:\n"
                            + "{{deepLink}}\n\n"
                            + "Reach out to {{supportEmail}} with any questions.\n\n"
                            + "{{signoffBlock}}",
                    "firstName,jobTitle,entityName,compensationLine,startDate,"
                            + "expiresAt,deepLink"),
            // WEEKLY_REPORT_RETURNED — intern-facing "needs revisions"
            // alert from the reviewer. {{reviewNotesLine}} inlines the
            // reviewer's notes when supplied (empty when blank).
            new Seed(
                    "WEEKLY_REPORT_RETURNED", "EMAIL",
                    "Weekly report returned for changes — week of {{weekStart}}",
                    "Hello {{firstName}},\n\n"
                            + "Your weekly report for the week of {{weekStart}} was "
                            + "returned with review feedback. Please open it, apply the "
                            + "corrections, and re-submit."
                            + "{{reviewNotesLine}}\n\n"
                            + "Open the report:\n{{deepLink}}\n\n{{signoffBlock}}",
                    "firstName,weekStart,reviewNotesLine,deepLink"),
            // WEEKLY_REPORT_APPROVED — intern-facing "approved" ack.
            new Seed(
                    "WEEKLY_REPORT_APPROVED", "EMAIL",
                    "Weekly report approved — week of {{weekStart}}",
                    "Hello {{firstName}},\n\n"
                            + "Your weekly report for the week of {{weekStart}} was "
                            + "approved. Nice work — no further action needed for this "
                            + "week.\n\n"
                            + "Open your dashboard:\n{{deepLink}}\n\n{{signoffBlock}}",
                    "firstName,weekStart,deepLink"),
            // I9_SECTION1_REMINDER — intern-facing prompt to complete
            // I-9 §1 before the due date.
            new Seed(
                    "I9_SECTION1_REMINDER", "EMAIL",
                    "Action needed: complete your I-9 Section 1",
                    "Hello {{firstName}},\n\n"
                            + "Your I-9 Section 1 is due {{dueDate}}. This is a required "
                            + "federal employment-eligibility form — please complete it "
                            + "before the deadline so onboarding stays on track.\n\n"
                            + "Open the I-9 page to complete it:\n{{deepLink}}\n\n"
                            + "{{signoffBlock}}",
                    "firstName,dueDate,deepLink")
    );

    @Override
    public void run(String... args) {
        int seeded = 0;
        int skipped = 0;
        for (Seed raw : SEEDS) {
            Seed s = brandify(raw);
            try {
                if (repository.existsByKeyAndChannel(s.key(), s.channel())) {
                    skipped++;
                    continue;
                }
                CommunicationTemplate t = CommunicationTemplate.builder()
                        .key(s.key())
                        .channel(s.channel())
                        .subjectTemplate(s.subject())
                        .bodyTemplate(s.body())
                        .variablesCsv(s.vars())
                        .active(true)
                        .build();
                repository.save(t);
                seeded++;
            } catch (Exception e) {
                log.warn("[CommunicationTemplateSeeder] seed failed for {} (non-fatal): {}",
                        s.key(), e.getMessage());
            }
        }
        log.info("[CommunicationTemplateSeeder] seeded {} templates (idempotent; {} pre-existing)",
                seeded, skipped);
        deactivateLegacyTemplates();
        refreshPhase8_2DocumentTemplates();
        refreshOfferTemplatesIfStale();
        rebrandifyExistingRowsIfLiteralBrandLeaks();
    }

    /**
     * Wave-1 email-refinement migration — walk every persisted template
     * row and, if the stored subject or body still carries the pre-Wave-1
     * literal {@code "Anvi Corp"} (or the pre-rebrand {@code "Skyzen ERM"})
     * tokens, rewrite them to the new placeholder shape via
     * {@link #rewrite(String)}. Guarantees an upgrade from any prior seed
     * moves to the white-label form without operators having to hand-edit
     * every row through the Communication Templates Settings page.
     *
     * <p>Preserves ERM-customised copy: only touches rows that STILL
     * contain the literal tokens (the marker for "never edited"). A row
     * an ERM has re-worded to use their brand or their own phrasing
     * won't match the literals and stays untouched.</p>
     */
    private void rebrandifyExistingRowsIfLiteralBrandLeaks() {
        int rewritten = 0;
        try {
            for (CommunicationTemplate t : repository.findAll()) {
                String subj = t.getSubjectTemplate();
                String body = t.getBodyTemplate();
                if (!containsLegacyBrandToken(subj) && !containsLegacyBrandToken(body)) {
                    continue;
                }
                String newSubj = rewrite(subj);
                String newBody = rewrite(body);
                boolean changed = (subj == null ? newSubj != null : !subj.equals(newSubj))
                        || (body == null ? newBody != null : !body.equals(newBody));
                if (!changed) continue;
                t.setSubjectTemplate(newSubj);
                t.setBodyTemplate(newBody);
                repository.save(t);
                rewritten++;
            }
        } catch (Exception e) {
            log.warn("[CommunicationTemplateSeeder] Wave-1 brandify-migration skipped: {}",
                    e.getMessage());
            return;
        }
        if (rewritten > 0) {
            log.info("[CommunicationTemplateSeeder] Wave-1 rewrote {} DB row(s) — literal "
                    + "brand tokens migrated to placeholders (brand={})",
                    rewritten, brand.getName());
        }
    }

    private static boolean containsLegacyBrandToken(String s) {
        if (s == null) return false;
        return s.contains("Anvi Corp") || s.contains("Skyzen ERM");
    }

    /**
     * Overwrite OFFER_LETTER / OFFER_REMINDER bodies when the existing DB
     * row carries DocuSign-era wording ("DocuSign", "docusign",
     * "envelope") — pre-IDMS rows that the strict idempotent seed leaves
     * untouched on boot. Safe for ERM-customized rows: only fires when
     * the legacy DocuSign tokens are present, so a hand-edited row with
     * neutral wording is preserved.
     */
    private void refreshOfferTemplatesIfStale() {
        List<String> offerKeys = List.of("OFFER_LETTER", "OFFER_REMINDER");
        int refreshed = 0;
        for (Seed raw : SEEDS) {
            if (!offerKeys.contains(raw.key())) continue;
            Seed s = brandify(raw);
            try {
                var existing = repository.findByKeyAndChannel(s.key(), s.channel());
                if (existing.isEmpty()) continue;
                var t = existing.get();
                String body = t.getBodyTemplate() != null ? t.getBodyTemplate() : "";
                String subj = t.getSubjectTemplate() != null ? t.getSubjectTemplate() : "";
                boolean stale = containsLegacySigningToken(body)
                        || containsLegacySigningToken(subj);
                if (!stale) continue;
                t.setSubjectTemplate(s.subject());
                t.setBodyTemplate(s.body());
                t.setVariablesCsv(s.vars());
                t.setActive(true);
                repository.save(t);
                refreshed++;
            } catch (Exception e) {
                log.warn("[CommunicationTemplateSeeder] OFFER refresh skipped for {}: {}",
                        s.key(), e.getMessage());
            }
        }
        if (refreshed > 0) {
            log.info("[CommunicationTemplateSeeder] refreshed {} stale DocuSign-era "
                    + "offer template(s) to the IDMS in-house signing copy", refreshed);
        }
    }

    private static boolean containsLegacySigningToken(String s) {
        if (s == null) return false;
        String lc = s.toLowerCase();
        return lc.contains("docusign") || lc.contains("envelope");
    }

    /**
     * ERM Phase 8.2 — overwrite the body/subject for the document-packet
     * templates whose copy changed in this phase (scan-with-phone
     * workflow). Idempotent: if the existing row's body already matches
     * the spec, no save is issued.
     */
    private void refreshPhase8_2DocumentTemplates() {
        List<String> refreshKeys = List.of(
                "DOCUMENT_PACKET_ASSIGNED",
                "DOCUMENT_TASK_REJECTED",
                "DOCUMENT_TASK_RESEND");
        int refreshed = 0;
        for (Seed raw : SEEDS) {
            if (!refreshKeys.contains(raw.key())) continue;
            Seed s = brandify(raw);
            try {
                var existing = repository.findByKeyAndChannel(s.key(), s.channel());
                if (existing.isEmpty()) continue;
                var t = existing.get();
                boolean dirty = !s.body().equals(t.getBodyTemplate())
                        || !s.subject().equals(t.getSubjectTemplate())
                        || !s.vars().equals(t.getVariablesCsv());
                if (!dirty) continue;
                t.setSubjectTemplate(s.subject());
                t.setBodyTemplate(s.body());
                t.setVariablesCsv(s.vars());
                t.setActive(true);
                repository.save(t);
                refreshed++;
            } catch (Exception e) {
                log.warn("[CommunicationTemplateSeeder] Phase 8.2 refresh skipped for {}: {}",
                        s.key(), e.getMessage());
            }
        }
        if (refreshed > 0) {
            log.info("[CommunicationTemplateSeeder] Phase 8.2 refreshed {} template(s) "
                    + "with scan-with-phone workflow copy", refreshed);
        }
    }

    /** ERM Phase 8 — mark the per-form onboarding templates inactive.
     *  The DOCUMENT_TASK_* / DOCUMENT_PACKET_* set above replaces them.
     *  Idempotent: if rows don't exist (fresh DB) or are already
     *  inactive, no-op. */
    private void deactivateLegacyTemplates() {
        List<String> legacyKeys = List.of(
                "ONBOARDING_ITEM_ACCEPTED",
                "ONBOARDING_ITEM_REJECTED",
                "ONBOARDING_ITEM_RESEND",
                "ONBOARDING_PACKET_ACCEPTED");
        int deactivated = 0;
        for (String key : legacyKeys) {
            try {
                var existing = repository.findByKeyAndChannel(key, "EMAIL");
                if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getActive())) {
                    var t = existing.get();
                    t.setActive(false);
                    repository.save(t);
                    deactivated++;
                }
            } catch (Exception e) {
                log.warn("[CommunicationTemplateSeeder] legacy deactivate skipped for {}: {}",
                        key, e.getMessage());
            }
        }
        if (deactivated > 0) {
            log.info("[CommunicationTemplateSeeder] deactivated {} legacy onboarding template(s)",
                    deactivated);
        }
    }
}
