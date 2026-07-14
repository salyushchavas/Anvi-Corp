package com.anvicorp.api.dto.compliance;

import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpcomingDeadline {
    private String label;
    private LocalDate dueDate;
    /** Negative when overdue. */
    private Long daysUntilDue;
    private String candidateName;
    private String linkUrl;
}
