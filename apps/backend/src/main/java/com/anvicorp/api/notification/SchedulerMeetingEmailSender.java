package com.anvicorp.api.notification;

import com.anvicorp.api.integration.meeting.MeetingProvider;
import com.anvicorp.api.integration.meeting.MeetingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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
        String subject = subjectPrefix + " — " + title;
        StringBuilder plain = new StringBuilder();
        plain.append("Hi ").append(name).append(",\n\n")
                .append("You scheduled \"").append(title).append("\"");
        if (participantLabel != null && !participantLabel.isBlank()) {
            plain.append(" ").append(participantLabel);
        }
        plain.append(" for ").append(when).append(" (").append(zone.getId()).append(").\n\n");
        if (startUrl != null && !startUrl.isBlank()) {
            plain.append("Start as host (one click, no Zoom sign-in needed): ")
                    .append(startUrl).append("\n")
                    .append("Note: this start link expires roughly 2 hours after the meeting was created. ")
                    .append("If it doesn't work, open the meeting in the ").append(brand.getName()).append(" dashboard for a fresh link.\n\n");
        } else {
            plain.append("Open the meeting in the ").append(brand.getName()).append(" dashboard for your host start link.\n\n");
        }
        plain.append(brand.signoff());
        // Scheduler email is host-only — the attendee join link is sent
        // separately to the participant via their own notification flow,
        // and is NEVER included here. The HTML builder receives a null
        // joinUrl so the "Join Meeting" button is skipped.
        String html = MeetingEmailHtmlBuilder.buildWithHostStart(
                plain.toString(), null, startUrl);
        try {
            emailProvider.sendBrandedHtml(recipientEmail, subject, plain.toString(), html);
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
