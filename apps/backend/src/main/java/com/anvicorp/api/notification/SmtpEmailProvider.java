package com.anvicorp.api.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Real transactional email via Spring's JavaMailSender. Bean is created by
 * {@link EmailProviderConfiguration} only when {@code spring.mail.host} +
 * {@code spring.mail.username} are non-blank — otherwise we boot with the
 * {@link LogEmailProvider} so unconfigured environments still start cleanly.
 *
 * <h2>Branded shared template</h2>
 * Every outgoing mail renders through {@link #wrapHtml(String, String)} —
 * the same logo header, brand colors, content card, and footer. Adding a new
 * email type is "build the body fragment, call {@code wrapHtml}". Colors
 * mirror the frontend's tailwind palette (Anvi blue accent on the dark navy
 * header) — see {@code apps/frontend/tailwind.config.ts}.
 *
 * <h2>From header</h2>
 * {@code MimeMessageHelper.setFrom(address, personalName)} so the recipient's
 * inbox shows "Anvi Corp" (configurable via {@code MAIL_FROM_NAME}) not the
 * raw address. The address must be one the SMTP account is authorized to
 * send as.
 *
 * <h2>Multipart</h2>
 * Sent as multipart/alternative — plain text first, HTML second — so mobile
 * mail clients render the branded version while accessibility tools / spam
 * scoring see the plain version.
 *
 * <h2>Failure</h2>
 * Any {@link MailException} / {@link MessagingException} / encoding error is
 * wrapped in {@link EmailDeliveryException}. Auth flows MUST catch and
 * surface a retryable error; best-effort callers (applicant-ID notice,
 * password-reset, conditional-selection) catch + log on their own.
 */
@Slf4j
public class SmtpEmailProvider implements EmailProvider {

    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a z")
                    .withZone(ZoneId.of("America/New_York"));

    private static final DateTimeFormatter INTERVIEW_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, MMM d 'at' h:mm a z")
                    .withZone(ZoneId.of("America/New_York"));

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy");

    // Brand tokens — mirror frontend/tailwind.config.ts so the email is
    // visually continuous with the rest of the product. Baked at compile
    // time (email HTML is built from these constants, not read from the
    // BRAND config bean — kept simple). For per-brand deploys that need
    // different accents, override in a subclass or introduce config here.
    private static final String COLOR_HEADER_BG    = "#080d1a"; // neutral dark navy (unchanged across brands)
    private static final String COLOR_HEADER_TEXT  = "#ffffff";
    private static final String COLOR_ACCENT_FROM  = "#2A8CDB"; // Anvi brand-500 (was Skyzen orange #fb9b47)
    private static final String COLOR_ACCENT_TO    = "#1F6BA8"; // Anvi brand-600 (was Skyzen orange #ff7c20)
    private static final String COLOR_BODY_BG      = "#f3f4f6";
    private static final String COLOR_CARD_BG      = "#ffffff";
    private static final String COLOR_CARD_BORDER  = "#e5e7eb";
    private static final String COLOR_TEXT_PRIMARY = "#0f172a";
    private static final String COLOR_TEXT_BODY    = "#1f2937";
    private static final String COLOR_TEXT_MUTED   = "#6b7280";
    private static final String COLOR_TEXT_HINT    = "#4b5563";
    private static final String COLOR_CODE_BG      = "#EFF7FD"; // Anvi brand-50   (was primary.50 orange tint)
    private static final String COLOR_CODE_BORDER  = "#D8ECFA"; // Anvi brand-100  (was primary.200 orange tint)

    private final JavaMailSender mailSender;
    private final String mailFromAddress;
    private final String mailFromName;
    private final String logoUrl;
    private final String brandUrl;
    /** Brand name surfaced in subjects + body copy (e.g. "Anvi Corp"). */
    private final String brand;
    /** "{brand} Careers" product noun (e.g. "Anvi Careers"). */
    private final String product;

    public SmtpEmailProvider(JavaMailSender mailSender,
                             String mailFromAddress,
                             String mailFromName,
                             String logoUrl,
                             String brandUrl,
                             String brand,
                             String product) {
        this.mailSender = mailSender;
        this.mailFromAddress = mailFromAddress;
        this.mailFromName = mailFromName;
        this.logoUrl = logoUrl;
        this.brandUrl = brandUrl;
        this.brand = brand;
        this.product = product;
    }

    @Override
    public void sendRendered(String email, String subject, String body) {
        if (email == null || email.isBlank()) return;
        String safeSubject = subject != null ? subject : brand + " update";
        String plain = body != null ? body : "";
        // Body comes from CommunicationTemplate — already plain text. Convert
        // line breaks for HTML preservation; do NOT trust the body as HTML.
        String html = wrapHtml(
                escape(safeSubject),
                "<div style=\"margin:0;font-size:15px;line-height:1.55;color:"
                        + COLOR_TEXT_BODY + ";white-space:pre-wrap;\">"
                        + escape(plain)
                        + "</div>"
        );
        send(email, safeSubject, plain, html);
    }

    @Override
    public void sendBrandedHtml(String email, String subject,
                                String plainBody, String htmlBody) {
        if (email == null || email.isBlank()) return;
        String safeSubject = subject != null ? subject : brand + " update";
        String plain = plainBody != null ? plainBody : "";
        // Caller has already built valid HTML; do NOT escape. Caller is
        // responsible for escape()ing any free-text it spliced in.
        String html = wrapHtml(escape(safeSubject), htmlBody != null ? htmlBody : "");
        send(email, safeSubject, plain, html);
    }

