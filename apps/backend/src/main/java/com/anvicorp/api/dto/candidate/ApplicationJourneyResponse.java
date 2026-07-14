package com.anvicorp.api.dto.candidate;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-application status-journey payload for the candidate's My Applications
 * page. Every stage-date field is OPTIONAL — we only populate dates we can
 * confidently derive from {@code Application}/{@code Interview}/{@code Offer}
 * entities or {@code AuditLog} rows we actually wrote. We never fabricate
 * timestamps for stages we can't pin down.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationJourneyResponse {

    private UUID id;
    private String position;
    private String entityName;
    /** Real ApplicationStatus enum name. */
    private String status;
    /** 0..4 in the 5-stage funnel; -1 for exited statuses. */
    private int stageIndex;
    private boolean isExited;

    private Journey journey;

    /** Action the candidate should take right now, or null when nothing is pending. */
    private ActionNeeded actionNeeded;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Journey {
        private Instant appliedAt;
        private Instant shortlistedAt;
        private InterviewBit interview;
        private OfferBit offer;
        private Instant hiredAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InterviewBit {
        private Instant scheduledAt;
        /** Real InterviewStatus enum name. */
        private String status;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OfferBit {
        private UUID id;
        /** Real OfferStatus enum name. */
        private String status;
        private Instant expiresAt;
        /** {@code Offer.respondedAt} when accepted/declined. */
        private Instant decidedAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ActionNeeded {
        private String label;
        private String href;
    }
}
