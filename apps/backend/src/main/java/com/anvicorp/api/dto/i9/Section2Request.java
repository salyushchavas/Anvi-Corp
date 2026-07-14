package com.anvicorp.api.dto.i9;

import lombok.*;

import java.time.LocalDate;

/**
 * Section 2 payload. As with Section 1, bean-validation is permissive so
 * drafts can be saved with partial data; full validation runs in the service
 * only when isDraft=false.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Section2Request {

    private LocalDate firstDayOfEmployment;

    // List A
    private String listATitle;
    private String listAIssuingAuthority;
    private String listADocumentNumber;
    private LocalDate listAExpirationDate;

    // List B
    private String listBTitle;
    private String listBIssuingAuthority;
    private String listBDocumentNumber;
    private LocalDate listBExpirationDate;

    // List C
    private String listCTitle;
    private String listCIssuingAuthority;
    private String listCDocumentNumber;

    private String additionalInformation;

    // Employer attestation
    private String employerName;
    private String employerTitle;
    private String businessOrganizationName;
    private String businessAddress;

    /**
     * When true: save partial state, do not run submission validation, do not flip status.
     * JSON wire name: "draft". Lombok-generated getter is still {@code isDraft()}.
     */
    @Builder.Default
    private boolean draft = false;
}
