package com.anvicorp.api.intern;

import com.anvicorp.api.enums.InternLifecycleStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Single payload that drives the entire intern surface — mode, stepper,
 * module visibility, the Home page's next-action card, the right-side
 * contact panel, and (Phase 8) the exit summary block when the intern is
 * INACTIVE. Shape mirrors the Phase 1 doc (§3 + §4) plus the Phase 8
 * exit extensions.
 */
public record InternDashboardResponse(
        UserSummary user,
        InternLifecycleStatus lifecycleStatus,
        String mode,
        boolean emailVerified,
        List<StepperStep> stepper,
        Modules modules,
        NextAction nextAction,
        Contacts contacts,
        ExitSummary exitSummary,
        /** Present when the intern has been SELECTED post-interview but
         *  has not yet clicked "Receive my offer letter". Null otherwise.
         *  Drives the dedicated selection-ack card on the intern home. */
        SelectionAck selectionAck,
        /** Approach 1 — derived view of "can this intern apply?". Drives the
         *  dashboard completion card AND the client-side "Apply locked"
         *  hint; the server stays authoritative via the apply endpoint
         *  guard. Always non-null. */
        ApplyReadiness applyReadiness,
        Instant lastUpdatedAt
) {
    public record UserSummary(
            String firstName,
            String lastName,
            String email,
            String applicantId,
            String employeeId
    ) {}

    public record StepperStep(
            String key,
            String label,
            String status, // "DONE" | "ACTIVE" | "UPCOMING"
            /** Optional secondary line rendered beneath the label. Used today
             *  only for the {@code active_intern} step at ONBOARDING_ACCEPTED
             *  to explain why the intern is parked ("Activates on YYYY-MM-DD",
             *  "Pending start date", "Activating shortly"). Null otherwise. */
            String reason
    ) {}

    public record Modules(
            ModuleState home,
            ModuleState jobPostings,
            ModuleState myApplications,
            ModuleState interviewCenter,
            ModuleState offerLetter,
            ModuleState onboarding,
            ModuleState myProjects,
            ModuleState timesheets,
            ModuleState evaluations,
            ModuleState documents,
            ModuleState messages,
            ModuleState doubts,
            ModuleState help
    ) {}

    public record ModuleState(
            boolean visible,
            boolean locked,
            boolean readOnly
    ) {}

    public record NextAction(
            String title,
            String description,
            String ctaLabel,
            String ctaHref,
            boolean waiting,
            String waitingFor
    ) {}

    public record Contacts(
            Contact erm,
            Contact trainer,
            Contact evaluator,
            Contact manager
    ) {}

    public record Contact(
            String name,
            String email
    ) {}

    /**
     * Surfaced only when the latest interview decision is SELECTED and the
     * applicant hasn't acknowledged yet. Frontend renders a dedicated
     * card with a "Receive my offer letter" button that POSTs to
     * {@code /api/v1/applications/{applicationId}/acknowledge-selection}.
     */
    public record SelectionAck(
            UUID applicationId,
            String jobTitle,
            /** Optional applicant-visible feedback ERM wrote at decision time. */
            String applicantVisibleNotes
    ) {}

    /**
     * Phase 8 — present only when {@code mode == "INACTIVE"}. Powers the
     * Home exit-summary card and the /careers/intern/exit/summary page.
     */
    public record ExitSummary(
            String exitType,
            LocalDate exitDate,
            long durationDays,
            long projectsCompleted,
            long evaluationsCount,
            Double averageScore,
            long timesheetsApproved,
            BigDecimal totalApprovedHours,
            boolean feedbackSubmitted,
            String internVisibleSummary,
            UUID finalEvaluationId
    ) {}
}
