package com.anvicorp.api.dto.everify;

import com.anvicorp.api.enums.EVerifyStatus;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EVerifyCaseSummaryResponse {
    private UUID id;
    private UUID i9FormId;
    private String candidateName;
    private String candidateEmail;
    private String caseNumber;
    private EVerifyStatus status;
    private Instant openedAt;
    private Instant closedAt;
    private Long daysOpen;
    // Phase 3 step 7 — surfaced on the HR list so the queue is sorted by federal deadline.
    private LocalDate dueBy;
    /** Derived from {@link #status}; see {@code EVerifyCaseResponse#phase}. */
    private String phase;
    /** True when {@code dueBy} is in the past AND {@code phase != AUTHORIZED}. */
    private boolean overdue;
    private Instant createdAt;
    private Instant updatedAt;
}
