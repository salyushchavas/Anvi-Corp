package com.anvicorp.api.dto.candidates;

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
public class CandidateDetailResponse {
    private UUID candidateId;
    private String name;
    private String email;
    private String phone;
    private LocalDate dateOfBirth;
    private Instant createdAt;
    /** Default resume metadata; null if no resume on file. */
    private ResumeSummary resume;
    private List<ApplicationSummary> applications;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ResumeSummary {
        private UUID id;
        private String fileName;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ApplicationSummary {
        private UUID id;
        private String position;
        private String entityName;
        private String status;
        private Instant appliedAt;
    }
}
