package com.anvicorp.api.erm.offer;

import com.anvicorp.api.enums.OfferStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** ERM Phase 4 — DTO surface for offer control + new-hire list. */
public final class ErmOfferDtos {

    private ErmOfferDtos() {}

    public record OfferRow(
            UUID offerId,
            UUID applicationId,
            String applicantName,
            String applicantId,
            String applicantEmail,
            String jobTitle,
            String jobType,
            OfferStatus status,
            String roleTitle,
            String compensationSummary,
            LocalDate tentativeStartDate,
            Instant sentAt,
            Instant expiresAt,
            Instant signedAt,
            Instant voidedAt,
            String voidReasonCode,
            Integer reminderCount,
            String legacyEnvelopeId,
            Boolean archived
    ) {}

    public record OfferListPage(
            List<OfferRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    public record OfferDetail(
            UUID id,
            OfferStatus status,
            UUID applicationId,
            String applicantName,
            String applicantEmail,
            String applicantId,
            String jobTitle,
            String jobType,
            String roleTitle,
            String compensationSummary,
            String worksite,
            Integer expectedHoursPerWeek,
            LocalDate tentativeStartDate,
            Instant sentAt,
            Instant expiresAt,
            Instant signedAt,
            Instant voidedAt,
            String voidReasonCode,
            String voidReasonText,         // ERM-only
            Integer reminderCount,
            Instant lastReminderAt,
            String legacyEnvelopeId,
            UUID signedPdfDocumentId,
            String internalNotes,          // ERM-only
            Instant archivedAt,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            List<EventLogEntry> history
    ) {}

    public record EventLogEntry(
            UUID id,
            String eventType,
            String reasonCode,
            String reasonText,             // ERM-only
            String payloadJson,
            UUID actorUserId,
            String actorName,
            Instant createdAt
    ) {}

    public record CreateOfferRequest(
            UUID applicationId,
            String roleTitle,
            String technology,
            LocalDate tentativeStartDate,
            String compensationSummary,
            String worksite,
            Integer expectedHoursPerWeek,
            Integer expiryDays,
            String contingencies,
            String templateOverrideKey
    ) {}

    public record ResendRequest(
            String reasonCode,
            String reasonText,
            Integer newExpiryDays
    ) {}

    public record VoidRequest(
            String reasonCode,
            String reasonText,
            Boolean notifyApplicant
    ) {}

    public record InternalNoteRequest(String note) {}

    public record UpdateStartDateRequest(LocalDate newDate) {}

    public record PreviewResponse(String subject, String body) {}

    public record ReasonCodeGroup(String category, List<ReasonCodeOption> options) {}
    public record ReasonCodeOption(String code, String label, boolean requiresFreeText) {}

    // ── New Hire List ────────────────────────────────────────────────────

    public record NewHireRow(
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String internEmail,
            String employeeId,
            Instant signedAt,
            LocalDate tentativeStartDate,
            String trainerName,
            String evaluatorName,
            String managerName,
            boolean reportingStructureComplete,
            boolean onboardingAssigned,
            /** ERM onboarding-tracker progress — populated by {@link
             *  com.anvicorp.api.erm.newhire.ErmNewHireService#list}. Null
             *  for older clients reading historical responses. */
            Integer stepsCompleted,
            Integer stepsTotal,
            String nextStepLabel,
            Boolean canActivate,
            /** Name of the current {@link com.anvicorp.api.erm.newhire
             *  .OnboardingTrackerDtos.StepId}, or null when all six are
             *  done. Drives the unified status-table's Stage badge +
             *  Next-action deep-link without bouncing the frontend off
             *  the tracker endpoint per row. */
            String currentStepId
    ) {}

    public record NewHireListPage(
            List<NewHireRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}

    public record NewHireDetail(
            UUID internLifecycleId,
            UUID internUserId,
            String internName,
            String internEmail,
            String employeeId,
            String activeStatus,
            Instant hiredAt,
            Instant startedAt,
            LocalDate tentativeStartDate,
            boolean reportingStructureComplete,
            Instant reportingStructureCompletedAt,
            UUID reportingStructureCompletedById,
            UserStub trainer,
            UserStub evaluator,
            UserStub manager,
            UserStub erm,
            OfferSummaryStub signedOffer,
            boolean onboardingAssigned,
            /** Phase 8.9 — server-authoritative flag the ERM UI uses to
             *  show/hide the "Activate now" override. True iff the intern's
             *  User.lifecycleStatus is ONBOARDING_ACCEPTED AND a SIGNED offer
             *  exists. Never true before document verification completes, so
             *  the override can never skip onboarding. */
            boolean canActivateNow,
            /** ERM Pass 2 — ERM-set committed activation switch (distinct
             *  from the offer's {@code tentativeStartDate}). Null when ERM
             *  hasn't committed yet. Once set + {@code <= today}, the
             *  next scheduled scan (or a synchronous activation hook)
             *  flips the intern to ACTIVE_INTERN. */
            LocalDate joiningDate,
            /** True when the lifecycle is at ONBOARDING_ACCEPTED (docs
             *  accepted). The UI uses this to enable the joining-date
             *  control — ERM commits the date only after docs pass. */
            boolean docsAccepted,
            /** Mail bridge Phase 5 — enum name (PERSONAL | PENDING_ACTIVATION |
             *  ACTIVATED) so the ERM intern-detail page can render the
             *  handover section without an extra round-trip. Mirrors
             *  {@code com.anvicorp.api.enums.MailHandoverState}. */
            String mailHandoverState,
            /** Mail bridge Phase 5 — the user's original personal Gmail,
             *  archived during the email swap. Null while still PERSONAL
             *  (no swap has happened yet). The ERM page uses this to
             *  confirm "credentials emailed to <personal email>" without
             *  exposing it from any other endpoint. */
            String personalEmail
    ) {}

    public record UserStub(
            UUID userId,
            String fullName,
            String email,
            String role,
            int currentInternCount
    ) {}

    public record OfferSummaryStub(
            UUID offerId,
            String roleTitle,
            String compensationSummary,
            String worksite,
            Integer expectedHoursPerWeek,
            LocalDate tentativeStartDate,
            Instant signedAt,
            UUID signedPdfDocumentId
    ) {}

    public record AssignReportingRequest(
            UUID trainerUserId,
            UUID evaluatorUserId,
            UUID managerUserId
    ) {}

    /** Phase 8.6.4 — inline Manager assignment from the New Hire detail page.
     *  {@code managerUserId == null} clears the assignment. */
    public record AssignManagerRequest(UUID managerUserId) {}

    // ── Phase 8.6 — Awaiting Offer queue ─────────────────────────────────
    // Applications in INTERVIEWED state with a COMPLETED interview where
    // decision=SELECTED, and no active offer (status NOT IN SENT, SIGNED)
    // for that application yet.

    public record AwaitingOfferRow(
            UUID applicationId,
            UUID interviewId,
            String applicantName,
            String applicantId,
            String applicantEmail,
            String jobTitle,
            String jobType,
            String technologyArea,
            Instant interviewCompletedAt,
            String overallRecommendation,
            Integer technicalScore,
            Integer communicationScore,
            String applicantVisibleNotes
    ) {}

    public record AwaitingOfferListPage(
            List<AwaitingOfferRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages
    ) {}
}
