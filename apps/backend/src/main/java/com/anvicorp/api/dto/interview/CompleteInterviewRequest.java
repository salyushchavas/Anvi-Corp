package com.anvicorp.api.dto.interview;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompleteInterviewRequest {

    public enum Decision { SELECTED, HOLD, REJECTED }

    @NotNull(message = "decision is required")
    private Decision decision;

    /** Applicant-safe message. Service enforces a ≥20-char minimum. */
    @NotNull(message = "applicantVisibleNotes is required")
    @Size(min = 20, max = 4000,
            message = "applicantVisibleNotes must be 20-4000 characters")
    private String applicantVisibleNotes;

    /** ERM-only notes. Never returned to the applicant. */
    @Size(max = 8000)
    private String internalNotes;
}
