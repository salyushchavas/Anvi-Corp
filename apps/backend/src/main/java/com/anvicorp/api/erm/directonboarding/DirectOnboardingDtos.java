package com.anvicorp.api.erm.directonboarding;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String fullName,
            @Size(max = 200) String legalName,
            @Size(max = 40) String phoneNumber,
            /** True historical joining date (display-only fact). {@code startedAt}
             *  is stamped {@code now()} inside the service so month-wise tracking
             *  begins with the registration month, per the ERM ruleset. */
            LocalDate joiningDate,
            /** Optional entity override — falls back to the platform-wide
             *  default when null (the only StaffingEntity row if there is one). */
            UUID entityId,

            // Step 2 — Work authorization --------------------------------------
            /** {@code US_CITIZEN | PERMANENT_RESIDENT | F1_CPT | F1_OPT |
             *  F1_STEM_OPT | H1B | H4 | OTHER}. Snapshotted onto
             *  Engagement.track; drives WorkAuthorizationRecord.workAuthType
             *  via the shared upsert. Also drives which of the per-type
             *  fields below are required (see DirectOnboardingService). */
            @NotBlank
            @Pattern(regexp = "^(US_CITIZEN|PERMANENT_RESIDENT|F1_CPT|F1_OPT|F1_STEM_OPT|H1B|H4|OTHER)$",
                    message = "workAuthType must be one of the fixed 8-value enum")
            String workAuthType,
            LocalDate authorizedFrom,
            LocalDate authorizedUntil,
            /** EAD card number (F-1 OPT, F-1 STEM OPT, H-4, OTHER). AES-GCM
             *  encrypted server-side. */
            @Size(max = 40) String eadCardNumber,
            /** EAD expiration (F-1 OPT, F-1 STEM OPT, H-4, OTHER). */
            LocalDate eadExpiration,
            LocalDate i20Expiration,
            /** True ONLY when workAuthType is F1_OPT or F1_STEM_OPT. The
             *  service forces false for every other type to keep the
             *  compliance card honest. */
            Boolean i983Required,
            @Size(max = 200) String dsoName,
            @Email @Size(max = 254) String dsoEmail,
            @Size(max = 40) String dsoPhone,
            @Size(max = 2000) String workAuthNotes,
            // Per-type extensions -----------------------------------------------
            /** SEVIS ID (F-1 CPT). AES-GCM encrypted server-side. */
            @Size(max = 40) String sevisNumber,
            /** CPT authorization end date (F-1 CPT). */
            LocalDate cptExpiration,
            /** USCIS receipt number for H-1B (I-797). AES-GCM encrypted
             *  server-side. */
            @Size(max = 40) String h1ReceiptNumber,
            LocalDate h1ReceiptStart,
            LocalDate h1ReceiptEnd,

            // Step 3 — Reporting structure (all optional; null = auto-link
            // from DEFAULT_TRAINER_EMAIL / DEFAULT_EVALUATOR_EMAIL) --------------
            UUID trainerUserId,
            UUID evaluatorUserId,
            UUID managerUserId,

            // Step 4 — Documents -----------------------------------------------
            /** One entry per uploaded onboarding document. The document key
             *  MUST resolve to an active row in {@code onboarding_document_templates};
             *  the file bytes are attached as a multipart part whose name
             *  matches {@link DocumentAssignment#formPartName()}. Capped at
             *  50 entries — no realistic onboarding packet legitimately
             *  requires more. */
            @Size(max = 50) List<DocumentAssignment> documents,

            // Step 5 — Mailbox -------------------------------------------------
            /** When {@code true} the service defers to
             *  {@code CareersMailProvisioningService.provisionForIntern}
             *  AFTER the create-transaction commits, using the localPart +
             *  starting password below. When {@code false} the intern is
             *  created with a null passwordHash and the ERM assigns a
             *  mailbox later via the existing AssignCompanyEmailDialog. */
            Boolean assignMailboxNow,
            @Pattern(regexp = "^$|^[a-z0-9._-]{1,64}$",
                    message = "mailboxLocalPart must be lowercase kebab/dotted shape")
            @Size(max = 64) String mailboxLocalPart,
            @Size(max = 128) String mailboxStartingPassword
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
            @NotBlank @Size(max = 120) String documentKey,
            /** Name of the multipart file part carrying the bytes for this
             *  document. Convention: {@code doc_<documentKey>}. */
            @NotBlank @Size(max = 150) String formPartName,
            /** Only used for custom documents (documentKey starting with
             *  {@code CUSTOM_}). The ERM-typed display name lands on a
             *  freshly-seeded (inactive) onboarding_document_template row
             *  so the downstream display path — which resolves task labels
             *  through {@code onboarding_document_templates} — shows the
             *  ERM's chosen title. Null / ignored for enum-backed keys. */
            @Size(max = 200) String titleOverride
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
