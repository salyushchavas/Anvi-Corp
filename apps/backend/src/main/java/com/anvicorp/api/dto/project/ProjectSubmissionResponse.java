package com.anvicorp.api.dto.project;

import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectSubmissionResponse {
    private UUID id;
    private String description;
    private List<String> links;
    private Instant submittedAt;
}
