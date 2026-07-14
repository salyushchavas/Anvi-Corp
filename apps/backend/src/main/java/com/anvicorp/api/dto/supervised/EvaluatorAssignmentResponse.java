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
public class EvaluatorAssignmentResponse {
    private UUID assignmentId;
    private UUID candidateId;
    private String internName;
    private String title;
    private WorkAssignmentStatus status;
    private LocalDate dueDate;
    private Instant submittedAt;
}
