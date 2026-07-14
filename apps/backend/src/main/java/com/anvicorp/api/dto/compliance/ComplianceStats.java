package com.anvicorp.api.dto.compliance;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceStats {

    private I9Stats i9;
    private I983Stats i983;
    private EverifyStats everify;
    private OfferStats offers;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class I9Stats {
        private long total;
        private long pending;
        private long completed;
        private long overdue;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class I983Stats {
        private long total;
        private long draft;
        private long complete;
        private long submittedToDso;
        private long approved;
        private long rejected;
        private long amendment;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EverifyStats {
        private long total;
        private long pendingSubmission;
        private long open;
        private long tnc;
        private long authorized;
        private long closed;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OfferStats {
        /** Currently SENT — awaiting candidate response. */
        private long totalActive;
        private long pending;
        /** Accepted in the last 30 days. */
        private long accepted;
        /** Declined in the last 30 days. */
        private long declined;
    }
}
