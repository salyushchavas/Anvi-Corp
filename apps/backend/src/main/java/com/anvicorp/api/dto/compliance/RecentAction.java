package com.anvicorp.api.dto.compliance;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecentAction {
    private Instant timestamp;
    private String summary;
    private String performedByName;
    private String performedByRole;
    /** "I9Form", "I983Plan", "Offer", "EVerifyCase", etc. */
    private String entityType;
    private String entityLinkUrl;
}
