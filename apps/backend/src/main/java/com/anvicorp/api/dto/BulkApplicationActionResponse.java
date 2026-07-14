package com.anvicorp.api.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkApplicationActionResponse {
    /** Applications whose status was actually changed by this call. */
    private int updated;
    /** Applications skipped (already at target status, or id not found). */
    private int skipped;
}
