package com.anvicorp.api.erm.settings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ERM Phase 7 — per-template sample variables for the live preview
 * pane in the settings editor. Each map mirrors the variables_csv on
 * the seeded template so the editor can preview without making the
 * ERM type values by hand.
 */
public final class SampleVarsRegistry {

    private SampleVarsRegistry() {}

    private static final Map<String, Map<String, Object>> SAMPLES = new LinkedHashMap<>();
    static {
        SAMPLES.put("APPLICATION_REJECT", Map.of(
                "firstName", "Alex", "jobTitle", "Java Intern"));
        SAMPLES.put("APPLICATION_HOLD", Map.of(
                "firstName", "Alex", "jobTitle", "Java Intern"));
        SAMPLES.put("APPLICATION_REQUEST_INFO", Map.of(
                "firstName", "Alex", "jobTitle", "Java Intern",
                "infoRequested", "Updated resume + work auth details"));
        SAMPLES.put("INTERVIEW_SELECTED", Map.of("firstName", "Alex"));
        SAMPLES.put("INTERVIEW_HOLD", Map.of("firstName", "Alex"));
        SAMPLES.put("INTERVIEW_REJECTED", Map.of(
                "firstName", "Alex", "jobTitle", "Java Intern"));
        SAMPLES.put("INTERVIEW_SCHEDULED", Map.of(
                "firstName", "Alex",
                "jobTitle", "Java Intern",
                "scheduledForLocal", "2026-06-12 10:00 AM",
                "timezone", "EST",
                "zoomJoinUrl", "https://zoom.us/j/sample",
                "interviewerName", "Priya S.",
                "prepInstructions", "Bring a laptop. 45 min coding + 15 min behavioral."));
        SAMPLES.put("INTERVIEW_RESCHEDULED", Map.of(
                "firstName", "Alex",
                "jobTitle", "Java Intern",
                "newScheduledForLocal", "2026-06-13 11:00 AM",
                "timezone", "EST",
                "rescheduleReasonHuman", "Interviewer conflict",
                "zoomJoinUrl", "https://zoom.us/j/sample"));
        SAMPLES.put("INTERVIEW_CANCELLED", Map.of(
                "firstName", "Alex",
                "jobTitle", "Java Intern",
                "cancellationMessage", "We will follow up shortly with next steps."));
        SAMPLES.put("OFFER_LETTER", Map.of(
                "firstName", "Alex",
                "roleTitle", "Software Engineer Intern",
                "tentativeStartDate", "2026-07-01",
                "compensationSummary", "$30/hr, 40 hrs/week",
                "worksite", "Remote (US)",
                "expectedHoursPerWeek", 40,
                "contingencies", "Subject to I-9 + E-Verify completion.",
                "expiryDays", 5,
                "ermName", "Priya S."));
        SAMPLES.put("OFFER_REMINDER", Map.of(
                "firstName", "Alex",
                "roleTitle", "Software Engineer Intern",
                "expiryDate", "2026-06-12",
                "ermName", "Priya S."));
        SAMPLES.put("OFFER_VOIDED", Map.of(
                "firstName", "Alex",
                "roleTitle", "Software Engineer Intern",
                "voidReasonHuman", "Terms revised — re-issuing",
                "ermName", "Priya S."));
        SAMPLES.put("OFFER_DOC_REJECTED", Map.of(
                "firstName", "Alex",
                "documentName", "W-4",
                "ermComments", "Signature missing on page 2"));
        SAMPLES.put("REPORTING_STRUCTURE_ASSIGNED", Map.of(
                "recipientFirstName", "Sam",
                "role", "Trainer",
                "internName", "Alex K.",
                "employeeId", "SKZ-2026-042",
                "tentativeStartDate", "2026-07-01"));
        SAMPLES.put("START_DATE_UPDATED", Map.of(
                "firstName", "Alex",
                "newDate", "2026-07-08",
                "ermName", "Priya S."));
        SAMPLES.put("ONBOARDING_ITEM_ACCEPTED", Map.of(
                "firstName", "Alex",
                "documentName", "W-4 tax form"));
        SAMPLES.put("ONBOARDING_ITEM_REJECTED", Map.of(
                "firstName", "Alex",
                "documentName", "I-9 Section 1",
                "ermComments", "Date of birth field doesn't match the SSN card.",
                "ermName", "Priya S."));
        SAMPLES.put("ONBOARDING_ITEM_RESEND", Map.of(
                "firstName", "Alex",
                "documentName", "ACH direct deposit form",
                "ermComments", "Please re-upload — the voided check is illegible.",
                "ermName", "Priya S."));
        SAMPLES.put("ONBOARDING_PACKET_ACCEPTED", Map.of(
                "firstName", "Alex",
                "firstDayOfEmployment", "2026-07-01"));
        SAMPLES.put("EVERIFY_CASE_OPENED", Map.of("firstName", "Alex"));
        SAMPLES.put("EVERIFY_TENTATIVE_NONCONFIRMATION", Map.of(
                "firstName", "Alex",
                "ermName", "Priya S."));
        SAMPLES.put("EVERIFY_AUTHORIZED", Map.of("firstName", "Alex"));
        SAMPLES.put("WORK_AUTH_EXPIRING", Map.of(
                "firstName", "Alex",
                "workAuthType", "F1_OPT",
                "expirationDate", "2026-07-15",
                "daysUntilExpiration", 30,
                "ermName", "Priya S."));
        SAMPLES.put("EXIT_COMPLETED", Map.of(
                "firstName", "Alex",
                "exitDate", "2026-08-01"));
        SAMPLES.put("EXIT_TERMINATED", Map.of(
                "firstName", "Alex",
                "exitDate", "2026-08-01"));
        SAMPLES.put("EXIT_RESIGNED", Map.of(
                "firstName", "Alex",
                "exitDate", "2026-08-01"));
    }

    public static Map<String, Object> samplesFor(String key) {
        return SAMPLES.getOrDefault(key, Map.of("firstName", "Alex"));
    }

    public static Map<String, Map<String, Object>> all() {
        return SAMPLES;
    }
}
