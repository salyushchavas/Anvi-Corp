package com.anvicorp.api.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Intern → edit a weekly report. All fields are optional — null fields
 * leave the existing value untouched.
 *
 * <p>{@code submit=true} also transitions DRAFT or RETURNED → SUBMITTED in
 * the same call. Folding submit into PUT keeps the surface area at exactly
 * the six endpoints the spec lists. Has no effect when the row is already
 * SUBMITTED, and triggers a 400 when the row is APPROVED (because the
 * service-level lock already rejected the edit).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateWeeklyReportRequest {

    private LocalDate weekStart;
    private String completedWork;
    private String blockers;
    private String learningOutcomes;
    private String nextPlan;

    private Boolean submit;
}
