package com.anvicorp.api.dto.i983;

import com.anvicorp.api.enums.DsoApprovalStatus;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DsoResponseRequest {

    /** Must be APPROVED, REJECTED, or AMENDMENT_REQUESTED. Validated in the service. */
    @NotNull
    private DsoApprovalStatus approvalStatus;

    /** Optional but encouraged for REJECTED / AMENDMENT_REQUESTED. */
    private String notes;
}
