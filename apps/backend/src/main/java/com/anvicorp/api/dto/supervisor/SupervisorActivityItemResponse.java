package com.anvicorp.api.dto.supervisor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One row in the supervisor's recent-activity feed. Pre-rendered summary
 * string (no raw audit before/after JSON). Scoped server-side to this
 * supervisor's interns; SUPER_ADMIN sees activity across all active
 * engagements.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupervisorActivityItemResponse {
    private Instant timestamp;
    private String summary;
    private String internName;
    private String entityType;
    private String href;
}
