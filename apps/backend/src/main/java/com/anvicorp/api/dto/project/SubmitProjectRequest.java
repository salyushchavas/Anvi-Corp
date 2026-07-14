package com.anvicorp.api.dto.project;

import lombok.*;

import java.util.List;

/**
 * Intern → submit a deliverable. The submission writes a new
 * {@link com.anvicorp.api.entity.ProjectSubmission} row (keeps history)
 * and transitions the project to SUBMITTED.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubmitProjectRequest {
    /** Submission note — what was done this round, what to look at first. */
    private String description;
    /** Deliverable links (PR / repo / deploy URL / docs). */
    private List<String> links;
}
