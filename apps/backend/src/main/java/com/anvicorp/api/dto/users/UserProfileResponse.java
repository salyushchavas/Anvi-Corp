package com.anvicorp.api.dto.users;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.anvicorp.api.enums.DegreeLevel;
import com.anvicorp.api.enums.WorkAuthTrack;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Current-user profile DTO. Never includes the password hash. Roles is the
 * full set the user holds (typical accounts have exactly one).
 *
 * Phase 1.4 — adds the intake profile + neutral work-authorization
 * self-attestation. NO document fields here; documents are post-offer only.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserProfileResponse {
    private UUID id;
    private String fullName;
    private String email;
    private String phone;
    /** Only populated when the user has a Candidate row; otherwise null. */
    private LocalDate dateOfBirth;
    private Set<String> roles;

    // Phase 1.4 intake — all candidate-row fields, null for non-candidates.
    private String legalName;
    private String preferredName;
    private String education;        // legacy free-text
    private String school;
    private String degree;           // legacy free-text

    // Phase 1.5 — structured education. New surface; null on legacy rows.
    private DegreeLevel degreeLevel;
    private String specialization;
    private Short graduationYear;

    private String skillset;

    // Phase 1.4 neutral work-authorization self-attestation.
    private Boolean authorizedToWork;
    private Boolean sponsorshipNeeded;
    private WorkAuthTrack expectedTrack;
    private LocalDate validityDate;
    private LocalDate validityStartDate;

    // ── B2 profile expansion ────────────────────────────────────────────
    // US address (candidate-row columns, all nullable).
    private String addressStreet;
    private String addressApt;
    private String addressCity;
    private String addressState;
    private String addressZip;
    private String addressCountry;

    // WorkAuthorizationRecord dates surfaced through the profile work step.
    private LocalDate authorizedFrom;
    private LocalDate authorizedUntil;

    /**
     * One-shot stamp of the first time this candidate's profile met the
     * stricter submission bar. Non-null means the ERM submission-ack
     * notification has already fired for this candidate; further edits
     * fire the (throttled) edit-notify path instead.
     */
    private Instant profileSubmittedAt;

    /**
     * Zoom host email — populated for any staff user (TRAINER, ERM,
     * MANAGER, REPORTING_MANAGER, SUPER_ADMIN) so the per-host scheduler
     * can render its own value. Null when the user hasn't set it.
     */
    private String zoomEmail;
}
