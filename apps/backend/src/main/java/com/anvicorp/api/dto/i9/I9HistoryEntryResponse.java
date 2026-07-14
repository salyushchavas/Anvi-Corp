package com.anvicorp.api.dto.i9;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class I9HistoryEntryResponse {
    private UUID auditId;
    private Instant timestamp;
    private String action;
    private String performedByName;
    private String performedByRole;
    /** Human-readable one-line summary for the timeline UI. */
    private String summary;
}
