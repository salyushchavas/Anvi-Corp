package com.anvicorp.api.dto.supervised;

import com.anvicorp.api.enums.WorkAssignmentStatus;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssignmentResponse {
    private UUID id;
    private String title;
    private String description;
    private LocalDate weekOf;
    private LocalDate dueDate;
    private WorkAssignmentStatus status;
    private String submissionText;
    private String submissionLink;
    private Instant submittedAt;
    private String reviewNote;
    private Instant reviewedAt;
    private String assignedByName;
    private Instant createdAt;
}
