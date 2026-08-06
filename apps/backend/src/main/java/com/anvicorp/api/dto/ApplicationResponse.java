package com.anvicorp.api.dto;

import com.anvicorp.api.enums.ApplicationStatus;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationResponse {
    private UUID id;
    private String candidateName;
    private String candidateEmail;
    private String jobPostingTitle;
    private UUID jobPostingId;
    private UUID resumeId;
    private String resumeFileName;
    private ApplicationStatus status;
    private Instant appliedAt;
    private Instant statusUpdatedAt;
    private String recruiterNotes;
    /** 1-5 from the review screen, nullable. */
    private Integer recruiterRating;

    /** Phase 2 — applicant-typed motivation captured at apply time. */
    private String statementOfInterest;

    /** Phase 2 — applicant-safe outcome message; null until ERM sets it. */
    private String applicantVisibleFeedback;

    /**
     * ERM Phase 2 — CSV of field keys (resume,workAuth,education,other) the
     * intern must provide when stage is INFO_REQUESTED. Drives the amber
     * banner + ProvideInfoModal on the intern detail page.
     */
    private String infoRequestedFieldsCsv;

    /** When the ERM flipped the application to INFO_REQUESTED. */
    private Instant infoRequestedAt;

    /**
     * Human label of the reason code the ERM picked (e.g. "Updated resume",
     * "Other (specify)"). Applicant-safe by design — the label copy on
     * ReasonCode is the same string ERM sees in the picker and is not a
     * private note. Null when no reason code was captured.
     */
    private String infoRequestedReasonLabel;

    /**
     * ERM's free-text specifics on the request — shown to the applicant
     * verbatim so they know exactly what's being asked. Only populated when
     * the reason code required free text (e.g. REQUEST_INFO_OTHER, or when
     * the ERM opted to add context on the pre-filled reason codes).
     */
    private String infoRequestedMessage;

    /** Free-text response the applicant typed when closing INFO_REQUESTED. */
    private String infoProvidedResponse;

    /** When the applicant submitted their response. */
    private Instant infoProvidedAt;
}
