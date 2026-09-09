package com.anvicorp.api.manager.portfolio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for the Manager Intern Portfolio surface — ONE place to see
 * every intern across every state (account created, onboarding, active,
 * exited) with a full read-only profile drill-down. Month-agnostic —
 * a portfolio is a "who's on the roster" view, not a monthly slice.
 *
 * <p>Exclusion rules enforced server-side (never in UI): rows where
 * {@code intern_lifecycles.deleted_at IS NOT NULL} (soft-deleted /
 * purged) OR {@code users.email_verified = false} (unverified accounts)
 * never appear.</p>
 */
public final class ManagerInternPortfolioDtos {

    private ManagerInternPortfolioDtos() {}

    // ── List ─────────────────────────────────────────────────────────────

    /** One list row. Trainer / evaluator / erm names hydrated via JOIN
     *  so the list renders team info without an N+1. */
    public record PortfolioRow(
            UUID userId,
            UUID lifecycleId,
            String fullName,
            String email,
            String employeeId,       // e.g. ANVI-INT-2026-000123
            String applicantId,      // ANVI-APP-… — when the account came from the pipeline
            String stage,            // ACCOUNT_CREATED | ONBOARDING | ACTIVE | EXITED
            String stageLabel,       // Friendly label — "Account created" / "Active" / …
            String trainerName,
            String evaluatorName,
            String ermName,
            Instant joinedAt,        // account creation
            Instant hiredAt,         // when the hire decision landed
            Instant startedAt,       // when active work began
            Instant endedAt          // when the engagement ended (COMPLETED / RESIGNED / TERMINATED)
    ) {}

    public record PortfolioListPage(
            List<PortfolioRow> items,
            int page,
            int pageSize,
            long totalElements,
            int totalPages,
            /** Live counts per stage across the WHOLE filtered universe
             *  (respects search + exclusion rules, ignores stage filter)
             *  so the stage tabs can render counts alongside their labels. */
            StageCounts stageCounts
    ) {}

    public record StageCounts(
            long accountCreated,
            long onboarding,
            long active,
            long exited,
            long total
    ) {}

    // ── Detail (read-only) ───────────────────────────────────────────────

    /**
     * Full read-only detail — every section the manager might inspect
     * on one page. Nulls indicate "not applicable / not captured yet"
     * (e.g. no application when the intern was direct-onboarded);
     * frontend renders each section with a neutral empty state rather
     * than hiding it entirely.
     */
    public record PortfolioDetail(
            ProfileSection profile,
            ContactSection contact,
            AddressSection address,
            EducationSection education,
            WorkAuthSection workAuth,
            ApplicationSummary application,   // null when direct-onboarded
            DocumentsSection documents,
            OffersSection offers,
            ProjectsAndEvalsSection projectsAndEvals,
            TeamSection team,
            ExitSection exit                  // null when engagement not ended
    ) {}

    public record ProfileSection(
            UUID userId,
            UUID lifecycleId,
            String fullName,
            String employeeId,
            String applicantId,
            String stage,
            String stageLabel,
            Instant joinedAt,
            Instant hiredAt,
            Instant startedAt,
            Instant endedAt,
            /** Whole-months since started_at (or hired_at fallback), 0 when neither set. */
            int monthsInProgram
    ) {}

    public record ContactSection(
            String primaryEmail,
            String phoneNumber,
            String companyEmail        // Anvi-issued mailbox, when provisioned
    ) {}

    public record AddressSection(
            String line1,
            String line2,
            String city,
            String state,
            String postalCode,
            String country
    ) {}

    public record EducationSection(
            String highestDegree,
            String fieldOfStudy,
            String university,
            String graduationYear
    ) {}

    public record WorkAuthSection(
            String workAuthType,       // e.g. F1_STEM_OPT, H1B, US_CITIZEN, …
            /** Start of the authorization window (H-1B: I-797 receipt start). */
            LocalDate authorizedFrom,
            /** End of the authorization window — single source of truth for
             *  expiration across every visa type. Displayed under a per-type
             *  label ("CPT expiration" / "EAD expiration" / "H-1B end date"). */
            LocalDate authExpiresOn,
            /** I-20 program end date (F-1 visa types). */
            LocalDate i20Expiration,
            /** True ONLY when workAuthType is F1_OPT or F1_STEM_OPT — the
             *  DirectOnboardingService server forces false elsewhere. */
            Boolean i983Required,
            /** DSO contact — Designated School Official at the intern's
             *  university (F-1 visa compliance). All three may be null
             *  for non-F-1 hires. */
            String dsoName,
            String dsoEmail,
            String dsoPhone,
            /** Legacy per-type end dates — mirrored from authorizedUntil at
             *  upsert time so downstream readers (compliance alerts) still
             *  see them. Displayed here so Manager sees the exact per-type
             *  expiration in addition to the unified authExpiresOn. */
            LocalDate cptExpiration,
            LocalDate h1ReceiptStart,
            LocalDate h1ReceiptEnd,
            /** Free-text notes the ERM captured at onboard time
             *  (work_authorization_records.erm_notes). */
            String ermNotes,
            /** i-983 plan status when applicable (F1 STEM OPT), else null. */
            String i983PlanStatus
    ) {}

    /** Slim summary of the pipeline application that resulted in this
     *  intern's onboarding. Absent for direct-onboarded users. */
    public record ApplicationSummary(
            UUID applicationId,
            String jobTitle,
            String status,             // Latest ApplicationStatus
            Instant appliedAt,
            Instant lastDecisionAt,
            String lastDecisionBy
    ) {}

    public record DocumentsSection(
            UUID packetId,
            String packetStatus,       // NONE / ASSIGNED / IN_PROGRESS / ALL_SUBMITTED / COMPLETED
            int totalTasks,
            int submittedTasks,
            int reviewedTasks
    ) {}

    public record OffersSection(
            /** Every offer/IDMS document ever issued to this user, newest first.
             *  Read-only history — no reissue/void controls surface here. */
            List<OfferHistoryRow> items
    ) {}

    public record OfferHistoryRow(
            UUID offerId,
            String offerNumber,
            String status,             // SENT / SIGNED / EXECUTED / REPLACED / REVOKED / EXPIRED
            String statusLabel,        // Friendly label rendered as a chip
            Instant issuedAt,
            Instant executedAt,
            Instant replacedAt,
            Instant revokedAt,
            String replacedByReason
    ) {}

    public record ProjectsAndEvalsSection(
            int totalProjects,
            int completedProjects,
            int publishedEvaluations,
            /** Rolling average of overall_score across published evals; null
             *  when no scores captured yet. */
            Double averageOverallScore,
            /** Newest-first slice (cap 8) — enough to skim without paging. */
            List<ProjectSummary> recentProjects
    ) {}

    public record ProjectSummary(
            UUID projectId,
            String title,
            String status,             // Project.status
            String monthYear,          // e.g. "2026-08"
            Instant startedAt,
            Instant completedAt,
            /** Latest POST_PROJECT evaluation status, when one exists. */
            String latestEvaluationStatus,
            Integer latestOverallScore
    ) {}

    public record TeamSection(
            NamedRef trainer,
            NamedRef evaluator,
            NamedRef manager,
            NamedRef erm
    ) {}

    public record NamedRef(
            UUID userId,
            String fullName,
            String email
    ) {}

    public record ExitSection(
            String exitType,           // COMPLETED / RESIGNED / TERMINATED
            String exitTypeLabel,
            Instant exitedAt,
            String reasonSummary
    ) {}
}
