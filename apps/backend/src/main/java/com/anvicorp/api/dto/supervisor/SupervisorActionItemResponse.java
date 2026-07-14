package com.anvicorp.api.dto.supervisor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row in the supervisor's "Needs your attention" queue. Same shape as
 * the operations / HR dashboards' action items so the frontend renders them
 * with the identical card grammar.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupervisorActionItemResponse {
    private String key;
    private String label;
    private long count;
    private String href;
}