    @Override
    public void sendVerificationCode(String email, String code, Instant expiresAt) {
        String expiryLabel = expiresAt != null ? EXPIRY_FORMAT.format(expiresAt) : "soon";
        String plain = ""
                + "Welcome to " + brand + " Careers.\n\n"
                + "Your verification code is: " + code + "\n\n"
                + "Enter this code on the verification screen to activate your account.\n"
                + "It expires " + expiryLabel + ".\n\n"
                + "If you didn't request this, you can safely ignore this email.\n\n"
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Verify your email",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Welcome to <strong>" + brand + " Careers</strong>."
                        + "</p>"
                        + "<p style=\"margin:0 0 8px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Your verification code is:"
                        + "</p>"
                        + codeBlock(code)
                        + "<p style=\"margin:0 0 12px;font-size:14px;color:" + COLOR_TEXT_HINT + ";\">"
                        + "Enter this code on the verification screen to activate your account. "
                        + "It expires " + escape(expiryLabel) + "."
                        + "</p>"
                        + "<p style=\"margin:16px 0 0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + "If you didn't request this, you can safely ignore this email."
                        + "</p>"
        );
        send(email, "Your " + brand + " verification code", plain, html);
    }

    @Override
    public void sendApplicantIdIssued(String email, String applicantId) {
        String plain = ""
                + "Your Applicant ID has been issued.\n\n"
                + "Applicant ID: " + applicantId + "\n\n"
                + "Keep this for your records — recruiters reference it on every application.\n\n"
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Your Applicant ID",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Your account is verified — welcome aboard."
                        + "</p>"
                        + "<p style=\"margin:0 0 8px;font-size:14px;color:" + COLOR_TEXT_HINT + ";\">"
                        + "Your Applicant ID:"
                        + "</p>"
                        + "<div style=\"margin:8px 0 18px;padding:12px 16px;border:1px solid "
                        + COLOR_CARD_BORDER + ";border-radius:8px;background:#f9fafb;"
                        + "font-family:'SFMono-Regular',Consolas,monospace;"
                        + "font-size:18px;color:" + COLOR_TEXT_PRIMARY + ";\">"
                        + escape(applicantId)
                        + "</div>"
                        + "<p style=\"margin:0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + "Keep this for your records — recruiters reference it on every application."
                        + "</p>"
        );
        send(email, "Your Applicant ID", plain, html);
    }

    @Override
    public void sendPasswordReset(String email, String code, Instant expiresAt) {
        String expiryLabel = expiresAt != null ? EXPIRY_FORMAT.format(expiresAt) : "soon";
        String plain = ""
                + "We received a request to reset your " + brand + " Careers password.\n\n"
                + "Your reset code is: " + code + "\n\n"
                + "Enter this code on the reset screen along with your new password.\n"
                + "It expires " + expiryLabel + ".\n\n"
                + "If you didn't request this, you can safely ignore this email and\n"
                + "your password stays unchanged.\n\n"
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Reset your password",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "We received a request to reset your <strong>" + brand
                        + " Careers</strong> password."
                        + "</p>"
                        + "<p style=\"margin:0 0 8px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Your reset code is:"
                        + "</p>"
                        + codeBlock(code)
                        + "<p style=\"margin:0 0 12px;font-size:14px;color:" + COLOR_TEXT_HINT + ";\">"
                        + "Enter this code on the reset screen along with your new password. "
                        + "It expires " + escape(expiryLabel) + "."
                        + "</p>"
                        + "<p style=\"margin:16px 0 0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + "If you didn't request this, you can safely ignore this email and your "
                        + "password stays unchanged."
                        + "</p>"
        );
        send(email, "Reset your " + brand + " Careers password", plain, html);
    }

    @Override
    public void sendConditionalSelectionConfirmation(String email,
                                                     String jobPostingTitle,
                                                     String entityName) {
        String role = jobPostingTitle != null ? jobPostingTitle : "the role";
        String at = entityName != null ? " at " + entityName : "";
        String plain = ""
                + "Great news — you've been conditionally selected for " + role + at + ".\n\n"
                + "Your formal offer letter and compliance steps will follow shortly.\n"
                + "We'll be in touch with next steps.\n\n"
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "You've been conditionally selected",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Great news — you've been <strong>conditionally selected</strong> for "
                        + escape(role) + escape(at) + "."
                        + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:14px;color:" + COLOR_TEXT_HINT + ";\">"
                        + "Your formal offer letter and compliance steps will follow shortly. "
                        + "We'll be in touch with next steps."
                        + "</p>"
        );
        send(email, "You've been conditionally selected — " + brand, plain, html);
    }

    // ── Batch 1 — applicant lifecycle ───────────────────────────────────────

    @Override
    public void sendApplicationReceived(String email, String candidateName,
                                        String jobTitle, String entityName) {
        String role = jobTitle != null ? jobTitle : "the role";
        String at = entityName != null ? " at " + entityName : "";
        String greet = greeting(candidateName);
        String plain = ""
                + greet + "\n\n"
                + "Thanks for applying to " + role + at + ".\n\n"
                + "We've received your application and the recruiting team is reviewing it.\n"
                + "We'll be in touch once there's an update — typically within a few days.\n\n"
                + "You can track this application from your " + product + " dashboard.\n\n"
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "We've got your application",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Thanks for applying to <strong>" + escape(role) + "</strong>"
                        + escape(at) + "."
                        + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:14px;color:" + COLOR_TEXT_HINT + ";\">"
                        + "We've received your application and the recruiting team is reviewing it. "
                        + "We'll be in touch once there's an update — typically within a few days."
                        + "</p>"
                        + "<p style=\"margin:16px 0 0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + "You can track this application from your " + product + " dashboard."
                        + "</p>"
        );
        send(email, "We've got your application — " + brand, plain, html);
    }

    @Override
    public void sendApplicationShortlisted(String email, String candidateName,
                                           String jobTitle, String entityName) {
        String role = jobTitle != null ? jobTitle : "the role";
        String at = entityName != null ? " at " + entityName : "";
        String greet = greeting(candidateName);
        String plain = ""
                + greet + "\n\n"
                + "Good news — you've been shortlisted for " + role + at + ".\n\n"
                + "Our recruiting team will reach out shortly with the next steps,\n"
                + "which typically include an interview.\n\n"
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "You've been shortlisted",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Good news — you've been <strong>shortlisted</strong> for "
                        + "<strong>" + escape(role) + "</strong>" + escape(at) + "."
                        + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:14px;color:" + COLOR_TEXT_HINT + ";\">"
                        + "Our recruiting team will reach out shortly with the next steps, which "
                        + "typically include an interview."
                        + "</p>"
        );
        send(email, "You've been shortlisted — " + brand, plain, html);
    }

    @Override
    public void sendApplicationRejected(String email, String candidateName,
                                        String jobTitle, String entityName) {
        String role = jobTitle != null ? jobTitle : "the role";
        String at = entityName != null ? " at " + entityName : "";
        String greet = greeting(candidateName);
        String plain = ""
                + greet + "\n\n"
                + "Thanks for your interest in " + role + at + " and for the time you put\n"
                + "into your application.\n\n"
                + "After careful review, we've decided to move forward with other candidates\n"
                + "whose experience more closely matches what we're looking for right now.\n\n"
                + "This isn't a reflection of your potential — keep an eye on " + product + "\n"
                + "for roles that better match your strengths; you're welcome to apply again.\n\n"
                + "Wishing you the very best with your search.\n\n"
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Update on your application",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Thanks for your interest in <strong>" + escape(role) + "</strong>"
                        + escape(at) + " and for the time you put into your application."
                        + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:14px;color:" + COLOR_TEXT_HINT + ";\">"
                        + "After careful review, we've decided to move forward with other candidates "
                        + "whose experience more closely matches what we're looking for right now."
                        + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:14px;color:" + COLOR_TEXT_HINT + ";\">"
                        + "This isn't a reflection of your potential — keep an eye on " + product + " "
                        + "for roles that better match your strengths; you're welcome to apply again."
                        + "</p>"
                        + "<p style=\"margin:16px 0 0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + "Wishing you the very best with your search."
                        + "</p>"
        );
        send(email, "Update on your application — " + brand, plain, html);
    }

    @Override
    public void sendInterviewScheduled(String email, String candidateName,
                                       String jobTitle, String entityName,
                                       Instant scheduledAt, Integer durationMinutes,
                                       String interviewType, String interviewerName,
                                       String meetingUrl, String candidateNotes) {
        String role = jobTitle != null ? jobTitle : "the role";
        String at = entityName != null ? " at " + entityName : "";
        String greet = greeting(candidateName);
        String when = scheduledAt != null ? INTERVIEW_FORMAT.format(scheduledAt) : "TBD";
        String duration = durationMinutes != null ? (durationMinutes + " minutes") : "—";
        String mode = interviewType != null ? interviewType.replace('_', ' ').toLowerCase() : "interview";
        String plain = ""
                + greet + "\n\n"
                + "Your interview for " + role + at + " is scheduled.\n\n"
                + "When:        " + when + "\n"
                + "Duration:    " + duration + "\n"
                + "Type:        " + mode + "\n"
                + (interviewerName != null ? "Interviewer: " + interviewerName + "\n" : "")
                + (meetingUrl != null ? "Link:        " + meetingUrl + "\n" : "")
                + (candidateNotes != null ? "\nNotes from the team:\n" + candidateNotes + "\n" : "")
                + "\nIf you need to reschedule, reply to your recruiter ASAP.\n\n"
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Your interview is scheduled",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Your interview for <strong>" + escape(role) + "</strong>"
                        + escape(at) + " is scheduled."
                        + "</p>"
                        + interviewDetailsBlock(when, duration, mode, interviewerName, meetingUrl)
                        + (meetingUrl != null
                            ? buttonBlock("Join the meeting", meetingUrl)
                            : "")
                        + (candidateNotes != null
                            ? "<p style=\"margin:0 0 8px;font-size:13px;font-weight:600;color:"
                                + COLOR_TEXT_BODY + ";\">Notes from the team</p>"
                              + "<p style=\"margin:0 0 12px;font-size:13px;color:" + COLOR_TEXT_HINT
                                + ";white-space:pre-wrap;\">" + escape(candidateNotes) + "</p>"
                            : "")
                        + "<p style=\"margin:16px 0 0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + "If you need to reschedule, reply to your recruiter as soon as you can."
                        + "</p>"
        );
        send(email, "Interview scheduled — " + brand, plain, html);
    }

    @Override
    public void sendInterviewReminder(String email, String candidateName,
                                      String jobTitle, String entityName,
                                      Instant scheduledAt, Integer durationMinutes,
                                      String interviewType, String interviewerName,
                                      String meetingUrl) {
        String role = jobTitle != null ? jobTitle : "the role";
        String greet = greeting(candidateName);
        String when = scheduledAt != null ? INTERVIEW_FORMAT.format(scheduledAt) : "tomorrow";
        String duration = durationMinutes != null ? (durationMinutes + " minutes") : "—";
        String mode = interviewType != null ? interviewType.replace('_', ' ').toLowerCase() : "interview";
        String plain = ""
                + greet + "\n\n"
                + "Quick reminder — your interview for " + role + " is tomorrow.\n\n"
                + "When:        " + when + "\n"
                + "Duration:    " + duration + "\n"
                + "Type:        " + mode + "\n"
                + (interviewerName != null ? "Interviewer: " + interviewerName + "\n" : "")
                + (meetingUrl != null ? "Link:        " + meetingUrl + "\n" : "")
                + "\nGood luck!\n\n"
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Interview reminder — tomorrow",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Quick reminder — your interview for <strong>" + escape(role) + "</strong>"
                        + " is <strong>tomorrow</strong>."
                        + "</p>"
                        + interviewDetailsBlock(when, duration, mode, interviewerName, meetingUrl)
                        + (meetingUrl != null
                            ? buttonBlock("Join the meeting", meetingUrl)
                            : "")
                        + "<p style=\"margin:16px 0 0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + "Good luck!"
                        + "</p>"
        );
        send(email, "Interview reminder — " + brand, plain, html);
    }

    @Override
    public void sendOfferExtended(String email, String candidateName,
                                  String jobTitle, String entityName,
                                  BigDecimal compensationAmount, String compensationCurrency,
                                  String compensationFrequency, LocalDate startDate,
                                  Instant expiresAt, String viewOfferUrl) {
        String role = jobTitle != null ? jobTitle : "the role";
        String at = entityName != null ? " at " + entityName : "";
        String greet = greeting(candidateName);
        String comp = formatComp(compensationAmount, compensationCurrency, compensationFrequency);
        String start = startDate != null ? DATE_FORMAT.format(startDate) : "TBD";
        String expires = expiresAt != null ? EXPIRY_FORMAT.format(expiresAt) : "soon";
        String plain = ""
                + greet + "\n\n"
                + "Congratulations — we'd like to offer you " + role + at + ".\n\n"
                + "Compensation: " + comp + "\n"
                + "Start date:   " + start + "\n"
                + "Respond by:   " + expires + "\n\n"
                + "Review the full letter and respond from your " + product + " dashboard:\n"
                + (viewOfferUrl != null ? viewOfferUrl + "\n\n" : "")
                + "We're excited about the prospect of working together.\n\n"
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Your offer is here",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Congratulations — we'd like to offer you <strong>"
                        + escape(role) + "</strong>" + escape(at) + "."
                        + "</p>"
                        + offerDetailsBlock(comp, start, expires)
                        + (viewOfferUrl != null
                            ? buttonBlock("Review and respond", viewOfferUrl)
                            : "")
                        + "<p style=\"margin:16px 0 0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + "We're excited about the prospect of working together."
                        + "</p>"
        );
        send(email, "Your offer from " + brand, plain, html);
    }

    @Override
    public void sendOfferAccepted(String email, String candidateName,
                                  String jobTitle, String entityName,
                                  LocalDate startDate) {
        String role = jobTitle != null ? jobTitle : "the role";
        String at = entityName != null ? " at " + entityName : "";
        String greet = greeting(candidateName);
        String start = startDate != null ? DATE_FORMAT.format(startDate) : "TBD";
        String plain = ""
                + greet + "\n\n"
                + "Welcome to " + brand + " — we've recorded your acceptance for " + role + at + ".\n\n"
                + "Start date: " + start + "\n\n"
                + "Our team will be in touch with onboarding steps shortly. In the meantime,\n"
                + "track outstanding compliance tasks from your " + product + " dashboard.\n\n"
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Welcome to " + brand,
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "We've recorded your acceptance for <strong>" + escape(role) + "</strong>"
                        + escape(at) + "."
                        + "</p>"
                        + miniRow("Start date", start)
                        + "<p style=\"margin:14px 0 0;font-size:14px;color:" + COLOR_TEXT_HINT + ";\">"
                        + "Our team will be in touch with onboarding steps shortly. In the meantime, "
                        + "track outstanding compliance tasks from your " + product + " dashboard."
                        + "</p>"
        );
        send(email, "Acceptance confirmed — " + brand, plain, html);
    }

    @Override
    public void sendOfferAcceptedToOps(String opsEmail, String candidateName,
                                       String candidateEmail, String jobTitle,
                                       String entityName, LocalDate startDate) {
        String role = jobTitle != null ? jobTitle : "the role";
        String at = entityName != null ? " at " + entityName : "";
        String start = startDate != null ? DATE_FORMAT.format(startDate) : "TBD";
        String plain = ""
                + "Offer accepted.\n\n"
                + "Candidate:  " + (candidateName != null ? candidateName : "—")
                + (candidateEmail != null ? " <" + candidateEmail + ">" : "") + "\n"
                + "Role:       " + role + at + "\n"
                + "Start date: " + start + "\n\n"
                + "Next: verify onboarding tasks seeded, confirm compliance routing,\n"
                + "and prepare engagement activation.\n\n"
                + "— " + product + " ops bot\n";
        String html = wrapHtml(
                "Offer accepted — operations heads-up",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "An offer has been <strong>accepted</strong>."
                        + "</p>"
                        + miniRow("Candidate",
                            (candidateName != null ? candidateName : "—")
                            + (candidateEmail != null ? " &lt;" + escape(candidateEmail) + "&gt;" : ""))
                        + miniRow("Role", escape(role) + escape(at))
                        + miniRow("Start date", start)
                        + "<p style=\"margin:14px 0 0;font-size:13px;color:" + COLOR_TEXT_HINT + ";\">"
                        + "Next: verify onboarding tasks seeded, confirm compliance routing, and "
                        + "prepare engagement activation."
                        + "</p>"
        );
        send(opsEmail, "Offer accepted — " + (candidateName != null ? candidateName : "candidate"),
                plain, html);
    }

    @Override
    public void sendOnboardingWelcome(String email, String internName,
                                      String jobTitle, String entityName,
                                      LocalDate startDate, String dashboardUrl) {
        String role = jobTitle != null ? jobTitle : "your role";
        String at = entityName != null ? " at " + entityName : "";
        String greet = greeting(internName);
        String start = startDate != null ? DATE_FORMAT.format(startDate) : "today";
        String plain = ""
                + greet + "\n\n"
                + "Welcome to " + brand + "! Your engagement for " + role + at + " is now active.\n\n"
                + "Start date: " + start + "\n\n"
                + "Your " + product + " dashboard is the home for your weekly materials,\n"
                + "reports, projects, training plan, and evaluations.\n"
                + (dashboardUrl != null ? "\nOpen your dashboard: " + dashboardUrl + "\n\n" : "\n")
                + "We're glad to have you on the team.\n\n"
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Welcome aboard — let's get started",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Welcome to <strong>" + brand + "</strong>! Your engagement for "
                        + "<strong>" + escape(role) + "</strong>" + escape(at) + " is now active."
                        + "</p>"
                        + miniRow("Start date", start)
                        + "<p style=\"margin:14px 0 12px;font-size:14px;color:" + COLOR_TEXT_HINT + ";\">"
                        + "Your " + product + " dashboard is the home for your weekly materials, "
                        + "reports, projects, training plan, and evaluations."
                        + "</p>"
                        + (dashboardUrl != null
                            ? buttonBlock("Open your dashboard", dashboardUrl)
                            : "")
                        + "<p style=\"margin:16px 0 0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + "We're glad to have you on the team."
                        + "</p>"
        );
        send(email, "Welcome to " + brand, plain, html);
    }

    // ── Batch 2 — compliance / onboarding ───────────────────────────────────
    // PII RULE: every body below contains ONLY status + names + dates + URLs.
    // Never SSN, document numbers, DOB, or addresses. The CTA points back to
    // the dashboard where the real (decrypted, access-controlled) data lives.

    @Override
    public void sendI9Section1Reminder(String email, String internName,
                                       LocalDate section1DueDate, String dashboardUrl) {
        String greet = greeting(internName);
        String due = section1DueDate != null ? DATE_FORMAT.format(section1DueDate) : "soon";
        String plain = ""
                + greet + "\n\n"
                + "Quick reminder — your I-9 Section 1 is still pending.\n\n"
                + "Section 1 due: " + due + "\n\n"
                + "Sign in to complete it from your dashboard:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Your I-9 Section 1 is pending",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Quick reminder — your <strong>I-9 Section 1</strong> is still pending. "
                        + "It's a federal employment-eligibility form we need on file before your "
                        + "first day."
                        + "</p>"
                        + miniRow("Section 1 due", escape(due))
                        + (dashboardUrl != null ? buttonBlock("Complete Section 1", dashboardUrl) : "")
                        + "<p style=\"margin:16px 0 0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + "If you've already submitted it, you can ignore this reminder."
                        + "</p>"
        );
        send(email, "I-9 Section 1 reminder — " + brand, plain, html);
    }

    @Override
    public void sendI9Section2Pending(String hrEmail, String internName,
                                      LocalDate section2DueDate, String hrDashboardUrl) {
        String due = section2DueDate != null ? DATE_FORMAT.format(section2DueDate) : "—";
        String who = internName != null ? internName : "an intern";
        String plain = ""
                + "Heads up — " + who + " has completed I-9 Section 1.\n\n"
                + "Section 2 due: " + due + "\n\n"
                + "Open the I-9 in the HR dashboard to complete Section 2:\n"
                + (hrDashboardUrl != null ? hrDashboardUrl + "\n\n" : "\n")
                + "— " + product + " ops bot\n";
        String html = wrapHtml(
                "I-9 Section 2 is now due",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "<strong>" + escape(who) + "</strong> has completed I-9 Section 1. "
                        + "Section 2 is now due."
                        + "</p>"
                        + miniRow("Section 2 due", escape(due))
                        + (hrDashboardUrl != null
                            ? buttonBlock("Complete Section 2", hrDashboardUrl) : "")
        );
        send(hrEmail, "I-9 Section 2 pending — " + who, plain, html);
    }

    @Override
    public void sendI983PlanNeeded(String email, String internName, String dashboardUrl) {
        String greet = greeting(internName);
        String plain = ""
                + greet + "\n\n"
                + "Because your engagement is on the STEM OPT track, we need your I-983\n"
                + "training plan on file before training begins.\n\n"
                + "Sign in to provide your details and sign the plan:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "I-983 training plan needed",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Because your engagement is on the <strong>STEM OPT</strong> track, we "
                        + "need your <strong>I-983 training plan</strong> on file before training "
                        + "begins."
                        + "</p>"
                        + (dashboardUrl != null
                            ? buttonBlock("Open the I-983 plan", dashboardUrl) : "")
                        + "<p style=\"margin:16px 0 0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + "Your DSO will use the same plan, so the sooner you submit, the smoother "
                        + "your STEM OPT review will be."
                        + "</p>"
        );
        send(email, "I-983 training plan needed — " + brand, plain, html);
    }

    @Override
    public void sendI983PlanReady(String hrEmail, String internName, String hrDashboardUrl) {
        String who = internName != null ? internName : "the intern";
        String plain = ""
                + "Heads up — " + who + " has signed their I-983 training plan.\n\n"
                + "It's ready for the employer signature. Open the plan in the HR\n"
                + "dashboard to review and sign:\n"
                + (hrDashboardUrl != null ? hrDashboardUrl + "\n\n" : "\n")
                + "— " + product + " ops bot\n";
        String html = wrapHtml(
                "I-983 plan ready for employer signature",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "<strong>" + escape(who) + "</strong> has signed their I-983 training "
                        + "plan. It's ready for the <strong>employer signature</strong>."
                        + "</p>"
                        + (hrDashboardUrl != null
                            ? buttonBlock("Review and sign", hrDashboardUrl) : "")
        );
        send(hrEmail, "I-983 ready for employer signature — " + who, plain, html);
    }

    @Override
    public void sendEVerifyCaseOpened(String email, String internName, String dashboardUrl) {
        String greet = greeting(internName);
        String plain = ""
                + greet + "\n\n"
                + "Your E-Verify case has been opened. This is the federal employment-\n"
                + "eligibility check; in most cases no action is required on your part.\n\n"
                + "We'll let you know if anything changes. You can also check progress\n"
                + "from your dashboard:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Your E-Verify case is open",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Your <strong>E-Verify</strong> case has been opened. This is the federal "
                        + "employment-eligibility check; in most cases no action is required on "
                        + "your part."
                        + "</p>"
                        + (dashboardUrl != null ? buttonBlock("Check status", dashboardUrl) : "")
                        + "<p style=\"margin:16px 0 0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + "We'll email you if anything changes."
                        + "</p>"
        );
        send(email, "E-Verify case opened — " + brand, plain, html);
    }

    @Override
    public void sendEVerifyTncAlert(String email, String internName, String dashboardUrl) {
        String greet = greeting(internName);
        String plain = ""
                + greet + "\n\n"
                + "ACTION REQUIRED — your E-Verify case has come back as a\n"
                + "Tentative Nonconfirmation (TNC). This means the federal system\n"
                + "couldn't immediately confirm your work eligibility.\n\n"
                + "A TNC is NOT a final answer. You have the right to contest it,\n"
                + "and " + brand + " will walk you through the steps. Sign in to your\n"
                + "dashboard right away:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "Time matters here — please act today.\n\n"
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Urgent — E-Verify action required",
                // Red urgency banner — only place we deviate from the calm
                // brand tone, intentional for the highest-impact email.
                "<div style=\"margin:0 0 16px;padding:12px 16px;border-radius:8px;"
                        + "background:#fef2f2;border:1px solid #fecaca;color:#991b1b;"
                        + "font-size:13px;font-weight:600;\">"
                        + "Action required — please respond today."
                        + "</div>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Your <strong>E-Verify</strong> case has come back as a "
                        + "<strong>Tentative Nonconfirmation (TNC)</strong>. This means the federal "
                        + "system couldn't immediately confirm your work eligibility."
                        + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:14px;color:" + COLOR_TEXT_HINT + ";\">"
                        + "A TNC is <strong>not</strong> a final answer. You have the right to "
                        + "contest it, and " + brand + " will walk you through the steps."
                        + "</p>"
                        + (dashboardUrl != null
                            ? buttonBlock("Take action now", dashboardUrl) : "")
                        + "<p style=\"margin:16px 0 0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + "Time matters here — please act today."
                        + "</p>"
        );
        send(email, "URGENT — E-Verify action required", plain, html);
    }

    @Override
    public void sendEVerifyCleared(String email, String internName, String dashboardUrl) {
        String greet = greeting(internName);
        String plain = ""
                + greet + "\n\n"
                + "Great news — your E-Verify case has cleared. Federal work\n"
                + "authorization is confirmed; no further action is needed.\n\n"
                + "You can view the case status from your dashboard:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "E-Verify cleared",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Great news — your <strong>E-Verify</strong> case has cleared. Federal "
                        + "work authorization is confirmed; no further action is needed."
                        + "</p>"
                        + (dashboardUrl != null ? buttonBlock("View status", dashboardUrl) : "")
        );
        send(email, "E-Verify cleared — " + brand, plain, html);
    }

    @Override
    public void sendWorkAuthExpiryReminder(String email, String internName,
                                           int daysUntilExpiry, LocalDate expirationDate,
                                           String authType, String dashboardUrl) {
        String greet = greeting(internName);
        String type = (authType != null && !authType.isBlank()) ? authType : "Work authorization";
        String when = expirationDate != null ? DATE_FORMAT.format(expirationDate) : "soon";
        boolean urgent = daysUntilExpiry <= 14;
        String urgencyPhrase = urgent
                ? "Time is short — please act this week."
                : "Plan ahead so the renewal lands before the deadline.";
        String plain = ""
                + greet + "\n\n"
                + type + " expiring in " + daysUntilExpiry + " day"
                + (daysUntilExpiry == 1 ? "" : "s") + ".\n\n"
                + "Authorization type: " + type + "\n"
                + "Expires:            " + when + "\n\n"
                + urgencyPhrase + "\n"
                + "Open your " + product + " dashboard to start the renewal process:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                type + " expires in " + daysUntilExpiry + " day"
                        + (daysUntilExpiry == 1 ? "" : "s"),
                (urgent
                        ? "<div style=\"margin:0 0 16px;padding:12px 16px;border-radius:8px;"
                            + "background:#fff7ed;border:1px solid #fed7aa;color:#7c2d12;"
                            + "font-size:13px;font-weight:600;\">"
                            + "Time-sensitive — renewal should be in progress already."
                            + "</div>"
                        : "")
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Your <strong>" + escape(type) + "</strong> is set to expire in "
                        + "<strong>" + daysUntilExpiry + " day"
                        + (daysUntilExpiry == 1 ? "" : "s") + "</strong>."
                        + "</p>"
                        + miniRow("Authorization", escape(type))
                        + miniRow("Expires", escape(when))
                        + (dashboardUrl != null
                            ? buttonBlock("Start the renewal", dashboardUrl) : "")
                        + "<p style=\"margin:16px 0 0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + escape(urgencyPhrase)
                        + "</p>"
        );
        send(email,
                (urgent ? "URGENT — " : "")
                        + type + " expires in " + daysUntilExpiry + "d — " + brand,
                plain, html);
    }

    @Override
    public void sendComplianceTaskReminder(String email, String internName,
                                           String taskTitle, LocalDate dueDate,
                                           Integer daysOverdue, String dashboardUrl) {
        String greet = greeting(internName);
        String title = taskTitle != null ? taskTitle : "a compliance task";
        String due = dueDate != null ? DATE_FORMAT.format(dueDate) : "—";
        String overdueLabel = daysOverdue != null && daysOverdue > 0
                ? daysOverdue + " day" + (daysOverdue == 1 ? "" : "s") + " overdue"
                : "still pending";
        String plain = ""
                + greet + "\n\n"
                + "Reminder — \"" + title + "\" is " + overdueLabel + ".\n\n"
                + "Due date:   " + due + "\n"
                + "Status:     " + overdueLabel + "\n\n"
                + "Open your " + product + " dashboard to complete it:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Compliance task reminder",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Reminder — <strong>" + escape(title) + "</strong> is "
                        + "<strong>" + escape(overdueLabel) + "</strong>."
                        + "</p>"
                        + miniRow("Due date", escape(due))
                        + miniRow("Status", escape(overdueLabel))
                        + (dashboardUrl != null
                            ? buttonBlock("Open dashboard", dashboardUrl) : "")
        );
        send(email, "Reminder: " + title + " — " + brand, plain, html);
    }

    // ── Batch 3 — intern weekly cycle ───────────────────────────────────────
    // sendWeeklyMaterialReleased + sendMaterialUnreadReminder removed in
    // Trainer Phase 0 (concept not in Trainer doc spec).

    @Override
    public void sendWeeklyReportDue(String email, String internName,
                                    LocalDate weekStart, String dashboardUrl) {
        String greet = greeting(internName);
        String week = weekStart != null ? DATE_FORMAT.format(weekStart) : "this week";
        String plain = ""
                + greet + "\n\n"
                + "Friendly reminder — your weekly report for the week of " + week + "\n"
                + "isn't submitted yet. The supervisor reviews these end-of-week.\n\n"
                + "Open your dashboard to submit it:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Submit your weekly report",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Friendly reminder — your weekly report for the week of "
                        + "<strong>" + escape(week) + "</strong> isn't submitted yet."
                        + "</p>"
                        + (dashboardUrl != null ? buttonBlock("Open weekly report", dashboardUrl) : "")
        );
        send(email, "Weekly report due — " + brand, plain, html);
    }

    @Override
    public void sendWeeklyReportReturned(String email, String internName,
                                         LocalDate weekStart, String reviewNotes,
                                         String dashboardUrl) {
        String greet = greeting(internName);
        String week = weekStart != null ? DATE_FORMAT.format(weekStart) : "your report";
        String notes = reviewNotes != null && !reviewNotes.isBlank() ? reviewNotes : null;
        String plain = ""
                + greet + "\n\n"
                + "Your supervisor returned your report for the week of " + week + "\n"
                + "with notes for revisions.\n\n"
                + (notes != null ? "Notes from your supervisor:\n" + notes + "\n\n" : "")
                + "Open your dashboard to address the changes and resubmit:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Your weekly report needs changes",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Your supervisor returned your report for the week of "
                        + "<strong>" + escape(week) + "</strong> with notes for revisions."
                        + "</p>"
                        + (notes != null
                            ? "<p style=\"margin:0 0 8px;font-size:13px;font-weight:600;color:"
                                + COLOR_TEXT_BODY + ";\">Notes from your supervisor</p>"
                              + "<p style=\"margin:0 0 12px;font-size:13px;color:" + COLOR_TEXT_HINT
                                + ";white-space:pre-wrap;\">" + escape(notes) + "</p>"
                            : "")
                        + (dashboardUrl != null ? buttonBlock("Open and revise", dashboardUrl) : "")
        );
        send(email, "Report returned — please revise — " + brand, plain, html);
    }

    @Override
    public void sendWeeklyReportApproved(String email, String internName,
                                         LocalDate weekStart, String dashboardUrl) {
        String greet = greeting(internName);
        String week = weekStart != null ? DATE_FORMAT.format(weekStart) : "your report";
        String plain = ""
                + greet + "\n\n"
                + "Nice work — your weekly report for the week of " + week + " has been\n"
                + "approved.\n\n"
                + "Review it any time from your dashboard:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Your weekly report is approved",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Nice work — your weekly report for the week of "
                        + "<strong>" + escape(week) + "</strong> has been "
                        + "<strong>approved</strong>."
                        + "</p>"
                        + (dashboardUrl != null ? buttonBlock("View report", dashboardUrl) : "")
        );
        send(email, "Report approved — " + brand, plain, html);
    }

    @Override
    public void sendTimesheetDue(String email, String internName,
                                 LocalDate weekStart, String dashboardUrl) {
        String greet = greeting(internName);
        String week = weekStart != null ? DATE_FORMAT.format(weekStart) : "this week";
        String plain = ""
                + greet + "\n\n"
                + "Reminder — please log your hours for the week of " + week + ".\n\n"
                + "Open your timesheet from the dashboard:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Log this week's hours",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Reminder — please log your hours for the week of "
                        + "<strong>" + escape(week) + "</strong>."
                        + "</p>"
                        + (dashboardUrl != null ? buttonBlock("Open timesheet", dashboardUrl) : "")
        );
        send(email, "Timesheet due — " + brand, plain, html);
    }

    @Override
    public void sendProjectAssigned(String email, String internName,
                                    String projectTitle, LocalDate dueDate,
                                    String supervisorName, String dashboardUrl) {
        String greet = greeting(internName);
        String title = projectTitle != null ? projectTitle : "a new project";
        String by = supervisorName != null ? supervisorName : "your supervisor";
        String due = dueDate != null ? DATE_FORMAT.format(dueDate) : "—";
        String plain = ""
                + greet + "\n\n"
                + by + " just allocated a new project to you.\n\n"
                + "Project:  " + title + "\n"
                + "Due date: " + due + "\n\n"
                + "Open it from your dashboard to read the deliverables:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "A new project is yours",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "<strong>" + escape(by) + "</strong> just allocated a new project to you."
                        + "</p>"
                        + miniRow("Project", escape(title))
                        + miniRow("Due date", escape(due))
                        + (dashboardUrl != null ? buttonBlock("Open project", dashboardUrl) : "")
        );
        send(email, "New project assigned — " + brand, plain, html);
    }

    @Override
    public void sendProjectSubmitted(String supervisorEmail, String supervisorName,
                                     String internName, String projectTitle,
                                     String supervisorDashboardUrl) {
        String greet = greeting(supervisorName);
        String title = projectTitle != null ? projectTitle : "their project";
        String who = internName != null ? internName : "an intern";
        String plain = ""
                + greet + "\n\n"
                + who + " just submitted \"" + title + "\" for your review.\n\n"
                + "Open it from your dashboard:\n"
                + (supervisorDashboardUrl != null ? supervisorDashboardUrl + "\n\n" : "\n")
                + "— " + product + " ops bot\n";
        String html = wrapHtml(
                "Project submission — " + title,
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "<strong>" + escape(who) + "</strong> just submitted "
                        + "<strong>" + escape(title) + "</strong> for your review."
                        + "</p>"
                        + (supervisorDashboardUrl != null
                            ? buttonBlock("Open and review", supervisorDashboardUrl) : "")
        );
        send(supervisorEmail, "Project submitted by " + who + " — " + brand, plain, html);
    }

    @Override
    public void sendProjectReturned(String email, String internName,
                                    String projectTitle, String reviewNotes,
                                    String dashboardUrl) {
        String greet = greeting(internName);
        String title = projectTitle != null ? projectTitle : "your project";
        String notes = reviewNotes != null && !reviewNotes.isBlank() ? reviewNotes : null;
        String plain = ""
                + greet + "\n\n"
                + "Your supervisor returned \"" + title + "\" for changes.\n\n"
                + (notes != null ? "Notes from your supervisor:\n" + notes + "\n\n" : "")
                + "Open it from your dashboard to revise and resubmit:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Project returned — please revise",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Your supervisor returned <strong>" + escape(title) + "</strong> for "
                        + "changes."
                        + "</p>"
                        + (notes != null
                            ? "<p style=\"margin:0 0 8px;font-size:13px;font-weight:600;color:"
                                + COLOR_TEXT_BODY + ";\">Notes from your supervisor</p>"
                              + "<p style=\"margin:0 0 12px;font-size:13px;color:" + COLOR_TEXT_HINT
                                + ";white-space:pre-wrap;\">" + escape(notes) + "</p>"
                            : "")
                        + (dashboardUrl != null ? buttonBlock("Open and revise", dashboardUrl) : "")
        );
        send(email, "Project returned: " + title + " — " + brand, plain, html);
    }

    @Override
    public void sendProjectCompleted(String email, String internName,
                                     String projectTitle, String dashboardUrl) {
        String greet = greeting(internName);
        String title = projectTitle != null ? projectTitle : "your project";
        String plain = ""
                + greet + "\n\n"
                + "Great work — \"" + title + "\" has been marked complete by your supervisor.\n\n"
                + "Review the closing notes any time from your dashboard:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Project completed",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Great work — <strong>" + escape(title) + "</strong> has been marked "
                        + "<strong>complete</strong> by your supervisor."
                        + "</p>"
                        + (dashboardUrl != null ? buttonBlock("View project", dashboardUrl) : "")
        );
        send(email, "Project completed: " + title + " — " + brand, plain, html);
    }

    @Override
    public void sendEvaluationDue(String supervisorEmail, String supervisorName,
                                  String internName, String evaluationType,
                                  Integer daysInDraft, String supervisorDashboardUrl) {
        String greet = greeting(supervisorName);
        String who = internName != null ? internName : "your intern";
        String type = evaluationType != null ? evaluationType.replace('_', ' ') : "Evaluation";
        String ageLabel = daysInDraft != null && daysInDraft > 0
                ? daysInDraft + " day" + (daysInDraft == 1 ? "" : "s") + " in draft"
                : "still in draft";
        String plain = ""
                + greet + "\n\n"
                + "Reminder — the " + type + " for " + who + " is " + ageLabel + ".\n\n"
                + "Open it from your supervisor dashboard to finalize:\n"
                + (supervisorDashboardUrl != null ? supervisorDashboardUrl + "\n\n" : "\n")
                + "— " + product + " ops bot\n";
        String html = wrapHtml(
                "Evaluation pending — " + who,
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Reminder — the <strong>" + escape(type) + "</strong> for "
                        + "<strong>" + escape(who) + "</strong> is "
                        + "<strong>" + escape(ageLabel) + "</strong>."
                        + "</p>"
                        + (supervisorDashboardUrl != null
                            ? buttonBlock("Open and finalize", supervisorDashboardUrl) : "")
        );
        send(supervisorEmail, "Evaluation pending — " + who + " — " + brand, plain, html);
    }

    @Override
    public void sendEvaluationFinalized(String email, String internName,
                                        String evaluationType, String supervisorName,
                                        Integer overallRating, String dashboardUrl) {
        String greet = greeting(internName);
        String type = evaluationType != null ? evaluationType.replace('_', ' ') : "Evaluation";
        String by = supervisorName != null ? supervisorName : "your supervisor";
        String rating = overallRating != null ? (overallRating + " / 5") : "—";
        String plain = ""
                + greet + "\n\n"
                + by + " finalized your " + type + " evaluation.\n\n"
                + "Overall rating: " + rating + "\n\n"
                + "Read the full evaluation from your dashboard:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Your evaluation is ready",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "<strong>" + escape(by) + "</strong> finalized your "
                        + "<strong>" + escape(type) + "</strong> evaluation."
                        + "</p>"
                        + miniRow("Overall rating", escape(rating))
                        + (dashboardUrl != null ? buttonBlock("Read your evaluation", dashboardUrl) : "")
        );
        send(email, type + " evaluation finalized — " + brand, plain, html);
    }

    @Override
    public void sendI983SelfEvalDue(String email, String internName,
                                    String evaluationType, String dashboardUrl) {
        String greet = greeting(internName);
        String type = evaluationType != null ? evaluationType.replace('_', ' ') : "I-983";
        String plain = ""
                + greet + "\n\n"
                + "Your " + type + " self-evaluation is awaiting your reflection.\n\n"
                + "Add your reflection and ratings before your supervisor finalizes:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "I-983 self-evaluation due",
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Your <strong>" + escape(type) + "</strong> self-evaluation is awaiting "
                        + "your reflection."
                        + "</p>"
                        + (dashboardUrl != null ? buttonBlock("Add self-review", dashboardUrl) : "")
                        + "<p style=\"margin:16px 0 0;font-size:13px;color:" + COLOR_TEXT_MUTED + ";\">"
                        + "Your supervisor will see this alongside their evaluation."
                        + "</p>"
        );
        send(email, type + " self-evaluation due — " + brand, plain, html);
    }

    // ── Two-role workflow (P1b) ─────────────────────────────────────────────

    @Override
    public void sendProjectTechApproved(String email, String internName,
                                        String projectTitle, String dashboardUrl) {
        String greet = greeting(internName);
        String title = projectTitle != null ? projectTitle : "your project";
        String plain = ""
                + greet + "\n\n"
                + "Your technical supervisor signed off on \"" + title + "\".\n\n"
                + "Next: your Reporting Manager will schedule a brief viva to "
                + "wrap things up.\n\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Technical approval — " + title,
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Your technical supervisor signed off on <strong>"
                        + escape(title) + "</strong>."
                        + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:14px;color:" + COLOR_TEXT_HINT + ";\">"
                        + "Next, your Reporting Manager will schedule a brief viva to wrap "
                        + "things up."
                        + "</p>"
                        + (dashboardUrl != null ? buttonBlock("View project", dashboardUrl) : "")
        );
        send(email, "Project tech-approved: " + title + " — " + brand, plain, html);
    }

    @Override
    public void sendProjectReturnedForRevisions(String email, String internName,
                                                String projectTitle, String reason,
                                                String dashboardUrl) {
        String greet = greeting(internName);
        String title = projectTitle != null ? projectTitle : "your project";
        String notes = reason != null && !reason.isBlank() ? reason : null;
        String plain = ""
                + greet + "\n\n"
                + "A reviewer sent \"" + title + "\" back for revisions.\n\n"
                + (notes != null ? "Notes:\n" + notes + "\n\n" : "")
                + "Open the project to revise and resubmit:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Revisions requested — " + title,
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "A reviewer sent <strong>" + escape(title) + "</strong> back "
                        + "for revisions."
                        + "</p>"
                        + (notes != null
                            ? "<p style=\"margin:0 0 8px;font-size:13px;font-weight:600;color:"
                                + COLOR_TEXT_BODY + ";\">Notes from your reviewer</p>"
                              + "<p style=\"margin:0 0 12px;font-size:13px;color:" + COLOR_TEXT_HINT
                                + ";white-space:pre-wrap;\">" + escape(notes) + "</p>"
                            : "")
                        + (dashboardUrl != null ? buttonBlock("Open project", dashboardUrl) : "")
        );
        send(email, "Project returned for revisions: " + title + " — " + brand, plain, html);
    }

    @Override
    public void sendProjectPendingViva(String email, String internName,
                                       String projectTitle, String dashboardUrl) {
        String greet = greeting(internName);
        String title = projectTitle != null ? projectTitle : "your project";
        String plain = ""
                + greet + "\n\n"
                + "Your Reporting Manager is set up to run the viva for \"" + title + "\".\n\n"
                + "Check your dashboard for the time and any pre-read notes:\n"
                + (dashboardUrl != null ? dashboardUrl + "\n\n" : "\n")
                + "— The " + brand + " team\n";
        String html = wrapHtml(
                "Viva scheduled — " + title,
                "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + escape(greet) + "</p>"
                        + "<p style=\"margin:0 0 12px;font-size:15px;color:" + COLOR_TEXT_BODY + ";\">"
                        + "Your Reporting Manager is set up to run the viva for <strong>"
                        + escape(title) + "</strong>."
                        + "</p>"
                        + (dashboardUrl != null ? buttonBlock("View project", dashboardUrl) : "")
        );
        send(email, "Viva scheduled: " + title + " — " + brand, plain, html);
    }

    // ── Wire ────────────────────────────────────────────────────────────────

    private void send(String to, String subject, String plain, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(mailFromAddress, mailFromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(plain, html); // plain first, html second → multipart/alternative
            mailSender.send(message);
            log.info("Sent email '{}' to {}", subject, to);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            log.error("Email send failed — subject='{}' to={} : {}",
                    subject, to, e.getMessage());
            throw new EmailDeliveryException(
                    "Couldn't deliver email to " + to, e);
        }
    }

    // ── Shared base template ────────────────────────────────────────────────

    /**
     * Branded outer template every email body is wrapped in. Logo header with
     * the dark navy brand bar, white content card, accent footer band, and
     * a muted disclaimer line. The body fragment is dropped inside the card.
     */
    private String wrapHtml(String heading, String bodyHtml) {
        // Brand split for the header lockup: render the first word plain
        // and any trailing word(s) in the accent gradient (preserves the
        // Anvi Corp "Tech" treatment for the default and works for any
        // two-word brand). Single-word brands render with no gradient.
        String[] brandParts = brand.split("\\s+", 2);
        String brandLead = brandParts[0];
        String brandTail = brandParts.length > 1 ? brandParts[1] : null;
        return "<!doctype html><html><body style=\"margin:0;padding:0;background:" + COLOR_BODY_BG + ";"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',"
                + "Arial,sans-serif;color:" + COLOR_TEXT_PRIMARY + ";\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"background:" + COLOR_BODY_BG + ";padding:24px 0;\">"
                + "<tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"max-width:560px;width:100%;background:" + COLOR_CARD_BG + ";"
                + "border:1px solid " + COLOR_CARD_BORDER + ";border-radius:12px;overflow:hidden;\">"
                // Header — dark navy bar with logo
                + "<tr><td style=\"background:" + COLOR_HEADER_BG + ";padding:20px 28px;\">"
                + "<a href=\"" + escape(brandUrl) + "\" "
                + "style=\"display:inline-block;text-decoration:none;color:" + COLOR_HEADER_TEXT + ";\">"
                + "<img src=\"" + escape(logoUrl) + "\" alt=\"" + brand + "\" "
                + "height=\"32\" width=\"32\" "
                + "style=\"vertical-align:middle;border:0;display:inline-block;height:32px;width:auto;"
                + "margin-right:10px;\"/>"
                + "<span style=\"vertical-align:middle;font-size:18px;font-weight:700;"
                + "letter-spacing:-0.01em;color:" + COLOR_HEADER_TEXT + ";\">"
                + escape(brandLead)
                + (brandTail != null
                    ? " <span style=\"background:linear-gradient(135deg," + COLOR_ACCENT_FROM + " 0%,"
                        + COLOR_ACCENT_TO + " 100%);"
                        + "-webkit-background-clip:text;background-clip:text;color:" + COLOR_ACCENT_FROM + ";"
                        + "-webkit-text-fill-color:transparent;\">" + escape(brandTail) + "</span>"
                    : "")
                + "</span>"
                + "</a>"
                + "</td></tr>"
                // Accent strip below header
                + "<tr><td style=\"background:linear-gradient(90deg," + COLOR_ACCENT_FROM + " 0%,"
                + COLOR_ACCENT_TO + " 100%);height:3px;line-height:3px;font-size:0;\">&nbsp;</td></tr>"
                // Content card
                + "<tr><td style=\"padding:28px;\">"
                + "<h1 style=\"margin:0 0 18px;font-size:20px;line-height:1.3;font-weight:700;"
                + "color:" + COLOR_TEXT_PRIMARY + ";letter-spacing:-0.01em;\">"
                + escape(heading) + "</h1>"
                + bodyHtml
                + "</td></tr>"
                // Footer disclaimer
                + "<tr><td style=\"padding:16px 28px 22px;border-top:1px solid " + COLOR_CARD_BORDER + ";"
                + "background:#fafbfc;font-size:12px;color:" + COLOR_TEXT_MUTED + ";\">"
                + "This is an automated message from " + brand + " Careers. Please don't reply directly "
                + "to this email — replies are not monitored.<br/>"
                + "<a href=\"" + escape(brandUrl) + "\" style=\"color:" + COLOR_ACCENT_TO + ";"
                + "text-decoration:none;font-weight:600;\">" + escape(brandHostLabel()) + "</a>"
                + "</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    /** Verification-code emphasis block (large, monospaced, accent border). */
    private String codeBlock(String code) {
        return "<div style=\"margin:14px 0 18px;padding:16px 18px;border:1px solid "
                + COLOR_CODE_BORDER + ";border-radius:8px;background:" + COLOR_CODE_BG + ";"
                + "font-family:'SFMono-Regular',Consolas,monospace;"
                + "font-size:28px;letter-spacing:8px;text-align:center;font-weight:700;"
                + "color:" + COLOR_TEXT_PRIMARY + ";\">"
                + escape(code)
                + "</div>";
    }

    /** Accent CTA button (used by password reset). */
    private String buttonBlock(String label, String href) {
        return "<p style=\"margin:0 0 18px;\">"
                + "<a href=\"" + escape(href) + "\" "
                + "style=\"display:inline-block;padding:12px 22px;border-radius:8px;"
                + "background:linear-gradient(135deg," + COLOR_ACCENT_FROM + " 0%,"
                + COLOR_ACCENT_TO + " 100%);"
                + "color:#ffffff;text-decoration:none;font-weight:700;font-size:14px;"
                + "letter-spacing:0.01em;\">"
                + escape(label)
                + "</a>"
                + "</p>";
    }

    /**
     * Footer link label — the visible host stripped from {@link #brandUrl}
     * (e.g. {@code "https://www.anvicorp.com"} → {@code "anvicorp.com"}).
     * Falls back to {@link #brand} when brandUrl is missing/malformed so the
     * footer never renders empty.
     */
    private String brandHostLabel() {
        if (brandUrl == null || brandUrl.isBlank()) return brand;
        String host = brandUrl.trim();
        int scheme = host.indexOf("://");
        if (scheme >= 0) host = host.substring(scheme + 3);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        if (host.startsWith("www.")) host = host.substring(4);
        return host.isEmpty() ? brand : host;
    }

    /** "Hi Jane," / "Hi there," when name is unknown. */
    private static String greeting(String name) {
        if (name == null || name.isBlank()) return "Hi there,";
        // Just the first word so "Jane Q. Doe" doesn't read awkwardly.
        String first = name.trim().split("\\s+", 2)[0];
        return "Hi " + first + ",";
    }

    /** Two-column label/value row used inside cards. */
    private String miniRow(String label, String value) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"margin:6px 0;font-size:14px;\">"
                + "<tr><td style=\"padding:2px 12px 2px 0;color:" + COLOR_TEXT_MUTED + ";"
                + "font-size:12px;text-transform:uppercase;letter-spacing:0.04em;"
                + "font-weight:600;\">" + escape(label) + "</td>"
                + "<td style=\"padding:2px 0;color:" + COLOR_TEXT_BODY + ";\">"
                + value + "</td></tr></table>";
    }

    /** Boxed interview-details panel used by scheduled + reminder emails. */
    private String interviewDetailsBlock(String when, String duration, String mode,
                                         String interviewerName, String meetingUrl) {
        StringBuilder rows = new StringBuilder();
        rows.append(miniRow("When", escape(when)));
        rows.append(miniRow("Duration", escape(duration)));
        rows.append(miniRow("Type", escape(mode)));
        if (interviewerName != null) {
            rows.append(miniRow("Interviewer", escape(interviewerName)));
        }
        if (meetingUrl != null) {
            rows.append(miniRow("Link",
                    "<a href=\"" + escape(meetingUrl) + "\" "
                            + "style=\"color:" + COLOR_ACCENT_TO + ";text-decoration:none;"
                            + "word-break:break-all;\">" + escape(meetingUrl) + "</a>"));
        }
        return "<div style=\"margin:14px 0 18px;padding:14px 18px;border:1px solid "
                + COLOR_CARD_BORDER + ";border-radius:8px;background:#f9fafb;\">"
                + rows.toString()
                + "</div>";
    }

    /** Boxed offer-details panel used by the offer email. */
    private String offerDetailsBlock(String comp, String start, String expires) {
        return "<div style=\"margin:14px 0 18px;padding:14px 18px;border:1px solid "
                + COLOR_CARD_BORDER + ";border-radius:8px;background:#f9fafb;\">"
                + miniRow("Compensation", escape(comp))
                + miniRow("Start date", escape(start))
                + miniRow("Respond by", escape(expires))
                + "</div>";
    }

    /**
     * "$25.00 USD per HOUR" / "$5000.00 USD per MONTHLY" — kept human-friendly
     * but doesn't try to localize the frequency word, since the OfferFrequency
     * enum names already read well.
     */
    private static String formatComp(BigDecimal amount, String currency, String frequency) {
        if (amount == null) return "—";
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        StringBuilder sb = new StringBuilder();
        sb.append(rounded.toPlainString());
        if (currency != null && !currency.isBlank()) sb.append(" ").append(currency);
        if (frequency != null && !frequency.isBlank()) {
            sb.append(" per ").append(frequency.toLowerCase().replace('_', ' '));
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
