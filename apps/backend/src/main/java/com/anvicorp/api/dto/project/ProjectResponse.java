package com.anvicorp.api.dto.project;

import com.anvicorp.api.enums.ProjectStatus;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectResponse {
    private UUID id;
    private String title;
    private String description;
    private String deliverables;
    private List<String> resourceLinks;

    private UUID internCandidateId;
    private String internName;
    private UUID engagementId;
    private UUID assignedById;
    private String assignedByName;

    private LocalDate startDate;
    private LocalDate dueDate;
    private ProjectStatus status;
    private Integer progressPct;

    private String reviewNotes;
    private UUID reviewedById;
    private String reviewedByName;
    private Instant reviewedAt;

    private Instant createdAt;
    private Instant startedAt;
    private Instant submittedAt;
    private Instant completedAt;
    private Instant updatedAt;

    private List<ProjectTaskResponse> tasks;
    private List<ProjectSubmissionResponse> submissions;
}
