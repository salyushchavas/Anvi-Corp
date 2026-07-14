package com.anvicorp.api.dto.i9;

import com.anvicorp.api.enums.I9Status;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class I9SummaryResponse {
    private UUID id;
    private UUID candidateId;
    private String candidateName;
    private String candidateEmail;
    private String jobPostingTitle;
    private I9Status status;
    private LocalDate firstDayOfEmployment;
    // Phase 3 step 5 — both sections get an explicit due date + overdue flag.
    private LocalDate section1DueDate;
    private LocalDate section2DueDate;
    private boolean section1Overdue;
    private boolean section2Overdue;
    /** @deprecated alias for section2Overdue; kept until existing UI consumers move. */
    @Deprecated
    private boolean overdue;
    private Long daysUntilDue;
}
