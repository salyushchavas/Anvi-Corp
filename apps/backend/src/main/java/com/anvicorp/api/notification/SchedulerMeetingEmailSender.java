package com.anvicorp.api.notification;

import com.anvicorp.api.erm.CommunicationTemplateService;
import com.anvicorp.api.integration.meeting.MeetingProvider;
import com.anvicorp.api.integration.meeting.MeetingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends the scheduler-side "you scheduled this meeting" email. Unlike the
 * participant emails (which the {@code *NotificationDispatcher} classes
 * render via the templated communication catalog), this email is inlined:
 * kept short, always the same shape, and carries the meeting's Zoom
 * {@code start_url} — the one-click host link that opens Zoom and joins
 * the user AS the host with no Zoom sign-in required.
 *
 * <p>The start URL is fetched fresh on-send (not cached) because Zoom's
 * {@code start_url} is short-lived (~2h after meeting create). If the
 * fresh fetch fails (provider misconfig, transient error) we fall back to
 * the stored copy passed in by the caller — the email is still useful
 * even when the on-the-wire link has expired, because the user can still
 * use the Refresh button in the in-app modal to get a current one.</p>
 *
 * <p>Participants never receive this body — only the scheduler does. The
 * existing intern/applicant emails stay unchanged (join button only, no
 * host link). See {@link MeetingEmailHtmlBuilder#buildWithHostStart} for
 * the HTML shape.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchedulerMeetingEmailSender {

    private static final DateTimeFormatter LOCAL_FMT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a");

    private final MeetingProvider meetingProvider;
    private final EmailProvider emailProvider;
    private final com.anvicorp.api.config.BrandConfig brand;
    // Email-slice-2 — the plain body used to be built inline via
    // StringBuilder; now it renders from the MEETING_INVITE_HOST template
    // seeded at boot (CommunicationTemplateSeeder), keeping brand/signoff
    // strings out of code and letting ERM edit the copy without a deploy.
    private final CommunicationTemplateService templateService;

    /**
     * Send the scheduler email. Safe to call even when fields are null/blank
     * — short-circuits when essentials (recipient email, join url) are
     * missing and logs at debug.
     *
     * @param recipientEmail scheduler's email address
     * @param recipientName  display name for the greeting (firstName or full)
     * @param subjectPrefix  e.g. "Weekly meeting scheduled" / "Interview
     *                       scheduled" — appended with the meeting title
     * @param meetingTitle   the meeting topic/title shown on the calendar
     * @param participantLabel the counterparty's name + label (e.g.
     *                       "with John Doe (intern)") shown in the body
     * @param scheduledFor   meeting start time
     * @param timezone       IANA zone (falls back to UTC)
     * @param joinUrl        Zoom join link (same one the participant uses)
     * @param storedStartUrl previously-stored Zoom start URL — used as the
     *                       fallback link when the fresh fetch fails
     * @param providerMeetingId Zoom meeting id used to fetch a fresh
     *                       {@code start_url} on-send (it expires ~2h
     *                       after meeting create)
     */
    public void send(String recipientEmail, String recipientName,
                     String subjectPrefix, String meetingTitle,
                     String participantLabel, Instant scheduledFor,
                     String timezone, String joinUrl,
                     String storedStartUrl, String providerMeetingId) {
        // joinUrl is used here only as a "meeting was actually created"
        // signal (Zoom returns both URLs on a successful create). When
        // joinUrl is null, the create probably failed — no point spamming
        // the scheduler with a start link they can't share. The join URL
        // itself is NEVER rendered into this email body — schedulers see
        // host-only.
        if (recipientEmail == null || recipientEmail.isBlank()
                || joinUrl == null || joinUrl.isBlank()) {
            log.debug("[SchedulerMeetingEmail] skipping send — missing recipient/join URL");
            return;
        }
        String startUrl = freshStartUrl(providerMeetingId, storedStartUrl);
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
        } catch (Exception e) {
            zone = ZoneId.of("UTC");
        }
        String when = scheduledFor != null
                ? LOCAL_FMT.format(scheduledFor.atZone(zone))
                : "TBD";
        String name = recipientName == null || recipientName.isBlank()
                ? "there" : recipientName;
        String title = meetingTitle == null || meetingTitle.isBlank()
                ? "Meeting" : meetingTitle;

        // Email-slice-2 — render the MEETING_INVITE_HOST template. The
        // per-recipient text that used to live in a StringBuilder now
        // resolves via {{placeholders}}; the dynamic "start URL vs
        // dashboard-fallback" block is composed in Java (its shape
        // depends on runtime state, not on ERM copy) and passed in as
        // {{hostAccessBlock}} — keeping the template author out of the
        // start-URL-expiry-caveat wording while still routing the whole
        // body through the templated pipeline. brandName + signoffBlock
        // are auto-injected by CommunicationTemplateService.render so
        // the seeded copy stays rebrand-safe.
        String participantLine = participantLabel != null && !participantLabel.isBlank()
                ? " " + participantLabel : "";
        String hostAccessBlock = startUrl != null && !startUrl.isBlank()
                ? "Start as host (one click, no Zoom sign-in needed): " + startUrl + "\n"
                        + "Note: this start link expires roughly 2 hours after the "
                        + "meeting was created. If it doesn't work, open the meeting "
                        + "in the {{brandName}} dashboard for a fresh link."
                : "Open the meeting in the {{brandName}} dashboard for your host start link.";

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("subjectPrefix", subjectPrefix == null ? "Meeting scheduled" : subjectPrefix);
        vars.put("meetingTitle", title);
        vars.put("recipientName", name);
        vars.put("participantLine", participantLine);
        vars.put("scheduledForLocal", when);
        vars.put("timezone", zone.getId());
        vars.put("hostAccessBlock", hostAccessBlock);

        String subject;
        String plain;
        try {
            var rendered = templateService.render("MEETING_INVITE_HOST", "EMAIL", vars)
                    .orElse(null);
            if (rendered == null) {
                // Template absent at boot (seeder disabled / DB pristine).
                // Skip send rather than fall back to inline copy so the
                // rebrand-safe contract holds — a MEETING_INVITE_HOST
                // rebrand deploy without the seeded row would otherwise
                // silently leak the old wording. In-app fanout (elsewhere)
                // keeps the participant informed.
                log.warn("[SchedulerMeetingEmail] MEETING_INVITE_HOST template missing — "
                        + "skipping host email to {}", recipientEmail);
                return;
            }
            subject = rendered.subject() != null && !rendered.subject().isBlank()
                    ? rendered.subject() : (subjectPrefix + " — " + title);
            plain = rendered.body();
            // Second-pass substitution for {{brandName}} tokens that were
            // baked into the hostAccessBlock value (the template renderer
            // resolves placeholders inside the TEMPLATE body, not inside
            // caller-supplied variable values — one-pass by design).
            plain = plain.replace("{{brandName}}", brand.getName());
        } catch (Exception e) {
            log.warn("[SchedulerMeetingEmail] template render failed for {} (non-fatal): {}",
                    recipientEmail, e.getMessage());
            return;
        }
        // Scheduler email is host-only — the attendee join link is sent
        // separately to the participant via their own notification flow,
        // and is NEVER included here. The HTML builder receives a null
        // joinUrl so the "Join Meeting" button is skipped.
        String html = MeetingEmailHtmlBuilder.buildWithHostStart(plain, null, startUrl);
        try {
            emailProvider.sendBrandedHtml(recipientEmail, subject, plain, html);
        } catch (Exception e) {
            log.warn("[SchedulerMeetingEmail] send to {} failed (non-fatal): {}",
                    recipientEmail, e.getMessage());
        }
    }

    private String freshStartUrl(String providerMeetingId, String storedStartUrl) {
        if (providerMeetingId == null || providerMeetingId.isBlank()) {
            return storedStartUrl;
        }
        try {
            MeetingResponse fresh = meetingProvider.getMeeting(providerMeetingId);
            if (fresh != null && fresh.startUrl() != null && !fresh.startUrl().isBlank()) {
                return fresh.startUrl();
            }
        } catch (Exception e) {
            log.warn("[SchedulerMeetingEmail] fresh start_url fetch for {} failed; "
                    + "falling back to stored value: {}", providerMeetingId, e.getMessage());
        }
        return storedStartUrl;
    }
}
