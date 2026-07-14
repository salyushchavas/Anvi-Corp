package com.anvicorp.api.dto;

import com.anvicorp.api.enums.EmploymentType;
import com.anvicorp.api.enums.JobPostingStatus;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobPostingResponse {
    private UUID id;
    private String slug;
    /** Anvi Corp Job ID — format {@code SKZ-JOB-YYYY-NNNNNN}. Immutable,
     *  unique, surfaced on the candidate listing + detail so candidates
     *  can reference a specific posting in correspondence. */
    private String jobId;
    private String title;
    private String description;
    private String requirements;
    private String location;
    private EmploymentType employmentType;
    private JobPostingStatus status;
    private String entityName;
    private Instant publishedAt;
    private Instant createdAt;

    /**
     * Populated only when the caller is an authenticated CANDIDATE — the
     * candidate's applications for this posting drive these three fields.
     * Public/unauthenticated callers always see {@code applied=false} +
     * nulls; the frontend treats all such postings as "available".
     */
    private boolean applied;
    /** Most-recent application id for this candidate × posting, or null. */
    private UUID applicationId;
    /** Real {@code ApplicationStatus} enum name, or null. */
    private String applicationStatus;

    /**
     * Total applications received for this posting. Populated by admin/staff
     * list endpoints; defaults to 0 elsewhere so public callers can ignore it.
     */
    private long applicantCount;

    /** UUID of the StaffingEntity owning this posting; null when unknown. */
    private UUID entityId;
}

