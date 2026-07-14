package com.anvicorp.api.dto.candidates;

import com.anvicorp.api.enums.ApplicationStatus;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandidateListItemResponse {
    private UUID candidateId;
    private String name;
    private String email;
    private String phone;
    private long applicationCount;
    /** Status of the most recently applied/updated application, or null. */
    private ApplicationStatus latestStatus;
    /** JobPosting title of that most recent application, or null. */
    private String latestPosition;
    private boolean hasResume;
    private Instant createdAt;
}
