package com.anvicorp.api.erm.compliance;

import com.anvicorp.api.erm.exception.ExceptionSeverity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** ERM Phase 5 — DTO surface for the Compliance Tracker. */
public final class ErmComplianceDtos {

    private ErmComplianceDtos() {}

    public record PipelineRow(
            UUID userId,
            String fullName,
            String applicantId,
            String email,
            String workAuthType,
            LocalDate authorizedUntil,
            Integer daysUntilExpiration,
            ExceptionSeverity workAuthSeverity,
            String i9Status,
            LocalDate i9Section2DueBy,
            Integer i9DaysUntil,
            ExceptionSeverity i9Severity,
            String everifyStatus,
            LocalDate everifyDueBy,
            Integer everifyDaysUntil,
            ExceptionSeverity everifySeverity,
            Boolean i983Required
    ) {}

    public record PipelinePage(
            List<PipelineRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages,
            PipelineKpi kpi
    ) {}

    public record PipelineKpi(
            long workAuthExpiring30,
            long i9OverdueOrDueSoon,
            long everifyTncOrOverdue,
            long i983Required
    ) {}

    public record InternTimeline(
            UUID userId,
            String fullName,
            String email,
            WorkAuthCard workAuth,
            I9TimelineCard i9,
            EverifyCard everify,
            I983Card i983,
            List<TimelineEvent> upcomingEvents
    ) {}

    public record WorkAuthCard(
            UUID recordId,
            String workAuthType,
            LocalDate authorizedFrom,
            LocalDate authorizedUntil,
            String eadCardNumberMasked,     // E••••1234 — never plaintext
            LocalDate eadExpiration,
            LocalDate i20Expiration,
            Boolean i983Required,
            UUID i983Id,
            String dsoName,
            String dsoEmail,
            String dsoPhone,
            String ermNotes,                // ERM-only
            Instant lastUpdatedAt,
            UUID lastUpdatedById
    ) {}

    public record I9TimelineCard(
            UUID i9FormId,
            String status,
            LocalDate firstDayOfEmployment,
            LocalDate section1DueDate,
            LocalDate section2DueDate,
            LocalDate section2DueByCalculated,
            Integer section2DaysUntil,
            ExceptionSeverity section2Severity,
            Instant section1SignedAt,
            Instant section2SignedAt,
            String employerName,
            String employerTitle,
            // ERM Phase 8 — simplified §2 attestation surfaced from the
            // intern_lifecycles row. section2Completed mirrors a non-null
            // section2CompletedAt for the UI checkbox.
            Boolean section2Completed,
            Instant section2CompletedAt,
            UUID section2CompletedById,
            String section2DocumentsDescribed,
            String section2Notes
    ) {}

    public record EverifyCard(
            UUID caseId,
            String caseNumberMasked,        // E••••1234 — never plaintext
            String status,
            LocalDate dueBy,
            LocalDate expectedCloseBy,
            Integer daysUntilClose,
            ExceptionSeverity severity,
            Instant openedAt,
            Instant closedAt,
            String closureReason,
            Boolean photoMatchRequired,
            String photoMatchResult,
            String ermNotes,                // ERM-only
            Instant lastUpdatedAt
    ) {}

    public record I983Card(
            UUID id,
            String status,
            LocalDate periodStartDate,
            LocalDate periodEndDate,
            Instant lastEvaluationAt,
            Integer daysUntilNext,
            ExceptionSeverity severity
    ) {}

    public record TimelineEvent(
            String label,
            LocalDate eventDate,
            Integer daysUntil,
            ExceptionSeverity severity
    ) {}

    public record RevealCaseNumberResponse(
            UUID caseId,
            String caseNumberFull           // returned exactly once; audit-logged
    ) {}

    // ── Mutations ────────────────────────────────────────────────────────

    public record UpdateWorkAuthRequest(
            String workAuthType,
            LocalDate authorizedFrom,
            LocalDate authorizedUntil,
            String eadCardNumber,           // plaintext on the wire — encrypted at rest
            LocalDate eadExpiration,
            LocalDate i20Expiration,
            Boolean i983Required,
            String dsoName,
            String dsoEmail,
            String dsoPhone,
            String ermNotes
    ) {}

    /**
     * ERM Phase 8 — simplified to checkbox attestation + first-day date +
     * free-text describing the documents the agent reviewed in person.
     * The document packet workflow (Phase 8) replaces the structured
     * List A / B / C inputs; agents record the underlying paperwork in
     * their narrative description and notes.
     */
    public record RecordI9Section2Request(
            LocalDate firstDayOfEmployment,
            String documentsDescribed,
            String notes
    ) {}

    public record RecordEverifyRequest(
            UUID i9FormId,
            String caseNumber,              // plaintext on the wire — encrypted at rest
            String status,
            LocalDate dueBy,
            LocalDate expectedCloseBy,
            Boolean photoMatchRequired,
            String photoMatchResult,
            String ermNotes
    ) {}

    public record UpdateEverifyStatusRequest(
            String status,
            String closureReason,
            LocalDate expectedCloseBy,
            String photoMatchResult,
            String ermNotes
    ) {}
}
