package com.anvicorp.api.dto.i9;

import com.anvicorp.api.enums.CitizenshipStatus;
import com.anvicorp.api.enums.I9Status;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class I9FormResponse {

    // Identity
    private UUID id;
    private UUID candidateId;
    private String candidateName;
    private String candidateEmail;
    private I9Status status;

    // Section 1
    private String lastName;
    private String firstName;
    private String middleInitial;
    private String otherLastNamesUsed;
    private String addressStreet;
    private String addressAptNumber;
    private String addressCity;
    private String addressState;
    private String addressZipCode;
    private LocalDate dateOfBirth;
    private String ssn;
    private String email;
    private String phoneNumber;
    private CitizenshipStatus citizenshipStatus;
    private String alienRegistrationNumber;
    private String foreignPassportNumber;
    private String foreignPassportCountry;
    private LocalDate workAuthExpirationDate;
    private Boolean preparerTranslatorUsed;
    private Instant section1SignedAt;
    private String section1SignedByName;

    // Section 2
    private LocalDate firstDayOfEmployment;
    private String listATitle;
    private String listAIssuingAuthority;
    private String listADocumentNumber;
    private LocalDate listAExpirationDate;
    private String listBTitle;
    private String listBIssuingAuthority;
    private String listBDocumentNumber;
    private LocalDate listBExpirationDate;
    private String listCTitle;
    private String listCIssuingAuthority;
    private String listCDocumentNumber;
    private String additionalInformation;
    private String employerName;
    private String employerTitle;
    private String businessOrganizationName;
    private String businessAddress;
    private Instant section2SignedAt;
    private String section2SignedByName;

    // Computed — Phase 3 step 5 split: Section 1 and Section 2 each have their
    // own due-by date and overdue flag. Legacy {@code overdue} stays for one
    // release and now mirrors {@code section2Overdue} so existing UI keeps working.
    private LocalDate section1DueDate;
    private LocalDate section2DueDate;
    private boolean section1Overdue;
    private boolean section2Overdue;
    /** @deprecated alias for section2Overdue. Will be removed once UI consumers move. */
    @Deprecated
    private boolean overdue;
    /** Days from today to section2DueDate; negative when overdue; null when no due date. */
    private Long daysUntilDue;

    private Instant createdAt;
    private Instant updatedAt;
}
