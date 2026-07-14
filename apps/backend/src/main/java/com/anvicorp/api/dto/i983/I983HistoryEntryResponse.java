package com.anvicorp.api.dto.i983;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class I983HistoryEntryResponse {
    private UUID auditId;
    private Instant timestamp;
    private String action;
    private String performedByName;
    private String performedByRole;
    private String summary;
}
