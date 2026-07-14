package com.anvicorp.api.dto.i9;

import com.anvicorp.api.enums.CitizenshipStatus;
import jakarta.validation.constraints.Past;
import lombok.*;

import java.time.LocalDate;

/**
 * Section 1 payload. Validation is intentionally lax at the bean-validation
 * layer so drafts (isDraft=true) can be saved with partial data; the service
 * runs full required-field + cross-field checks only when isDraft=false.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Section1Request {

    private String lastName;
    private String firstName;
    private String middleInitial;
    private String otherLastNamesUsed;

    private String addressStreet;
    private String addressAptNumber;
    private String addressCity;
    private String addressState;
    private String addressZipCode;

    @Past
    private LocalDate dateOfBirth;

    /** XXX-XX-XXXX. Format check happens in service when isDraft=false. */
    private String ssn;

    private String email;
    private String phoneNumber;

    private CitizenshipStatus citizenshipStatus;
    private String alienRegistrationNumber;
    private String foreignPassportNumber;
    private String foreignPassportCountry;
    private LocalDate workAuthExpirationDate;

    @Builder.Default
    private Boolean preparerTranslatorUsed = false;

    /**
     * When true: save partial state, do not run submission validation, do not flip status.
     * JSON wire name: "draft". Lombok-generated getter is still {@code isDraft()}.
     */
    @Builder.Default
    private boolean draft = false;
}
