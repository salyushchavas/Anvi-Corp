package com.anvicorp.api.dto.everify;

import com.anvicorp.api.enums.EVerifyClosureReason;
import com.anvicorp.api.enums.EVerifyStatus;
import com.anvicorp.api.enums.PhotoMatchResult;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EVerifyCaseResponse {
    private UUID id;
    private UUID i9FormId;
    private String candidateName;
    private String candidateEmail;
    private UUID candidateId;
    private String caseNumber;
    private EVerifyStatus status;
    private EVerifyClosureReason closureReason;
    private Instant openedAt;
    private Instant closedAt;
    private Boolean photoMatchRequired;
    private PhotoMatchResult photoMatchResult;
    private Boolean additionalVerificationRequired;
    private String notes;
    private Long daysOpen;
    // Phase 3 step 7 — federal-deadline tracking + UI-friendly phase.
    private LocalDate dueBy;
    /**
     * Derived from {@link #status}:
     *   PENDING_SUBMISSION | OPEN          -> CREATED
     *   EMPLOYMENT_AUTHORIZED              -> AUTHORIZED
     *   TENTATIVE_NONCONFIRMATION          -> IN_REVIEW
     *   FINAL_NONCONFIRMATION              -> NOT_AUTHORIZED
     *   CLOSED                             -> CLOSED
     */
    private String phase;
    /** True when {@code dueBy} is in the past AND {@code phase != AUTHORIZED}. */
    private boolean overdue;
    private Instant lastSyncedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdByName;
}
