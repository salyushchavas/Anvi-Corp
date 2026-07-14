package com.anvicorp.api.dto.supervised;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InternSummaryResponse {
    private UUID candidateId;
    private String name;
    private String email;
    /** Job title of the hired application. */
    private String position;
    private String entityName;
    /** Timestamp the application reached HIRED (falls back to statusUpdatedAt). */
    private Instant hiredDate;
    /** Full name of the assigned Technical Evaluator, or null if unassigned. */
    private String assignedEvaluatorName;
}
