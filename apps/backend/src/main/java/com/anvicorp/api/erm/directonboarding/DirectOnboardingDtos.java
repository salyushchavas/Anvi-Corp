package com.anvicorp.api.erm.directonboarding;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for the ERM "Direct Onboarding" flow — the one-shot wizard that
 * registers a pre-platform employee directly into the system, bypassing
 * application / screening / interview / offer entirely.
 *
 * <p>The request is delivered as {@code multipart/form-data}: a single
 * JSON metadata part carries all typed fields, and file parts carry
 * the resume + one document per selected onboarding-document key. The
 * shape mirrors the fields the ERM wizard collects in its five steps
 * (Personal, Work Authorization, Reporting Structure, Documents,
 * Mailbox / Review).</p>
 */
public final class DirectOnboardingDtos {

    private DirectOnboardingDtos() {}

    /**
     * Metadata part of the multipart POST — everything the wizard collects
     * except the file bytes (those come as separate multipart parts,
     * keyed by {@link DocumentAssignment#formPartName()} + a fixed
     * {@code resume} part).
     */
    public record DirectOnboardingRequest(
            // Step 1 — Personal ------------------------------------------------
            String email,
            String fullName,
            String legalName,
            String phoneNumber,
            /** True historical joining date (display-only fact). {@code startedAt}
             *  is stamped {@code now()} inside the service so month-wise tracking
             *  begins with the registration month, per the ERM ruleset. */
            LocalDate joiningDate,
            /** Optional entity override — falls back to the platform-wide
             *  default when null (the only StaffingEntity row if there is one). */
            UUID entityId,

            // Step 2 — Work authorization --------------------------------------
            /** {@code CITIZEN | CPT | OPT | STEM_OPT | OTHER} — snapshotted onto
             *  the Engagement.track; also drives WorkAuthorizationRecord.workAuthType
             *  via the shared upsert. */
            String workAuthType,
            LocalDate authorizedFrom,
            LocalDate authorizedUntil,
            String eadCardNumber,
            LocalDate eadExpiration,
            LocalDate i20Expiration,
            Boolean i983Required,
            String dsoName,
            String dsoEmail,
            String dsoPhone,
            String workAuthNotes,

            // Step 3 — Reporting structure (all optional; null = auto-link
            // from DEFAULT_TRAINER_EMAIL / DEFAULT_EVALUATOR_EMAIL) --------------
            UUID trainerUserId,
            UUID evaluatorUserId,
            UUID managerUserId,

            // Step 4 — Documents -----------------------------------------------
            /** One entry per uploaded onboarding document. The document key
             *  MUST resolve to an active row in {@code onboarding_document_templates};
             *  the file bytes are attached as a multipart part whose name
             *  matches {@link DocumentAssignment#formPartName()}. */
            List<DocumentAssignment> documents,

            // Step 5 — Mailbox -------------------------------------------------
            /** When {@code true} the service defers to
             *  {@code CareersMailProvisioningService.provisionForIntern}
             *  AFTER the create-transaction commits, using the localPart +
             *  starting password below. When {@code false} the intern is
             *  created with a null passwordHash and the ERM assigns a
             *  mailbox later via the existing AssignCompanyEmailDialog. */
            Boolean assignMailboxNow,
            String mailboxLocalPart,
            String mailboxStartingPassword
    ) {}

    /**
     * A single document the ERM has uploaded on the intern's behalf. The
     * {@code documentKey} matches an {@code OnboardingDocumentTemplate.key};
     * the file bytes arrive on the multipart part named
     * {@link #formPartName()}. Everything is written straight to
     * {@link com.anvicorp.api.intern.DocumentVaultService} — the SAME
     * storage pipeline the intern packet flow uses.
     */
    public record DocumentAssignment(
            String documentKey,
            /** Name of the multipart file part carrying the bytes for this
             *  document. Convention: {@code doc_<documentKey>}. */
            String formPartName
    ) {}

    /**
     * Response body on success. Provides everything the wizard's confirmation
     * screen needs to jump straight to the new employee's Active Intern
     * detail without re-fetching.
     */
    public record DirectOnboardingResponse(
            UUID userId,
            UUID internLifecycleId,
            String employeeId,
            String applicantId,
            String email,
            String companyEmail,
            Boolean mailboxProvisioned,
            Boolean credentialsEmailSent,
            Instant activatedAt,
            /** Convenience deep-link for the wizard's success screen. */
            String activeInternDetailPath
    ) {}
}
