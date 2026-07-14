package com.anvicorp.api.dto.users;

import com.anvicorp.api.enums.DegreeLevel;
import com.anvicorp.api.enums.WorkAuthTrack;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Editable profile fields. Email is intentionally NOT here — it's tied to the
 * login identity and must go through a separate change-email flow when we add one.
 *
 * Phase 1.4 — adds intake profile + neutral work-auth self-attestation. All
 * new fields are optional; sending {@code null} blanks the value on the row
 * (the partial-update granularity of this endpoint is "the whole profile",
 * not field-level patch).
 */
@Getter
@Setter
public class UpdateProfileRequest {

    @NotBlank(message = "fullName is required")
    @Size(max = 200, message = "fullName must be 200 characters or fewer")
    private String fullName;

    @Size(max = 40, message = "phone must be 40 characters or fewer")
    private String phone;

    private LocalDate dateOfBirth;

    // Phase 1.4 intake profile
    @Size(max = 200) private String legalName;
    @Size(max = 200) private String preferredName;
    @Size(max = 500) private String education;          // legacy free-text
    @Size(max = 200) private String school;
    @Size(max = 200) private String degree;             // legacy free-text
    @Size(max = 4000) private String skillset;

    // Phase 1.5 structured education
    private DegreeLevel degreeLevel;
    @Size(max = 200) private String specialization;
    private Short graduationYear;

    // Phase 1.4 neutral work-authorization self-attestation
    private Boolean authorizedToWork;
    private Boolean sponsorshipNeeded;
    private WorkAuthTrack expectedTrack;
    private LocalDate validityDate;
    private LocalDate validityStartDate;
}
