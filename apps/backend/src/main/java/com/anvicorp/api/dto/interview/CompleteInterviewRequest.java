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

    /** Applicant-safe message. Optional as of the F8 revision — the
     *  ERM can send a scorecard to the Manager with no applicant-visible
     *  notes; only the upper bound is enforced. */
    @Size(max = 4000, message = "applicantVisibleNotes must be at most 4000 characters")
    private String applicantVisibleNotes;

    /** ERM-only notes. Never returned to the applicant. */
    @Size(max = 8000)
    private String internalNotes;
}
